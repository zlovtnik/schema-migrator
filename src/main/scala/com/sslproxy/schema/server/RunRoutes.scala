package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.store.{
  Models,
  PatchStore,
  Run,
  RunEvent,
  RunStore,
  TargetStore,
  TriggerRunPayload,
  ValidationStore
}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{HttpRoutes, ServerSentEvent}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.concurrent.duration.*

object RunRoutes:
  import Models.given

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val log = LoggerFactory[IO].getLogger

  def routes(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    runExecutor: RunExecutor
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ POST -> Root / "runs" =>
        request.as[TriggerRunPayload].flatMap { payload =>
          (targetStore.getStored(payload.target_id), patchStore.get(payload.patch_id)).tupled.flatMap {
            case (None, _) => RouteJson.notFound(s"target '${payload.target_id}' was not found")
            case (_, None) => RouteJson.notFound(s"patch '${payload.patch_id}' was not found")
            case (Some(_), Some(patch)) if patch.target_id != payload.target_id =>
              RouteJson.badRequest("patch does not belong to target")
            case (Some(target), Some(patch)) =>
              TargetRoutes.withDbAccessAllowed(
                config.server,
                target.target.jdbc_url,
                "database migration execution is not allowed for this target"
              ) {
                (for
                  run <- runStore.create(payload, patch, "api")
                  _ <- log.info(
                    Json
                      .obj(
                        "event" -> Json.fromString("run_triggered"),
                        "run_id" -> Json.fromString(run.id),
                        "target_id" -> Json.fromString(payload.target_id),
                        "patch_id" -> Json.fromString(payload.patch_id)
                      )
                      .noSpaces
                  )
                  _ <- runExecutor.run(target, run, patch).start.void
                  response <- RouteJson.created(run.asJson)
                yield response).recoverWith { case _: RunStore.ConcurrentRun =>
                  RouteJson.conflict(s"target '${payload.target_id}' already has an active run")
                }
              }
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
          case Some(run) =>
            log.info(Json.obj("event" -> Json.fromString("run_aborted"), "run_id" -> Json.fromString(id)).noSpaces) *>
              RouteJson.ok(run.asJson)
          case None =>
            log.info(
              Json.obj("event" -> Json.fromString("run_abort_not_found"), "run_id" -> Json.fromString(id)).noSpaces
            ) *>
              RouteJson.notFound(s"run '$id' was not found")
        }
    }

  private def eventStream(id: String, store: RunStore): Stream[IO, ServerSentEvent] =
    val heartbeat =
      Stream.awakeEvery[IO](15.seconds).map(_ => ServerSentEvent(comment = Some("ping")))
    Stream.resource(store.runEvents(id)).flatMap { events =>
      Stream.eval(store.get(id)).flatMap {
        case Some(run) =>
          terminalEvent(run) match
            case Some(event) => Stream.emit(toServerSentEvent(event))
            case None => events.map(toServerSentEvent).merge(heartbeat)
        case None => events.map(toServerSentEvent).merge(heartbeat)
      }
    }

  private def terminalEvent(run: Run): Option[RunEvent] =
    run.status match
      case "completed" =>
        Some(
          RunEvent(
            run.id,
            "run:complete",
            Json.obj(
              "run_id" -> Json.fromString(run.id),
              "duration_ms" -> Json.fromLong(durationMs(run)),
              "validation_triggered" -> Json.fromBoolean(true)
            )
          )
        )
      case "failed" | "aborted" =>
        val failedScriptId = run.scripts.find(_.status == "failed").map(_.script_id).getOrElse("")
        Some(
          RunEvent(
            run.id,
            "run:failed",
            Json.obj(
              "run_id" -> Json.fromString(run.id),
              "failed_script_id" -> Json.fromString(failedScriptId),
              "reason" -> Json.fromString(run.status)
            )
          )
        )
      case _ => None

  private def toServerSentEvent(event: RunEvent): ServerSentEvent =
    ServerSentEvent(
      data = Some(event.payload.noSpaces),
      eventType = Some(event.name)
    )

  private def durationMs(run: Run): Long =
    run.ended_at
      .flatMap { ended =>
        Either
          .catchNonFatal(
            java.time.Duration.between(java.time.Instant.parse(run.started_at), java.time.Instant.parse(ended)).toMillis
          )
          .toOption
      }
      .getOrElse(0L)
