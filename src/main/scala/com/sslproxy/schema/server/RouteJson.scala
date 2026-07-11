package com.sslproxy.schema.server

import cats.effect.IO
import io.circe.Json
import org.http4s.{EntityDecoder, Request, Response}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

object RouteJson:
  def ok(json: Json): IO[Response[IO]] =
    Ok(json.deepDropNullValues)

  def created(json: Json): IO[Response[IO]] =
    Created(json.deepDropNullValues)

  def badRequest(message: String): IO[Response[IO]] =
    BadRequest(error(message))

  def forbidden(message: String): IO[Response[IO]] =
    Forbidden(error(message))

  def notFound(message: String): IO[Response[IO]] =
    NotFound(error(message))

  def conflict(message: String): IO[Response[IO]] =
    Conflict(error(message))

  def unprocessableEntity(json: Json): IO[Response[IO]] =
    UnprocessableEntity(json.deepDropNullValues)

  def unprocessableEntity(message: String): IO[Response[IO]] =
    UnprocessableEntity(error(message))

  def payloadTooLarge(message: String): IO[Response[IO]] =
    PayloadTooLarge(error(message))

  def gatewayTimeout(message: String): IO[Response[IO]] =
    GatewayTimeout(error(message))

  def internalServerError(message: String): IO[Response[IO]] =
    InternalServerError(error(message))

  def fromOption[A](value: Option[A], missingMessage: String)(present: A => IO[Response[IO]]): IO[Response[IO]] =
    value.fold(notFound(missingMessage))(present)

  def withJson[A](request: Request[IO], invalidMessage: String)(use: A => IO[Response[IO]])(using
    EntityDecoder[IO, A]
  ): IO[Response[IO]] =
    request.as[A].attempt.flatMap {
      case Right(value) => use(value)
      case Left(_) => badRequest(invalidMessage)
    }

  def error(message: String): Json =
    Json.obj("error" -> Json.fromString(message))
