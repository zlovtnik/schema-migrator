package com.sslproxy.schema.server

import cats.effect.IO
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

object HealthRoute:
  val routes: HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "health" =>
      RouteJson.ok(Json.obj("ok" -> Json.fromBoolean(true)))
    }
