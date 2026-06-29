package com.sslproxy.schema.server

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.{DbKind, MigratorConfig, ServerConfig}
import com.sslproxy.schema.store.{Models, PatchStore, RunStore, TargetStore, ValidationStore}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.multipart.{Multipart, Part}
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

class RoutesSuite extends FunSuite:
  import Models.given

  test("target routes create, list, fetch, update, and delete") {
    val result = routeFixture
      .use { routes =>
        for
          created <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Alpha", password = None)))
          createdJson <- bodyJson(created)
          id <- IO.fromEither(createdJson.hcursor.get[String]("id"))
          listed <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/targets")))
          listedJson <- bodyJson(listed)
          fetched <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/targets/$id")))
          updated <- routes.run(jsonRequest(Method.PUT, s"/targets/$id", targetPayload("Beta")))
          updatedFetched <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/targets/$id")))
          updatedJson <- bodyJson(updatedFetched)
          deleted <- routes.run(Request[IO](Method.DELETE, Uri.unsafeFromString(s"/targets/$id")))
          missing <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/targets/$id")))
        yield (
          created.status,
          listedJson,
          fetched.status,
          updated.status,
          updatedJson.hcursor.get[String]("label"),
          deleted.status,
          missing.status
        )
      }
      .unsafeRunSync()

    val (createdStatus, listedJson, fetchedStatus, updatedStatus, updatedLabel, deletedStatus, missingStatus) = result
    assertEquals(createdStatus, Status.Created)
    assertEquals(listedJson.hcursor.downField("targets").values.map(_.size), Some(1))
    assertEquals(fetchedStatus, Status.Ok)
    assertEquals(updatedStatus, Status.Ok)
    assertEquals(updatedLabel, Right("Beta"))
    assertEquals(deletedStatus, Status.NoContent)
    assertEquals(missingStatus, Status.NotFound)
  }

  test("target routes return bad request for malformed payloads") {
    val result = routeFixture
      .use { routes =>
        val payload = Json.obj(
          "label" -> Json.fromString("Broken"),
          "app_name" -> Json.fromString("app"),
          "env" -> Json.fromString("dev")
        )

        for
          response <- routes.run(jsonRequest(Method.POST, "/targets", payload))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.BadRequest)
    assertEquals(json.hcursor.get[String]("error").map(_.startsWith("invalid target payload:")), Right(true))
  }

  test("target routes reject unsupported JDBC URL schemes") {
    val result = routeFixture
      .use { routes =>
        val payload = targetPayload("Wrong scheme", jdbcUrl = "jdbc:postgres://sync@192.168.1.221:5432/sync")

        for
          response <- routes.run(jsonRequest(Method.POST, "/targets", payload))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.BadRequest)
    assertEquals(
      json.hcursor.get[String]("error"),
      Right(
        "Postgres JDBC URLs must start with jdbc:postgresql://, for example jdbc:postgresql://host:5432/database?user=username"
      )
    )
  }

  test("target test routes reject hosts outside the configured allowlist") {
    val result = routeFixture
      .use { routes =>
        val payload = targetPayload("Blocked", jdbcUrl = "jdbc:postgresql://db.internal:5432/app?user=app")

        for
          response <- routes.run(jsonRequest(Method.POST, "/targets/test", payload))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.Forbidden)
    assertEquals(json.hcursor.get[String]("error"), Right("database connection tests are not allowed for this target"))
  }

  test("target delete conflicts when dependent patch records exist") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          _ <- routes.run(patchUploadRequest(targetId))
          deleted <- routes.run(Request[IO](Method.DELETE, Uri.unsafeFromString(s"/targets/$targetId")))
          fetched <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/targets/$targetId")))
        yield deleted.status -> fetched.status
      }
      .unsafeRunSync()

    val (deleteStatus, fetchStatus) = result
    assertEquals(deleteStatus, Status.Conflict)
    assertEquals(fetchStatus, Status.Ok)
  }

  test("schema route returns Postgres manifest data when live catalog is unavailable") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(
            jsonRequest(
              Method.POST,
              "/targets",
              targetPayload("Target", jdbcUrl = "jdbc:postgresql://127.0.0.1:1/app?user=app&connectTimeout=1")
            )
          )
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          response <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/schema?target_id=$targetId")))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.Ok)
    assertEquals(json.hcursor.get[Boolean]("supported"), Right(true))
    assertEquals(json.hcursor.get[String]("db_kind"), Right("postgres"))
    assertEquals(json.hcursor.downField("objects").downArray.get[String]("name"), Right("devices"))
    assertEquals(json.hcursor.downField("objects").downArray.get[String]("status"), Right("defined"))
    assertEquals(json.hcursor.downField("warnings").values.exists(_.nonEmpty), true)
  }

  test("drift route returns warnings instead of failing when Postgres live catalog is unavailable") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(
            jsonRequest(
              Method.POST,
              "/targets",
              targetPayload("Target", jdbcUrl = "jdbc:postgresql://127.0.0.1:1/app?user=app&connectTimeout=1")
            )
          )
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          response <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/drift?target_id=$targetId")))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.Ok)
    assertEquals(json.hcursor.get[Boolean]("supported"), Right(true))
    assertEquals(json.hcursor.get[String]("db_kind"), Right("postgres"))
    assertEquals(json.hcursor.downField("items").values.map(_.size), Some(0))
    assertEquals(json.hcursor.downField("warnings").values.exists(_.nonEmpty), true)
  }

  test("schema and drift routes explicitly mark Oracle targets unsupported") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(
            jsonRequest(
              Method.POST,
              "/targets",
              targetPayload("Oracle", jdbcUrl = "jdbc:oracle:thin:@//localhost:1521/FREE")
            )
          )
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          schemaResponse <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/schema?target_id=$targetId")))
          schemaJson <- bodyJson(schemaResponse)
          driftResponse <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/drift?target_id=$targetId")))
          driftJson <- bodyJson(driftResponse)
        yield (schemaResponse.status, schemaJson, driftResponse.status, driftJson)
      }
      .unsafeRunSync()

    val (schemaStatus, schemaJson, driftStatus, driftJson) = result
    assertEquals(schemaStatus, Status.Ok)
    assertEquals(driftStatus, Status.Ok)
    assertEquals(schemaJson.hcursor.get[Boolean]("supported"), Right(false))
    assertEquals(driftJson.hcursor.get[Boolean]("supported"), Right(false))
    assertEquals(schemaJson.hcursor.get[String]("db_kind"), Right("oracle"))
    assertEquals(driftJson.hcursor.get[String]("db_kind"), Right("oracle"))
  }

  test("patch upload, run trigger, and validation routes interoperate") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          patchResponse <- routes.run(patchUploadRequest(targetId))
          patchJson <- bodyJson(patchResponse)
          patchId <- IO.fromEither(patchJson.hcursor.get[String]("id"))
          runResponse <- routes.run(
            jsonRequest(
              Method.POST,
              "/runs",
              Json.obj("target_id" -> Json.fromString(targetId), "patch_id" -> Json.fromString(patchId))
            )
          )
          runJson <- bodyJson(runResponse)
          runId <- IO.fromEither(runJson.hcursor.get[String]("id"))
          validationResponse <- awaitValidation(routes, runId)
          validationJson <- bodyJson(validationResponse)
          firstScriptOrder <- IO.fromEither(patchJson.hcursor.downField("scripts").downArray.get[Int]("order"))
        yield (
          patchResponse.status,
          runResponse.status,
          validationResponse.status,
          validationJson.hcursor.get[String]("status"),
          firstScriptOrder
        )
      }
      .unsafeRunSync()

    val (patchStatus, runStatus, validationStatus, validationState, firstScriptOrder) = result
    assertEquals(patchStatus, Status.Created)
    assertEquals(runStatus, Status.Created)
    assertEquals(validationStatus, Status.Ok)
    assertEquals(validationState, Right("clean"))
    assertEquals(firstScriptOrder, 1)
  }

  test("patch upload over the size limit returns payload too large") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          response <- routes.run(largePatchUploadRequest(targetId))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.PayloadTooLarge)
    assertEquals(json.hcursor.get[String]("error"), Right("uploaded SQL file exceeds 10 MiB limit"))
  }

  private def routeFixture =
    cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-routes")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .evalMap { stageDir =>
        val sqlDir = stageDir.resolve("sql")
        val patchStageDir = stageDir.resolve("patches")
        for
          _ <- writeSqlManifest(sqlDir)
          targetStore <- TargetStore.inMemory
          patchStore <- PatchStore.inMemory(patchStageDir)
          runStore <- RunStore.inMemory
          validationStore <- ValidationStore.inMemory
        yield Routes
          .all(migratorConfig(patchStageDir, sqlDir), targetStore, patchStore, runStore, validationStore)
          .orNotFound
      }

  private def migratorConfig(stageDir: Path, sqlDir: Path): MigratorConfig =
    MigratorConfig(
      dbKind = DbKind.Postgres,
      databaseUrl = None,
      sqlDir = sqlDir,
      dryRun = false,
      verbose = false,
      continueOnError = false,
      connectRetries = 0,
      connectRetryBackoff = 1.second,
      oracleWallet = None,
      oracleTnsAlias = None,
      oracleUser = None,
      oraclePasswordFile = None,
      json = false,
      server = serverConfig(stageDir)
    )

  private def serverConfig(stageDir: Path): ServerConfig =
    ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = None,
      jwtSecret = "jwt",
      devAuthSecret = "dev",
      dbTestAllowedHosts = Set("localhost"),
      patchStageDir = stageDir
    )

  private def writeSqlManifest(sqlDir: Path): IO[Unit] =
    val tableDir = sqlDir.resolve("tables")
    val sql =
      """-- object: public.devices
        |-- folder: tables
        |-- depends_on: -
        |create table if not exists public.devices (
        |  id bigint primary key
        |);
        |""".stripMargin
    IO.blocking {
      Files.createDirectories(tableDir)
      Files.writeString(tableDir.resolve("001_devices.sql"), sql, StandardCharsets.UTF_8)
      ()
    }

  private def targetPayload(
    label: String,
    password: Option[String] = Some("secret"),
    jdbcUrl: String = "jdbc:postgresql://localhost:5432/app?user=app&sslmode=disable"
  ): Json =
    val requiredFields = List(
      "label" -> Json.fromString(label),
      "app_name" -> Json.fromString("app"),
      "env" -> Json.fromString("dev"),
      "jdbc_url" -> Json.fromString(jdbcUrl)
    )
    val passwordField = password.map(value => "password" -> Json.fromString(value)).toList
    Json.obj((requiredFields ++ passwordField)*)

  private def jsonRequest(method: Method, path: String, body: Json): Request[IO] =
    Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)

  private def patchUploadRequest(targetId: String): Request[IO] =
    val sqlBytes = "select 1;".getBytes(StandardCharsets.UTF_8).toVector
    val multipart = Multipart[IO](
      Vector(
        Part.formData[IO]("target_id", targetId),
        Part.fileData[IO]("files", "001_test.sql", Stream.emits(sqlBytes).covary[IO])
      )
    )
    Request[IO](Method.POST, Uri.unsafeFromString("/patches")).withEntity(multipart).putHeaders(multipart.headers)

  private def largePatchUploadRequest(targetId: String): Request[IO] =
    val sqlBytes = Vector.fill((10L * 1024L * 1024L + 1L).toInt)(0.toByte)
    val multipart = Multipart[IO](
      Vector(
        Part.formData[IO]("target_id", targetId),
        Part.fileData[IO]("files", "001_large.sql", Stream.emits(sqlBytes).covary[IO])
      )
    )
    Request[IO](Method.POST, Uri.unsafeFromString("/patches")).withEntity(multipart).putHeaders(multipart.headers)

  private def bodyJson(response: Response[IO]): IO[Json] =
    response.body.compile.toVector
      .map(bytes => String(bytes.toArray, StandardCharsets.UTF_8))
      .flatMap(text => IO.fromEither(parse(text)))

  private def awaitValidation(routes: HttpApp[IO], runId: String): IO[Response[IO]] =
    def loop(remaining: Int): IO[Response[IO]] =
      routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/validation/$runId"))).flatMap { response =>
        if response.status == Status.Ok || remaining <= 0 then IO.pure(response)
        else response.body.compile.drain *> IO.sleep(50.millis) *> loop(remaining - 1)
      }

    loop(20)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
