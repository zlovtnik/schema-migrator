package com.sslproxy.schema.server

import cats.effect.IO
import io.circe.Json
import org.http4s.{HttpApp, Request, Response}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object LoggingMiddleware:
  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory[IO].getLogger

  private val excludedPaths: Set[String] = Set("/api/health")

  def apply(app: HttpApp[IO]): HttpApp[IO] =
    HttpApp[IO] { request =>
      for
        _ <- logRequest(request)
        start <- IO.monotonic
        result <- app(request).attempt
        end <- IO.monotonic
        durationMs = (end - start).toMillis
        _ <- result match
          case Right(response) => logResponse(request, response, durationMs)
          case Left(error) => logErrorResponse(request, error, durationMs)
        response <- IO.fromEither(result)
      yield response
    }

  private def logRequest(request: Request[IO]): IO[Unit] =
    val path = request.uri.path.toString
    if excludedPaths.contains(path) then IO.unit
    else
      val fields = List(
        "event" -> Json.fromString("http_request"),
        "method" -> Json.fromString(request.method.name),
        "path" -> Json.fromString(path)
      )
      logger.info(Json.obj(fields*).noSpaces)

  private def logResponse(request: Request[IO], response: Response[IO], durationMs: Long): IO[Unit] =
    val path = request.uri.path.toString
    if excludedPaths.contains(path) then IO.unit
    else
      val fields = List(
        "event" -> Json.fromString("http_response"),
        "method" -> Json.fromString(request.method.name),
        "path" -> Json.fromString(path),
        "status" -> Json.fromInt(response.status.code),
        "duration_ms" -> Json.fromLong(durationMs)
      )
      logger.info(Json.obj(fields*).noSpaces)

  private def logErrorResponse(request: Request[IO], error: Throwable, durationMs: Long): IO[Unit] =
    val path = request.uri.path.toString
    if excludedPaths.contains(path) then IO.unit
    else
      val fields = List(
        "event" -> Json.fromString("http_response"),
        "method" -> Json.fromString(request.method.name),
        "path" -> Json.fromString(path),
        "status" -> Json.fromInt(500),
        "duration_ms" -> Json.fromLong(durationMs),
        "error" -> Json.fromString(error.getClass.getSimpleName)
      )
      logger.error(Json.obj(fields*).noSpaces)
