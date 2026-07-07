package com.sslproxy.schema.server

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.TestSqlSupport
import com.sslproxy.schema.config.{DbKind, MigratorConfig, ServerConfig}
import com.sslproxy.schema.server.auth.{AuthContext, Claims, UserRole}
import com.sslproxy.schema.store.{
  AuditStore,
  Models,
  PatchStore,
  RunStore,
  SnapshotStore,
  SqlFileStore,
  StoredSqlFile,
  TargetPayload,
  TargetStore,
  ValidationStore
}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.multipart.{Multipart, Part}
import munit.FunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.concurrent.duration.*

class RoutesSuite extends FunSuite with TestSqlSupport:
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

  test("target routes reject hostless Postgres JDBC URLs") {
    val result = routeFixture
      .use { routes =>
        val payload = targetPayload("Hostless", jdbcUrl = "jdbc:postgresql:///sync")

        for
          response <- routes.run(jsonRequest(Method.POST, "/targets", payload))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.BadRequest)
    assertEquals(json.hcursor.get[String]("error"), Right("invalid Postgres URL: host is required"))
  }

  test("target routes accept postgres URLs and store sanitized JDBC URLs") {
    val result = routeFixture
      .use { routes =>
        val payload = targetPayload(
          "Postgres URL",
          password = None,
          jdbcUrl = "postgres://sync:p%40ss@localhost:5432/sync?sslmode=disable"
        )

        for
          response <- routes.run(jsonRequest(Method.POST, "/targets", payload))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.Created)
    assertEquals(
      json.hcursor.get[String]("jdbc_url"),
      Right("jdbc:postgresql://localhost:5432/sync?sslmode=disable&user=sync")
    )
    assert(!json.noSpaces.contains("p@ss"))
  }

  test("target payload normalization extracts postgres URL passwords without storing them inline") {
    val normalized = TargetRoutes
      .validateTargetPayload(
        TargetPayload(
          label = "Postgres URL",
          app_name = "app",
          env = "dev",
          jdbc_url = "postgres://sync:p%40ss@localhost:5432/sync",
          password = None
        )
      )
      .toOption
      .get

    assertEquals(normalized.jdbc_url, "jdbc:postgresql://localhost:5432/sync?user=sync")
    assertEquals(normalized.password, Some("p@ss"))
  }

  test("target payload normalization converts JDBC postgres username authority to user parameter") {
    val normalized = TargetRoutes
      .validateTargetPayload(
        TargetPayload(
          label = "Postgres JDBC URL",
          app_name = "app",
          env = "dev",
          jdbc_url = "jdbc:postgresql://sync@192.168.1.221:5432/sync",
          password = Some("secret")
        )
      )
      .toOption
      .get

    assertEquals(normalized.jdbc_url, "jdbc:postgresql://192.168.1.221:5432/sync?user=sync")
    assertEquals(normalized.password, Some("secret"))
  }

  test("JDBC connection settings normalize stored postgres username authority") {
    val settings = JdbcConnectionProperties.normalize(
      "jdbc:postgresql://sync@192.168.1.221:5432/sync",
      Some("secret")
    )

    assertEquals(settings.jdbcUrl, "jdbc:postgresql://192.168.1.221:5432/sync")
    assertEquals(settings.user, Some("sync"))
    assertEquals(settings.password, Some("secret"))
  }

  test("target routes reject conflicting postgres URL and password field passwords") {
    val result = routeFixture
      .use { routes =>
        val payload = targetPayload(
          "Conflicting passwords",
          password = Some("field-secret"),
          jdbcUrl = "postgres://sync:url-secret@localhost:5432/sync"
        )

        for
          response <- routes.run(jsonRequest(Method.POST, "/targets", payload))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.BadRequest)
    assertEquals(json.hcursor.get[String]("error"), Right("Postgres URL password does not match the password field"))
  }

  test("target routes reject inline JDBC credentials before storing targets") {
    val result = routeFixture
      .use { routes =>
        val payload = targetPayload(
          "Inline credentials",
          jdbcUrl = "jdbc:postgresql://app:secret@localhost:5432/app?user=app"
        )

        for
          response <- routes.run(jsonRequest(Method.POST, "/targets", payload))
          listed <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/targets")))
          json <- bodyJson(response)
          listedJson <- bodyJson(listed)
        yield (response.status, json, listedJson)
      }
      .unsafeRunSync()

    val (status, json, listedJson) = result
    assertEquals(status, Status.BadRequest)
    assertEquals(
      json.hcursor.get[String]("error"),
      Right("JDBC URL must not contain inline credentials; provide credentials in the password field")
    )
    assertEquals(listedJson.hcursor.downField("targets").values.map(_.size), Some(0))
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

  test("target test allowlist permits approved Postgres hosts") {
    val response = TargetRoutes
      .withDbAccessAllowed(
        serverConfig(Path.of("."), Set("192.168.1.221")),
        "jdbc:postgresql://192.168.1.221:5432/sync?user=sync",
        "blocked"
      ) {
        Ok()
      }
      .unsafeRunSync()

    assertEquals(response.status, Status.Ok)
  }

  test("target test wildcard allowlist permits URLs without a parsed host") {
    val response = TargetRoutes
      .withDbAccessAllowed(serverConfig(Path.of("."), Set("*")), "jdbc:oracle:thin:@tnsalias", "blocked") {
        Ok()
      }
      .unsafeRunSync()

    assertEquals(response.status, Status.Ok)
  }

  test("target test routes reject Oracle descriptors with multiple hosts") {
    val result = routeFixture(Set("db1.example"))
      .use { routes =>
        val descriptor =
          "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db1.example)(PORT=1521))(ADDRESS=(PROTOCOL=TCP)(HOST=db2.example)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=FREE)))"
        val payload = targetPayload("Oracle descriptor", jdbcUrl = descriptor)

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

  test("schema route normalizes uploaded SQL manifest for Postgres targets") {
    val uploadedSql =
      """-- object: public.uploaded_devices
        |-- folder: tables
        |-- depends_on: -
        |create table if not exists public.uploaded_devices (
        |  id bigint primary key
        |);
        |""".stripMargin
    val result = routeFixture
      .use { routes =>
        for
          uploadResponse <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_uploaded_devices.sql" -> uploadedSql,
            "sql/oracle/functions/001_oracle_only.sql" -> "create or replace function oracle_only return number as begin return 1; end;",
            "sql/000_baseline.sql" -> "select 1;"
          )))
          uploadJson <- bodyJson(uploadResponse)
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
        yield (uploadResponse.status, uploadJson, response.status, json)
      }
      .unsafeRunSync()

    val (uploadStatus, uploadJson, schemaStatus, schemaJson) = result
    val warnings = schemaJson.hcursor.downField("warnings").as[List[String]].getOrElse(Nil)

    assertEquals(uploadStatus, Status.Created)
    assertEquals(uploadJson.hcursor.downField("folders").as[List[String]], Right(List("baseline", "oracle/functions", "tables")))
    assertEquals(schemaStatus, Status.Ok)
    assertEquals(schemaJson.hcursor.downField("objects").downArray.get[String]("name"), Right("uploaded_devices"))
    assertEquals(schemaJson.hcursor.downField("objects").downArray.get[String]("source_file"), Right("tables/001_uploaded_devices.sql"))
    assert(!warnings.exists(_.contains("unrecognized sql folder")))
    assert(!warnings.exists(_.contains("has no files in store")))
  }

  test("sql zip upload over compressed size limit returns payload too large") {
    val limits = SqlFileRoutes.UploadLimits(
      maxUploadBytes = 1024,
      maxZipBytes = 8,
      maxZipEntryBytes = 1024,
      maxZipUncompressedBytes = 1024
    )
    val result = sqlFileRoutesWithLimits(limits)
      .use { routes =>
        for
          response <- routes.run(sqlZipUploadRequestBytes(Vector.fill(9)(0.toByte)))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.PayloadTooLarge)
    assertEquals(json.hcursor.get[String]("error"), Right("uploaded zip exceeds 8 bytes limit"))
  }

  test("sql zip upload enforces uncompressed entry limit") {
    val limits = SqlFileRoutes.UploadLimits(
      maxUploadBytes = 1024,
      maxZipBytes = 1024,
      maxZipEntryBytes = 10,
      maxZipUncompressedBytes = 1024
    )
    val result = sqlFileRoutesWithLimits(limits)
      .use { routes =>
        for
          response <- routes.run(sqlZipUploadRequest(List("sql/tables/001_large.sql" -> "select 1;\n--x")))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.PayloadTooLarge)
    assertEquals(
      json.hcursor.get[String]("error"),
      Right("zip entry 'sql/tables/001_large.sql' exceeds 10 bytes uncompressed limit")
    )
  }

  test("sql zip upload enforces total uncompressed SQL limit") {
    val limits = SqlFileRoutes.UploadLimits(
      maxUploadBytes = 1024,
      maxZipBytes = 1024,
      maxZipEntryBytes = 1024,
      maxZipUncompressedBytes = 12
    )
    val result = sqlFileRoutesWithLimits(limits)
      .use { routes =>
        for
          response <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_a.sql" -> "select;",
            "sql/tables/002_b.sql" -> "select;"
          )))
          json <- bodyJson(response)
        yield response.status -> json
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.PayloadTooLarge)
    assertEquals(json.hcursor.get[String]("error"), Right("zip archive exceeds 12 bytes uncompressed SQL limit"))
  }

  test("sql file upload rejects empty multipart without replacing existing files") {
    val result =
      (for
        store <- SqlFileStore.inMemory
        existing = StoredSqlFile.fromBytes(
          path = "tables/001_existing.sql",
          folder = "tables",
          filename = "001_existing.sql",
          bytes = "select 1;".getBytes(StandardCharsets.UTF_8),
          uploadedAt = "2026-07-02T12:00:00Z"
        )
        _ <- store.replaceAll(List(existing))
        routes = authedForTest(SqlFileRoutes.routes(store).orNotFound)
        multipart = Multipart[IO](Vector(Part.formData[IO]("note", "empty")))
        response <- routes.run(
          Request[IO](Method.POST, Uri.unsafeFromString("/sql-files/upload"))
            .withEntity(multipart)
            .putHeaders(multipart.headers)
        )
        json <- bodyJson(response)
        stored <- store.list
      yield (response.status, json, stored))
        .unsafeRunSync()

    val (status, json, stored) = result
    assertEquals(status, Status.BadRequest)
    assertEquals(json.hcursor.get[String]("error"), Right("No .sql files found in upload"))
    assertEquals(stored.map(_.path), List("tables/001_existing.sql"))
  }

  test("sql file upload rejects non-sql multipart filenames") {
    val result =
      sqlFileRoutesWithLimits(SqlFileRoutes.UploadLimits.default)
        .use { routes =>
          val multipart = Multipart[IO](
            Vector(
              Part.fileData[IO](
                "tables/files",
                "001_readme.txt",
                Stream.emits("not sql".getBytes(StandardCharsets.UTF_8).toVector).covary[IO]
              )
            )
          )
          for
            response <- routes.run(
              Request[IO](Method.POST, Uri.unsafeFromString("/sql-files/upload"))
                .withEntity(multipart)
                .putHeaders(multipart.headers)
            )
            json <- bodyJson(response)
          yield response.status -> json
        }
        .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.BadRequest)
    assertEquals(json.hcursor.get[String]("error"), Right("Only .sql file uploads are accepted"))
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

  test("schema and drift routes reject targets outside the configured allowlist") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(
            jsonRequest(
              Method.POST,
              "/targets",
              targetPayload("Blocked", jdbcUrl = "jdbc:postgresql://db.internal:5432/app?user=app")
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
    assertEquals(schemaStatus, Status.Forbidden)
    assertEquals(driftStatus, Status.Forbidden)
    assertEquals(schemaJson.hcursor.get[String]("error"), Right("database schema access is not allowed for this target"))
    assertEquals(driftJson.hcursor.get[String]("error"), Right("database drift access is not allowed for this target"))
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

  test("snapshot routes capture stored SQL files without returning content") {
    val result = routeFixture
      .use { routes =>
        for
          _ <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_devices.sql" -> "create table if not exists public.devices (id bigint primary key);"
          )))
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          createResponse <- routes.run(
            jsonRequest(
              Method.POST,
              "/snapshots",
              Json.obj("target_id" -> Json.fromString(targetId), "label" -> Json.fromString("before"))
            )
          )
          createJson <- bodyJson(createResponse)
          snapshotId <- IO.fromEither(createJson.hcursor.get[String]("id"))
          listResponse <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/snapshots?target_id=$targetId")))
          listJson <- bodyJson(listResponse)
          getResponse <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/snapshots/$snapshotId")))
          getJson <- bodyJson(getResponse)
        yield (createResponse.status, createJson, listResponse.status, listJson, getResponse.status, getJson)
      }
      .unsafeRunSync()

    val (createStatus, createJson, listStatus, listJson, getStatus, getJson) = result
    assertEquals(createStatus, Status.Created)
    assertEquals(listStatus, Status.Ok)
    assertEquals(getStatus, Status.Ok)
    assertEquals(createJson.hcursor.get[Int]("file_count"), Right(1))
    assertEquals(listJson.hcursor.downField("snapshots").values.map(_.size), Some(1))
    assertEquals(getJson.hcursor.downField("files").downArray.get[String]("path"), Right("tables/001_devices.sql"))
    assertEquals(getJson.hcursor.downField("files").downArray.get[String]("content_base64").isLeft, true)
  }

  test("snapshot diff reports added changed and removed files") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          _ <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_devices.sql" -> "create table if not exists public.devices (id bigint primary key);",
            "sql/views/001_devices_view.sql" -> "create or replace view public.devices_view as select id from public.devices;"
          )))
          baseResponse <- routes.run(jsonRequest(Method.POST, "/snapshots", Json.obj("target_id" -> Json.fromString(targetId))))
          baseJson <- bodyJson(baseResponse)
          baseId <- IO.fromEither(baseJson.hcursor.get[String]("id"))
          _ <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_devices.sql" -> "create table if not exists public.devices (id bigint primary key, name text);",
            "sql/functions/001_touch.sql" -> "create or replace function public.touch() returns void language sql as $$ select 1 $$;"
          )))
          compareResponse <- routes.run(jsonRequest(Method.POST, "/snapshots", Json.obj("target_id" -> Json.fromString(targetId))))
          compareJson <- bodyJson(compareResponse)
          compareId <- IO.fromEither(compareJson.hcursor.get[String]("id"))
          diffResponse <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/snapshots/$baseId/diff/$compareId")))
          diffJson <- bodyJson(diffResponse)
        yield diffResponse.status -> diffJson
      }
      .unsafeRunSync()

    val (status, json) = result
    val diffTypes = json.hcursor.downField("items").values.toList.flatten.flatMap(_.hcursor.get[String]("diff_type").toOption).sorted
    assertEquals(status, Status.Ok)
    assertEquals(diffTypes, List("added", "changed", "removed"))
  }

  test("rollback to snapshot rejects current-only files without rollback SQL") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          _ <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_devices.sql" -> "create table if not exists public.devices (id bigint primary key);"
          )))
          snapshotResponse <- routes.run(jsonRequest(Method.POST, "/snapshots", Json.obj("target_id" -> Json.fromString(targetId))))
          snapshotJson <- bodyJson(snapshotResponse)
          snapshotId <- IO.fromEither(snapshotJson.hcursor.get[String]("id"))
          _ <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_devices.sql" -> "create table if not exists public.devices (id bigint primary key);",
            "sql/tables/002_extra.sql" -> "create table if not exists public.extra (id bigint primary key);"
          )))
          rollbackResponse <- routes.run(
            jsonRequest(Method.POST, s"/snapshots/$snapshotId/rollback", Json.obj("target_id" -> Json.fromString(targetId)))
          )
          rollbackJson <- bodyJson(rollbackResponse)
        yield rollbackResponse.status -> rollbackJson
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.UnprocessableEntity)
    assertEquals(json.hcursor.downField("details").downArray.as[String].map(_.contains("missing -- rollback: reference")), Right(true))
  }

  test("rollback to snapshot creates sourced patch and run in deterministic order") {
    val result = routeFixture
      .use { routes =>
        val rollbackSql = "drop table if exists public.extra;"
        val extraSql =
          """-- rollback: rollbacks/drop_extra.sql
            |create table if not exists public.extra (id bigint primary key);
            |""".stripMargin
        for
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          _ <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_devices.sql" -> "create table if not exists public.devices (id bigint primary key);",
            "sql/rollbacks/drop_extra.sql" -> rollbackSql
          )))
          snapshotResponse <- routes.run(jsonRequest(Method.POST, "/snapshots", Json.obj("target_id" -> Json.fromString(targetId))))
          snapshotJson <- bodyJson(snapshotResponse)
          snapshotId <- IO.fromEither(snapshotJson.hcursor.get[String]("id"))
          _ <- routes.run(sqlZipUploadRequest(List(
            "sql/tables/001_devices.sql" -> "create table if not exists public.devices (id bigint primary key);",
            "sql/rollbacks/drop_extra.sql" -> rollbackSql,
            "sql/tables/002_extra.sql" -> extraSql
          )))
          rollbackResponse <- routes.run(
            jsonRequest(Method.POST, s"/snapshots/$snapshotId/rollback", Json.obj("target_id" -> Json.fromString(targetId)))
          )
          rollbackJson <- bodyJson(rollbackResponse)
          patchId <- IO.fromEither(rollbackJson.hcursor.get[String]("patch_id"))
          patchResponse <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/patches/$patchId")))
          patchJson <- bodyJson(patchResponse)
          firstScriptOrder <- IO.fromEither(patchJson.hcursor.downField("scripts").downArray.get[Int]("order"))
          firstScriptName <- IO.fromEither(patchJson.hcursor.downField("scripts").downArray.get[String]("filename"))
        yield (rollbackResponse.status, rollbackJson, patchJson, snapshotId, firstScriptOrder, firstScriptName)
      }
      .unsafeRunSync()

    val (status, _, patchJson, snapshotId, firstScriptOrder, firstScriptName) = result
    assertEquals(status, Status.Created)
    assertEquals(patchJson.hcursor.get[String]("source_snapshot_id"), Right(snapshotId))
    assertEquals(firstScriptOrder, 1)
    assert(firstScriptName.contains("rollback_002_extra.sql"), firstScriptName)
  }

  test("audit route filters recorded mutation events") {
    val result = routeFixture
      .use { routes =>
        for
          targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Audited")))
          targetJson <- bodyJson(targetResponse)
          targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
          auditResponse <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/audit?entity_type=target&target_id=$targetId")))
          auditJson <- bodyJson(auditResponse)
        yield auditResponse.status -> auditJson
      }
      .unsafeRunSync()

    val (status, json) = result
    assertEquals(status, Status.Ok)
    assertEquals(json.hcursor.downField("events").downArray.get[String]("action"), Right("target.create"))
  }

  test("RBAC denies viewer mutations and operator audit access") {
    val result = routeFixture
      .use { routes =>
        for
          viewerSnapshot <- routes.run(
            withClaims(
              jsonRequest(Method.POST, "/snapshots", Json.obj("target_id" -> Json.fromString("target-1"))),
              Claims("viewer", None, UserRole.Viewer)
            )
          )
          operatorTarget <- routes.run(
            withClaims(
              jsonRequest(Method.POST, "/targets", targetPayload("Operator denied")),
              Claims("operator", None, UserRole.Operator)
            )
          )
          operatorAudit <- routes.run(
            withClaims(
              Request[IO](Method.GET, Uri.unsafeFromString("/audit")),
              Claims("operator", None, UserRole.Operator)
            )
          )
          viewerRead <- routes.run(
            withClaims(
              Request[IO](Method.GET, Uri.unsafeFromString("/targets")),
              Claims("viewer", None, UserRole.Viewer)
            )
          )
        yield (viewerSnapshot.status, operatorTarget.status, operatorAudit.status, viewerRead.status)
      }
      .unsafeRunSync()

    val (viewerMutationStatus, operatorTargetStatus, operatorAuditStatus, viewerReadStatus) = result
    assertEquals(viewerMutationStatus, Status.Forbidden)
    assertEquals(operatorTargetStatus, Status.Forbidden)
    assertEquals(operatorAuditStatus, Status.Forbidden)
    assertEquals(viewerReadStatus, Status.Ok)
  }

  test("validation rerun uses real SQL validators") {
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
          _ <- awaitValidation(routes, runId)
          rerun <- routes.run(Request[IO](Method.POST, Uri.unsafeFromString(s"/validation/$runId/rerun")))
          rerunJson <- bodyJson(rerun)
        yield (rerun.status, rerunJson.hcursor.get[String]("status"), rerunJson.hcursor.downField("invalid").values.map(_.size))
      }
      .unsafeRunSync()

    val (status, validationStatus, invalidCount) = result
    assertEquals(status, Status.Ok)
    assertEquals(validationStatus, Right("errors"))
    assertEquals(invalidCount.exists(_ > 0), true)
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

  private def routeFixture: cats.effect.Resource[IO, HttpApp[IO]] =
    routeFixture(Set("localhost", "127.0.0.1"))

  private def routeFixture(allowedHosts: Set[String]): cats.effect.Resource[IO, HttpApp[IO]] =
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
          sqlFileStore <- SqlFileStore.inMemory
          snapshotStore <- SnapshotStore.inMemory
          auditStore <- AuditStore.inMemory
          runExecutor = RunExecutor.simulated(patchStore, runStore, validationStore, auditStore = Some(auditStore))
        yield
          val app = Routes
            .all(
              migratorConfig(patchStageDir, sqlDir, allowedHosts),
              targetStore,
              patchStore,
              runStore,
              validationStore,
              sqlFileStore,
              snapshotStore,
              auditStore,
              runExecutor
            )
            .orNotFound
          authedForTest(app)
      }

  private def sqlFileRoutesWithLimits(limits: SqlFileRoutes.UploadLimits): cats.effect.Resource[IO, HttpApp[IO]] =
    cats.effect.Resource.eval(
      SqlFileStore.inMemory.map(store => authedForTest(SqlFileRoutes.routes(store, limits).orNotFound))
    )

  private def migratorConfig(stageDir: Path, sqlDir: Path, allowedHosts: Set[String]): MigratorConfig =
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
      server = serverConfig(stageDir, allowedHosts)
    )

  private def serverConfig(stageDir: Path, allowedHosts: Set[String]): ServerConfig =
    ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = None,
      jwtSecret = "jwt",
      devAuthSecret = "dev",
      dbTestAllowedHosts = allowedHosts,
      patchStageDir = stageDir
    )

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

  private def authedForTest(app: HttpApp[IO]): HttpApp[IO] =
    Kleisli((request: Request[IO]) => app.run(withDefaultClaims(request)))

  private def withDefaultClaims(request: Request[IO]): Request[IO] =
    request.attributes.lookup(AuthContext.claimsKey) match
      case Some(_) => request
      case None => withClaims(request, Claims("test-admin", None, UserRole.Admin))

  private def withClaims(request: Request[IO], claims: Claims): Request[IO] =
    request.withAttribute(AuthContext.claimsKey, claims)

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

  private def sqlZipUploadRequest(entries: List[(String, String)]): Request[IO] =
    sqlZipUploadRequestBytes(zipBytes(entries).toVector)

  private def sqlZipUploadRequestBytes(bytes: Vector[Byte]): Request[IO] =
    val multipart = Multipart[IO](
      Vector(
        Part.fileData[IO]("file", "sql.zip", Stream.emits(bytes).covary[IO])
      )
    )
    Request[IO](Method.POST, Uri.unsafeFromString("/sql-files/upload-zip")).withEntity(multipart).putHeaders(multipart.headers)

  private def zipBytes(entries: List[(String, String)]): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val zip = new ZipOutputStream(bytes)
    try
      entries.foreach { case (path, content) =>
        zip.putNextEntry(new ZipEntry(path))
        zip.write(content.getBytes(StandardCharsets.UTF_8))
        zip.closeEntry()
      }
    finally zip.close()
    bytes.toByteArray

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
