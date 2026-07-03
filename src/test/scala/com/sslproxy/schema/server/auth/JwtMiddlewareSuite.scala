package com.sslproxy.schema.server.auth

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.ServerConfig
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString
import munit.FunSuite

import java.nio.file.Paths

class JwtMiddlewareSuite extends FunSuite:
  test("middleware rejects missing tokens and accepts signed bearer token for protected routes") {
    val config = serverConfig()
    val routes = JwtMiddleware(config)(HttpRoutes.of[IO] { case GET -> Root / "protected" => Ok("ok") }).orNotFound
    val token = JwtTokens.create(config.jwtSecret, "tester").map(_._1).unsafeRunSync()

    val missing = routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/protected"))).unsafeRunSync()
    val bearer = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/protected"))
          .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
      )
      .unsafeRunSync()
    val query =
      routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/protected?access_token=$token"))).unsafeRunSync()

    assertEquals(missing.status, Status.Unauthorized)
    assertEquals(bearer.status, Status.Ok)
    assertEquals(query.status, Status.Unauthorized)
  }

  test("middleware accepts configured static bearer token and rejects invalid static tokens") {
    val config = serverConfig(apiBearerToken = Some("static-token"))
    val routes = JwtMiddleware(config)(HttpRoutes.of[IO] { case GET -> Root / "protected" => Ok("ok") }).orNotFound

    val accepted = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/protected"))
          .putHeaders(Header.Raw(CIString("Authorization"), "Bearer static-token"))
      )
      .unsafeRunSync()
    val rejected = routes
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString("/protected"))
          .putHeaders(Header.Raw(CIString("Authorization"), "Bearer wrong-token"))
      )
      .unsafeRunSync()

    assertEquals(accepted.status, Status.Ok)
    assertEquals(rejected.status, Status.Unauthorized)
  }

  test("middleware rejects query token fallback on run stream routes") {
    val config = serverConfig()
    val routes = JwtMiddleware(config)(HttpRoutes.of[IO] { case GET -> Root / "api" / "runs" / _ / "stream" =>
      Ok("ok")
    }).orNotFound
    val token = JwtTokens.create(config.jwtSecret, "tester").map(_._1).unsafeRunSync()

    val stream = routes
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/runs/run-1/stream?access_token=$token")))
      .unsafeRunSync()

    assertEquals(stream.status, Status.Unauthorized)
  }

  private def serverConfig(apiBearerToken: Option[String] = None): ServerConfig =
    ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = None,
      jwtSecret = "jwt-secret",
      devAuthSecret = "dev",
      dbTestAllowedHosts = Set.empty,
      patchStageDir = Paths.get("."),
      apiBearerToken = apiBearerToken
    )
