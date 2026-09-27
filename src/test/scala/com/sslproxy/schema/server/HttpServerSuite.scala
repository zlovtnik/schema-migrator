package com.sslproxy.schema.server

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.ServerConfig
import com.sslproxy.schema.server.auth.{AuthContext, Claims, JwtMiddleware, UserRole}
import io.circe.Json
import munit.FunSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

import java.nio.file.Paths

class HttpServerSuite extends FunSuite:
  private val config = ServerConfig(
    host = "127.0.0.1",
    port = 8080,
    corsOrigins = Set("https://migrator.example.com"),
    encryptKeyBase64 = Some("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
    jwtSecret = "test-jwt-secret",
    devAuthSecret = "test-dev-secret",
    dbTestAllowedHosts = Set.empty,
    patchStageDir = Paths.get("."),
    keycloakEnabled = true
  )
  private val targets = Json.obj("targets" -> Json.arr(Json.obj("id" -> Json.fromString("target-1"))))
  private val routes = HttpRoutes.of[IO] {
    case GET -> Root / "targets" => Ok(targets)
    case request @ POST -> Root / "targets" =>
      AuthContext.requireRole(request, UserRole.Admin)(_ => Ok(targets))
  }
  private val verifier: JwtMiddleware.TokenVerifier = token =>
    IO.pure(
      if token == "viewer-token" then Right(Claims("viewer", None, UserRole.Viewer))
      else Left("invalid test token")
    )
  private val app = HttpServer.httpApp(config, routes, Some(verifier))

  test("authenticated JSON responses need no browser key even with credential encryption configured") {
    val response = app.run(request(Method.GET, Some("viewer-token"))).unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(response.contentType.map(_.mediaType), Some(MediaType.application.json))
    assertEquals(response.as[Json].unsafeRunSync(), targets)
    assertEquals(header(response, "X-Bedrock-Encrypted"), None)
    assertEquals(header(response, "Access-Control-Allow-Origin"), Some("https://migrator.example.com"))
  }

  test("missing bearer tokens still return a readable JSON 401") {
    val response = app.run(request(Method.GET, None)).unsafeRunSync()

    assertEquals(response.status, Status.Unauthorized)
    assertEquals(response.as[Json].unsafeRunSync(), Json.obj("error" -> Json.fromString("missing bearer token")))
    assertEquals(header(response, "X-Bedrock-Encrypted"), None)
  }

  test("viewer tokens cannot perform admin operations and receive a readable JSON 403") {
    val response = app.run(request(Method.POST, Some("viewer-token"))).unsafeRunSync()

    assertEquals(response.status, Status.Forbidden)
    assertEquals(response.as[Json].unsafeRunSync(), Json.obj("error" -> Json.fromString("admin role required")))
    assertEquals(header(response, "X-Bedrock-Encrypted"), None)
  }

  private def request(method: Method, token: Option[String]): Request[IO] =
    val base = Request[IO](method, Uri.unsafeFromString("/api/targets"))
      .putHeaders(Header.Raw(CIString("Origin"), "https://migrator.example.com"))
    token.fold(base)(value => base.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $value")))

  private def header(response: Response[IO], name: String): Option[String] =
    response.headers.headers.find(_.name == CIString(name)).map(_.value)
