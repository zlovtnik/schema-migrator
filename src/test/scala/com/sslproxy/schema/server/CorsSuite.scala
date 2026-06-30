package com.sslproxy.schema.server

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.ServerConfig
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString
import munit.FunSuite

import java.nio.file.Paths

class CorsSuite extends FunSuite:
  test("preflight returns configured browser auth CORS headers") {
    val config = ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = None,
      jwtSecret = "jwt",
      devAuthSecret = "dev",
      dbTestAllowedHosts = Set.empty,
      patchStageDir = Paths.get(".")
    )
    val routes = HttpRoutes.of[IO] { case GET -> Root / "health" => Ok("ok") }
    val request =
      Request[IO](Method.OPTIONS, Uri.unsafeFromString("/api/targets"))
        .putHeaders(
          Header.Raw(CIString("Origin"), "http://localhost:5173"),
          Header.Raw(CIString("Access-Control-Request-Method"), "GET"),
          Header.Raw(CIString("Access-Control-Request-Headers"), "authorization")
        )

    val response = CorsMiddleware(config)(routes).orNotFound.run(request).unsafeRunSync()

    assertEquals(response.status, Status.NoContent)
    assertEquals(
      response.headers.headers.find(_.name == CIString("Access-Control-Allow-Origin")).map(_.value),
      Some("http://localhost:5173")
    )
    assertEquals(
      response.headers.headers.find(_.name == CIString("Access-Control-Allow-Methods")).map(_.value),
      Some("GET, POST, PUT, DELETE, OPTIONS")
    )
    assertEquals(
      response.headers.headers.find(_.name == CIString("Access-Control-Allow-Headers")).map(_.value),
      Some("Authorization, Content-Type")
    )
    assertEquals(
      response.headers.headers.find(_.name == CIString("Access-Control-Expose-Headers")).map(_.value),
      Some("X-Bedrock-Encrypted")
    )
  }

  test("CORS preserves existing Vary values when adding Origin") {
    val config = ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = None,
      jwtSecret = "jwt",
      devAuthSecret = "dev",
      dbTestAllowedHosts = Set.empty,
      patchStageDir = Paths.get(".")
    )
    val routes = HttpRoutes.of[IO] { case GET -> Root / "payload" =>
      Ok("ok").map(_.putHeaders(Header.Raw(CIString("Vary"), "Accept-Encoding")))
    }
    val request =
      Request[IO](Method.GET, Uri.unsafeFromString("/payload"))
        .putHeaders(Header.Raw(CIString("Origin"), "http://localhost:5173"))

    val response = CorsMiddleware(config)(routes).orNotFound.run(request).unsafeRunSync()

    assertEquals(
      response.headers.headers.find(_.name == CIString("Vary")).map(_.value),
      Some("Accept-Encoding, Origin")
    )
  }
