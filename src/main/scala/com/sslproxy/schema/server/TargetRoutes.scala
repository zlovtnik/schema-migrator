package com.sslproxy.schema.server

import cats.effect.IO
import com.sslproxy.schema.store.{Models, TargetPayload, TargetStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

object TargetRoutes:
  import Models.given

  def routes(store: TargetStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "targets" =>
        store.list.flatMap(targets => RouteJson.ok(Json.obj("targets" -> targets.asJson)))

      case request @ POST -> Root / "targets" =>
        request.as[TargetPayload].flatMap(store.create).flatMap(target => RouteJson.created(target.asJson))

      case request @ POST -> Root / "targets" / "test" =>
        request.as[TargetPayload].flatMap(DbPing.test).flatMap(result => RouteJson.ok(result.asJson))

      case GET -> Root / "targets" / id =>
        store.get(id).flatMap {
          case Some(target) => RouteJson.ok(target.asJson)
          case None => RouteJson.notFound(s"target '$id' was not found")
        }

      case request @ PUT -> Root / "targets" / id =>
        request.as[TargetPayload].flatMap { payload =>
          store.update(id, payload).flatMap {
            case Some(target) => RouteJson.ok(target.asJson)
            case None => RouteJson.notFound(s"target '$id' was not found")
          }
        }

      case DELETE -> Root / "targets" / id =>
        store.delete(id).flatMap {
          case true => NoContent()
          case false => RouteJson.notFound(s"target '$id' was not found")
        }

      case POST -> Root / "targets" / id / "test" =>
        store.getStored(id).flatMap {
          case Some(target) => DbPing.test(target).flatMap(result => RouteJson.ok(result.asJson))
          case None => RouteJson.notFound(s"target '$id' was not found")
        }
    }
