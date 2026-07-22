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
  private val vary = CIString("Vary")

  def apply(config: ServerConfig)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes { request =>
      origin(request) match
        case Some(value) if !isSameOrigin(request, value) && !config.corsOrigins.contains(value) =>
          OptionT.liftF(Forbidden(Json.obj("error" -> Json.fromString("CORS origin is not allowed"))))
        case allowed if request.method == Method.OPTIONS =>
          OptionT.liftF(NoContent().map(response => withCorsHeaders(response, allowed)))
        case allowed =>
          routes(request).map(response => withCorsHeaders(response, allowed))
    }

  private def origin(request: Request[IO]): Option[String] =
    request.headers.headers.find(_.name == CIString("Origin")).map(_.value)

  /** Returns true when the Origin header value matches the request's own
    * scheme + host + port, i.e. the request is same-origin and CORS
    * restrictions do not apply.
    */
  private def isSameOrigin(request: Request[IO], originValue: String): Boolean =
    val scheme = request.uri.scheme.map(_.value).getOrElse("http")
    val host = request.uri.host.map(_.value).getOrElse("")
    val port = request.uri.port
    val selfOrigin = port match
      case Some(p) if p != 80 && p != 443 => s"$scheme://$host:$p"
      case _                              => s"$scheme://$host"
    originValue.equalsIgnoreCase(selfOrigin)

  private def withCorsHeaders(response: Response[IO], origin: Option[String]): Response[IO] =
    origin match
      case None => response
      case Some(value) =>
        val headersWithoutVary = response.headers.headers.filterNot(_.name == vary)
        val varyValues = response.headers.headers
          .filter(_.name == vary)
          .flatMap(_.value.split(",").map(_.trim))
          .filter(_.nonEmpty)
        val nextVary = appendHeaderValue(varyValues, "Origin").mkString(", ")

        response
          .withHeaders(Headers(headersWithoutVary))
          .putHeaders(
            Header.Raw(CIString("Access-Control-Allow-Origin"), value),
            Header.Raw(CIString("Access-Control-Allow-Credentials"), "true"),
            Header.Raw(CIString("Access-Control-Allow-Methods"), "GET, POST, PUT, DELETE, OPTIONS"),
            Header.Raw(CIString("Access-Control-Allow-Headers"), "Authorization, Content-Type"),
            Header.Raw(CIString("Access-Control-Expose-Headers"), "X-Bedrock-Encrypted"),
            Header.Raw(CIString("Access-Control-Max-Age"), "3600"),
            Header.Raw(vary, nextVary)
          )

  private def appendHeaderValue(values: List[String], value: String): List[String] =
    if values.exists(_.equalsIgnoreCase(value)) then values else values :+ value
