package com.sslproxy.schema.server

import cats.effect.IO
import com.sslproxy.schema.store.{Models, RunStore, ValidationStore}
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

object ValidationRoutes:
  import Models.given

  def routes(runStore: RunStore, validationStore: ValidationStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "validation" / runId =>
        validationStore.get(runId).flatMap {
          case Some(result) => RouteJson.ok(result.asJson)
          case None => RouteJson.notFound(s"validation for run '$runId' was not found")
        }

      case POST -> Root / "validation" / runId / "rerun" =>
        runStore.get(runId).flatMap {
          case Some(run) => validationStore.rerun(run).flatMap(result => RouteJson.ok(result.asJson))
          case None => RouteJson.notFound(s"run '$runId' was not found")
        }
    }
