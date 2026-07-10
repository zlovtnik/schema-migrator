package com.sslproxy.schema.server

import cats.effect.IO
import io.circe.Json
import org.http4s.Response
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

  def error(message: String): Json =
    Json.obj("error" -> Json.fromString(message))
