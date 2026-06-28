package com.sslproxy.schema.server

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.ServerConfig
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
    val result = routeFixture.use { routes =>
      for
        created <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Alpha")))
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
      yield (created.status, listedJson, fetched.status, updated.status, updatedJson.hcursor.get[String]("label"), deleted.status, missing.status)
    }.unsafeRunSync()

    val (createdStatus, listedJson, fetchedStatus, updatedStatus, updatedLabel, deletedStatus, missingStatus) = result
    assertEquals(createdStatus, Status.Created)
    assertEquals(listedJson.hcursor.downField("targets").values.map(_.size), Some(1))
    assertEquals(fetchedStatus, Status.Ok)
    assertEquals(updatedStatus, Status.Ok)
    assertEquals(updatedLabel, Right("Beta"))
    assertEquals(deletedStatus, Status.NoContent)
    assertEquals(missingStatus, Status.NotFound)
  }

  test("patch upload, run trigger, and validation routes interoperate") {
    val result = routeFixture.use { routes =>
      for
        targetResponse <- routes.run(jsonRequest(Method.POST, "/targets", targetPayload("Target")))
        targetJson <- bodyJson(targetResponse)
        targetId <- IO.fromEither(targetJson.hcursor.get[String]("id"))
        patchResponse <- routes.run(patchUploadRequest(targetId))
        patchJson <- bodyJson(patchResponse)
        patchId <- IO.fromEither(patchJson.hcursor.get[String]("id"))
        runResponse <- routes.run(jsonRequest(Method.POST, "/runs", Json.obj("target_id" -> Json.fromString(targetId), "patch_id" -> Json.fromString(patchId))))
        runJson <- bodyJson(runResponse)
        runId <- IO.fromEither(runJson.hcursor.get[String]("id"))
        validationResponse <- awaitValidation(routes, runId)
        validationJson <- bodyJson(validationResponse)
      yield (patchResponse.status, runResponse.status, validationResponse.status, validationJson.hcursor.get[String]("status"))
    }.unsafeRunSync()

    val (patchStatus, runStatus, validationStatus, validationState) = result
    assertEquals(patchStatus, Status.Created)
    assertEquals(runStatus, Status.Created)
    assertEquals(validationStatus, Status.Ok)
    assertEquals(validationState, Right("clean"))
  }

  private def routeFixture =
    cats.effect.Resource.make(IO.blocking(Files.createTempDirectory("schema-migrator-routes")))(path =>
      IO.blocking(deleteRecursively(path))
    ).evalMap { stageDir =>
      for
        targetStore <- TargetStore.inMemory
        patchStore <- PatchStore.inMemory(stageDir)
        runStore <- RunStore.inMemory
        validationStore <- ValidationStore.inMemory
      yield Routes
        .all(serverConfig(stageDir), targetStore, patchStore, runStore, validationStore)
        .orNotFound
    }

  private def serverConfig(stageDir: Path): ServerConfig =
    ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = None,
      jwtSecret = "jwt",
      devAuthSecret = "dev",
      patchStageDir = stageDir
    )

  private def targetPayload(label: String): Json =
    Json.obj(
      "label" -> Json.fromString(label),
      "app_name" -> Json.fromString("app"),
      "env" -> Json.fromString("dev"),
      "host" -> Json.fromString("localhost"),
      "port" -> Json.fromInt(5432),
      "dbname" -> Json.fromString("app"),
      "user" -> Json.fromString("app"),
      "password" -> Json.fromString("secret"),
      "schema" -> Json.fromString("public"),
      "ssl_mode" -> Json.fromString("disable")
    )

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
