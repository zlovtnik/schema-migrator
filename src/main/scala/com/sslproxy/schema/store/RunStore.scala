package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Temporal}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json

import java.util.UUID
import scala.concurrent.duration.*

trait RunStore:
  def list(targetId: Option[String]): IO[List[Run]]
  def create(payload: TriggerRunPayload, patch: Patch, triggeredBy: String): IO[Run]
  def get(id: String): IO[Option[Run]]
  def abort(id: String): IO[Option[Run]]
  def runPatch(run: Run, patchStore: PatchStore, validationStore: ValidationStore): IO[Unit]
  def events: Stream[IO, RunEvent]

object RunStore:
  def inMemory: IO[RunStore] =
    for
      ref <- Ref.of[IO, Map[String, Run]](Map.empty)
      topic <- Topic[IO, RunEvent]
    yield InMemoryRunStore(ref, topic)

private final class InMemoryRunStore(ref: Ref[IO, Map[String, Run]], topic: Topic[IO, RunEvent]) extends RunStore:
  private val terminalStatuses = Set("completed", "failed", "aborted")
  private val activeScriptStatuses = Set("pending", "running")

  override def list(targetId: Option[String]): IO[List[Run]] =
    ref.get.map { runs =>
      runs.values.toList
        .filter(run => targetId.forall(_ == run.target_id))
        .sortBy(_.started_at)
    }

  override def create(payload: TriggerRunPayload, patch: Patch, triggeredBy: String): IO[Run] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- nowString
      scripts = patch.scripts.map { script =>
        ScriptRun(
          script_id = script.id,
          filename = script.filename,
          order = script.order,
          status = "pending",
          error = None,
          duration_ms = None
        )
      }
      run = Run(
        id = id,
        target_id = payload.target_id,
        patch_id = payload.patch_id,
        status = "pending",
        scripts = scripts,
        started_at = now,
        ended_at = None,
        triggered_by = triggeredBy
      )
      _ <- ref.update(_ + (id -> run))
    yield run

  override def get(id: String): IO[Option[Run]] =
    ref.get.map(_.get(id))

  override def abort(id: String): IO[Option[Run]] =
    for
      ended <- nowString
      result <- ref.modify { values =>
        values.get(id) match
          case None => values -> Option.empty[(Run, Boolean)]
          case Some(run) if isTerminalStatus(run.status) => values -> Some(run -> false)
          case Some(run) =>
            val scripts = run.scripts.map { script =>
              if activeScriptStatuses.contains(script.status) then script.copy(status = "skipped") else script
            }
            val next = run.copy(status = "aborted", scripts = scripts, ended_at = Some(ended))
            values.updated(id, next) -> Some(next -> true)
      }
      _ <- result.traverse_ { case (run, changed) =>
        if changed then
          publish(
            id,
            "run:failed",
            Json.obj(
              "run_id" -> Json.fromString(run.id),
              "failed_script_id" -> Json.fromString(""),
              "reason" -> Json.fromString("aborted")
            )
          )
        else IO.unit
      }
    yield result.map(_._1)

  override def runPatch(run: Run, patchStore: PatchStore, validationStore: ValidationStore): IO[Unit] =
    val total = run.scripts.length
    val runProgram =
      run.scripts.traverse_ { script =>
        currentStatus(run.id).flatMap {
          case Some(status) if isTerminalStatus(status) => IO.unit
          case _ =>
            for
              _ <- updateScript(run.id, script.script_id)(_.copy(status = "running"))
              _ <- publish(
                run.id,
                "script:start",
                Json.obj(
                  "script_id" -> Json.fromString(script.script_id),
                  "filename" -> Json.fromString(script.filename),
                  "order" -> Json.fromInt(script.order),
                  "total" -> Json.fromInt(total)
                )
              )
              _ <- log(run.id, "info", s"running ${script.filename}")
              started <- Clock[IO].monotonic
              _ <- Temporal[IO].sleep(75.millis)
              elapsed <- Clock[IO].monotonic.map(duration => (duration - started).toMillis.max(1L))
              _ <- currentStatus(run.id).flatMap {
                case Some(status) if isTerminalStatus(status) => IO.unit
                case _ =>
                  for
                    _ <- updateScript(run.id, script.script_id)(
                      _.copy(status = "completed", duration_ms = Some(elapsed))
                    )
                    _ <- publish(
                      run.id,
                      "script:complete",
                      Json.obj(
                        "script_id" -> Json.fromString(script.script_id),
                        "duration_ms" -> Json.fromLong(elapsed)
                      )
                    )
                    _ <- log(run.id, "info", s"completed ${script.filename}")
                  yield ()
              }
            yield ()
        }
      }

    for
      started <- startRunning(run.id)
      _ <- if started then runProgram else IO.unit
      completed <-
        if started then
          nowString.flatMap(ended =>
            completeRunning(run.id, ended).map(_.map { case (finished, changed) => (finished, changed, ended) })
          )
        else currentRun(run.id).map(_.map((_, false, "")))
      _ <- completed match
        case Some((finished, true, ended)) =>
          for
            _ <- patchStore.markApplied(run.patch_id, ended)
            _ <- validationStore.recordClean(run.id, run.target_id)
            _ <- publish(
              run.id,
              "run:complete",
              Json.obj(
                "run_id" -> Json.fromString(run.id),
                "duration_ms" -> Json.fromLong(durationMs(finished.started_at, ended)),
                "validation_triggered" -> Json.fromBoolean(true)
              )
            )
          yield ()
        case Some((finished, false, _)) if finished.status == "failed" =>
          patchStore.markFailed(run.patch_id)
        case _ => IO.unit
    yield ()

  override def events: Stream[IO, RunEvent] =
    topic.subscribe(1024)

  private def startRunning(id: String): IO[Boolean] =
    ref.modify { values =>
      values.get(id) match
        case Some(run) if run.status == "pending" =>
          values.updated(id, run.copy(status = "running")) -> true
        case _ => values -> false
    }

  private def completeRunning(id: String, ended: String): IO[Option[(Run, Boolean)]] =
    ref.modify { values =>
      values.get(id) match
        case None => values -> Option.empty[(Run, Boolean)]
        case Some(run) if run.status == "running" =>
          val next = run.copy(status = "completed", ended_at = Some(ended))
          values.updated(id, next) -> Some(next -> true)
        case Some(run) => values -> Some(run -> false)
    }

  private def currentStatus(id: String): IO[Option[String]] =
    ref.get.map(_.get(id).map(_.status))

  private def currentRun(id: String): IO[Option[Run]] =
    ref.get.map(_.get(id))

  private def isTerminalStatus(status: String): Boolean =
    terminalStatuses.contains(status)

  private def updateRun(id: String)(f: Run => Run): IO[Option[Run]] =
    ref.modify { values =>
      values.get(id) match
        case None => values -> Option.empty[Run]
        case Some(run) =>
          val next = f(run)
          values.updated(id, next) -> Some(next)
    }

  private def updateScript(id: String, scriptId: String)(f: ScriptRun => ScriptRun): IO[Unit] =
    updateRun(id) { run =>
      run.copy(scripts = run.scripts.map(script => if script.script_id == scriptId then f(script) else script))
    }.void

  private def publish(runId: String, name: String, payload: Json): IO[Unit] =
    topic.publish1(RunEvent(runId, name, payload)).void

  private def log(runId: String, level: String, message: String): IO[Unit] =
    nowString.flatMap { now =>
      publish(
        runId,
        "log",
        Json.obj(
          "level" -> Json.fromString(level),
          "message" -> Json.fromString(message),
          "ts" -> Json.fromString(now)
        )
      )
    }

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)

  private def durationMs(startedAt: String, endedAt: String): Long =
    Either
      .catchNonFatal(
        java.time.Duration.between(java.time.Instant.parse(startedAt), java.time.Instant.parse(endedAt)).toMillis
      )
      .getOrElse(0L)
