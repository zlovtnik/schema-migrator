package com.sslproxy.schema.server

import cats.data.OptionT
import cats.effect.IO
import com.sslproxy.schema.config.ServerConfig
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

object CorsMiddleware:
  def apply(config: ServerConfig)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes { request =>
      origin(request) match
        case Some(value) if !config.corsOrigins.contains(value) =>
          OptionT.liftF(Forbidden(Json.obj("error" -> Json.fromString("CORS origin is not allowed"))))
        case allowed if request.method == Method.OPTIONS =>
          OptionT.liftF(NoContent().map(response => withCorsHeaders(response, allowed)))
        case allowed =>
          routes(request).map(response => withCorsHeaders(response, allowed))
    }

  private def origin(request: Request[IO]): Option[String] =
    request.headers.headers.find(_.name == CIString("Origin")).map(_.value)

  private def withCorsHeaders(response: Response[IO], origin: Option[String]): Response[IO] =
    origin match
      case None => response
      case Some(value) =>
        response.putHeaders(
          Header.Raw(CIString("Access-Control-Allow-Origin"), value),
          Header.Raw(CIString("Access-Control-Allow-Credentials"), "true"),
          Header.Raw(CIString("Access-Control-Allow-Methods"), "GET, POST, PUT, DELETE, OPTIONS"),
          Header.Raw(CIString("Access-Control-Allow-Headers"), "Authorization, Content-Type"),
          Header.Raw(CIString("Access-Control-Expose-Headers"), "X-Bedrock-Encrypted"),
          Header.Raw(CIString("Access-Control-Max-Age"), "3600"),
          Header.Raw(CIString("Vary"), "Origin")
        )
