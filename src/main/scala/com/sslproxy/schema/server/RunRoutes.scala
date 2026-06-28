package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.store.{Models, PatchStore, RunEvent, RunStore, TargetStore, TriggerRunPayload, ValidationStore}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{HttpRoutes, ServerSentEvent}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import scala.concurrent.duration.*

object RunRoutes:
  import Models.given

  def routes(
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ POST -> Root / "runs" =>
        request.as[TriggerRunPayload].flatMap { payload =>
          (targetStore.get(payload.target_id), patchStore.get(payload.patch_id)).tupled.flatMap {
            case (None, _) => RouteJson.notFound(s"target '${payload.target_id}' was not found")
            case (_, None) => RouteJson.notFound(s"patch '${payload.patch_id}' was not found")
            case (Some(_), Some(patch)) if patch.target_id != payload.target_id =>
              RouteJson.badRequest("patch does not belong to target")
            case (Some(_), Some(patch)) =>
              for
                run <- runStore.create(payload, patch, "api")
                _ <- runStore.runPatch(run, patchStore, validationStore).start.void
                response <- RouteJson.created(run.asJson)
              yield response
          }
        }

      case request @ GET -> Root / "runs" =>
        val targetId = request.uri.query.params.get("target_id").filter(_.nonEmpty)
        runStore.list(targetId).flatMap(runs => RouteJson.ok(Json.obj("runs" -> runs.asJson)))

      case GET -> Root / "runs" / id / "stream" =>
        runStore.get(id).flatMap {
          case None => RouteJson.notFound(s"run '$id' was not found")
          case Some(_) => Ok(eventStream(id, runStore))
        }

      case GET -> Root / "runs" / id =>
        runStore.get(id).flatMap {
          case Some(run) => RouteJson.ok(run.asJson)
          case None => RouteJson.notFound(s"run '$id' was not found")
        }

      case POST -> Root / "runs" / id / "abort" =>
        runStore.abort(id).flatMap {
          case Some(run) => RouteJson.ok(run.asJson)
          case None => RouteJson.notFound(s"run '$id' was not found")
        }
    }

  private def eventStream(id: String, store: RunStore): Stream[IO, ServerSentEvent] =
    val events =
      store.events
        .filter(_.run_id == id)
        .map(toServerSentEvent)
    val heartbeat =
      Stream.awakeEvery[IO](15.seconds).map(_ => ServerSentEvent(comment = Some("ping")))
    events.merge(heartbeat)

  private def toServerSentEvent(event: RunEvent): ServerSentEvent =
    ServerSentEvent(
      data = Some(event.payload.noSpaces),
      eventType = Some(event.name)
    )
