package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

final case class RunLease(
  runId: String,
  ownerId: String,
  token: String,
  fence: Long,
  attemptCount: Int,
  expiresAt: String
)

final case class RunClaim(run: Run, lease: RunLease)

trait RunStore:
  def list(targetId: Option[String]): IO[List[Run]]
  def create(payload: TriggerRunPayload, patch: Patch, triggeredBy: String): IO[Run]
  def get(id: String): IO[Option[Run]]
  def abort(id: String): IO[Option[Run]]
  def resolveFailed(id: String): IO[Option[Run]]
  def startRun(id: String): IO[Boolean]
  def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]]
  def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]]
  def currentStatus(id: String): IO[Option[String]]
  def scriptStarted(id: String, scriptId: String, filename: String, order: Int, total: Int): IO[Boolean]
  def scriptCompleted(id: String, scriptId: String, filename: String, durationMs: Long): IO[Boolean]
  def scriptFailed(id: String, scriptId: String, filename: String, error: ScriptError, durationMs: Long): IO[Boolean]
  def claim(id: String, ownerId: String, leaseFor: FiniteDuration): IO[Option[RunLease]] =
    IO.pure((id, ownerId, leaseFor)).as(None)
  def claimNext(ownerId: String, leaseFor: FiniteDuration): IO[Option[RunClaim]] =
    IO.pure(ownerId -> leaseFor).as(None)
  def renew(lease: RunLease, leaseFor: FiniteDuration): IO[Boolean] =
    IO.pure(lease -> leaseFor).as(false)
  def ownsLease(lease: RunLease): IO[Boolean] = IO.pure(lease).as(false)
  def release(lease: RunLease): IO[Boolean] = IO.pure(lease).as(false)
  def startRun(lease: RunLease): IO[Boolean] = startRun(lease.runId)
  def completeRun(lease: RunLease, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    completeRun(lease.runId, endedAt, validationTriggered)
  def failRun(lease: RunLease, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]] =
    failRun(lease.runId, endedAt, failedScriptId, reason)
  def scriptStarted(lease: RunLease, scriptId: String, filename: String, order: Int, total: Int): IO[Boolean] =
    scriptStarted(lease.runId, scriptId, filename, order, total)
  def scriptCompleted(lease: RunLease, scriptId: String, filename: String, durationMs: Long): IO[Boolean] =
    scriptCompleted(lease.runId, scriptId, filename, durationMs)
  def scriptFailed(
    lease: RunLease,
    scriptId: String,
    filename: String,
    error: ScriptError,
    durationMs: Long
  ): IO[Boolean] =
    scriptFailed(lease.runId, scriptId, filename, error, durationMs)
  def log(runId: String, level: String, message: String): IO[Unit]
  def runEvents(id: String): Resource[IO, Stream[IO, RunEvent]]

object RunStore:
  final case class ConcurrentRun(targetId: String)
      extends RuntimeException(s"target '$targetId' already has an active run")

  def isTerminalStatus(status: String): Boolean =
    RunState.isTerminal(status)

  def inMemory: IO[RunStore] =
    for
      ref <- Ref.of[IO, Map[String, Run]](Map.empty)
      leases <- Ref.of[IO, Map[String, MemoryLeaseState]](Map.empty)
      topic <- Topic[IO, RunEvent]
    yield InMemoryRunStore(ref, leases, topic)

private[store] final case class MemoryLeaseState(current: Option[RunLease], fence: Long, attempts: Int)

private[store] object RunState:
  val terminalStatuses: Set[String] = Set("completed", "failed", "aborted")
  private val activeScriptStatuses = Set("pending", "running")

  def isTerminal(status: String): Boolean =
    terminalStatuses.contains(status)

  def start(run: Run): Option[Run] =
    Option.when(run.status == "pending")(run.copy(status = "running"))

  def complete(run: Run, endedAt: String): Option[Run] =
    Option.when(run.status == "running")(run.copy(status = "completed", ended_at = Some(endedAt)))

  def fail(run: Run, endedAt: String, failedScriptId: String): Option[Run] =
    Option.when(!isTerminal(run.status)) {
      val scripts = run.scripts.map { script =>
        if script.script_id == failedScriptId && activeScriptStatuses.contains(script.status) then
          script.copy(status = "failed")
        else if script.script_id == failedScriptId then script
        else if activeScriptStatuses.contains(script.status) then script.copy(status = "skipped")
        else script
      }
      run.copy(status = "failed", scripts = scripts, ended_at = Some(endedAt))
    }

  def abort(run: Run, endedAt: String): Option[Run] =
    Option.when(!isTerminal(run.status)) {
      val scripts = run.scripts.map { script =>
        if activeScriptStatuses.contains(script.status) then script.copy(status = "skipped") else script
      }
      run.copy(status = "aborted", scripts = scripts, ended_at = Some(endedAt))
    }

  def resolveFailed(run: Run): Option[Run] =
    Option.when(run.status == "failed")(run.copy(status = "aborted"))

  def updateScript(run: Run, scriptId: String)(f: ScriptRun => ScriptRun): Option[Run] =
    Option.when(!isTerminal(run.status)) {
      run.copy(scripts = run.scripts.map(script => if script.script_id == scriptId then f(script) else script))
    }

  def hasActiveRun(runs: Iterable[Run], targetId: String): Boolean =
    runs.exists(run => run.target_id == targetId && !isTerminal(run.status))

private[store] trait RunStoreEvents:
  protected def topic: Topic[IO, RunEvent]

  protected def publish(runId: String, name: String, payload: Json): IO[Unit] =
    topic.publish1(RunEvent(runId, name, payload)).void

  protected def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)

  protected def durationMs(startedAt: String, endedAt: String): Long =
    Either
      .catchNonFatal(
        java.time.Duration.between(java.time.Instant.parse(startedAt), java.time.Instant.parse(endedAt)).toMillis
      )
      .getOrElse(0L)

  protected def publishRunComplete(run: Run, validationTriggered: Boolean): IO[Unit] =
    publish(
      run.id,
      "run:complete",
      Json.obj(
        "run_id" -> Json.fromString(run.id),
        "duration_ms" -> Json.fromLong(run.ended_at.map(durationMs(run.started_at, _)).getOrElse(0L)),
        "validation_triggered" -> Json.fromBoolean(validationTriggered)
      )
    )

  protected def publishRunFailed(runId: String, failedScriptId: String, reason: String): IO[Unit] =
    publish(
      runId,
      "run:failed",
      Json.obj(
        "run_id" -> Json.fromString(runId),
        "failed_script_id" -> Json.fromString(failedScriptId),
        "reason" -> Json.fromString(reason)
      )
    )

  protected def publishScriptStart(
    runId: String,
    scriptId: String,
    filename: String,
    order: Int,
    total: Int
  ): IO[Unit] =
    publish(
      runId,
      "script:start",
      Json.obj(
        "script_id" -> Json.fromString(scriptId),
        "filename" -> Json.fromString(filename),
        "order" -> Json.fromInt(order),
        "total" -> Json.fromInt(total)
      )
    )

  protected def publishScriptComplete(runId: String, scriptId: String, durationMs: Long): IO[Unit] =
    publish(
      runId,
      "script:complete",
      Json.obj(
        "script_id" -> Json.fromString(scriptId),
        "duration_ms" -> Json.fromLong(durationMs)
      )
    )

  protected def publishScriptError(runId: String, scriptId: String, error: ScriptError): IO[Unit] =
    publish(
      runId,
      "script:error",
      Json.obj(
        "script_id" -> Json.fromString(scriptId),
        "db_code" -> Json.fromString(error.db_code),
        "message" -> Json.fromString(error.message),
        "hint" -> error.hint.fold(Json.Null)(Json.fromString),
        "context" -> error.context.fold(Json.Null)(Json.fromString),
        "line" -> error.line.fold(Json.Null)(Json.fromInt)
      )
    )

  def log(runId: String, level: String, message: String): IO[Unit] =
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

  def runEvents(id: String): Resource[IO, Stream[IO, RunEvent]] =
    topic.subscribeAwait(1024).map(_.filter(_.run_id == id))

private final class InMemoryRunStore(
  ref: Ref[IO, Map[String, Run]],
  leases: Ref[IO, Map[String, MemoryLeaseState]],
  protected val topic: Topic[IO, RunEvent]
)
    extends RunStore
    with RunStoreEvents:
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
      inserted <- ref.modify { values =>
        if RunState.hasActiveRun(values.values, payload.target_id) then values -> false
        else values.updated(id, run) -> true
      }
      result <- if inserted then IO.pure(run) else IO.raiseError(RunStore.ConcurrentRun(payload.target_id))
      _ <- leases.update(_.updated(id, MemoryLeaseState(None, 0L, 0)))
    yield result

  override def get(id: String): IO[Option[Run]] =
    ref.get.map(_.get(id))

  override def abort(id: String): IO[Option[Run]] =
    for
      ended <- nowString
      result <- updateRun(id)(RunState.abort(_, ended))
      _ <- result.traverse_(_ => clearLease(id))
      _ <- result.traverse_(_ => publishRunFailed(id, "", "aborted"))
    yield result

  override def resolveFailed(id: String): IO[Option[Run]] =
    for
      result <- updateRun(id)(RunState.resolveFailed)
      _ <- result.traverse_(_ => publishRunFailed(id, "", "resolved"))
    yield result

  override def startRun(id: String): IO[Boolean] =
    updateRun(id)(RunState.start).map(_.nonEmpty)

  override def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    for
      result <- updateRun(id)(RunState.complete(_, endedAt))
      _ <- result.traverse_(_ => clearLease(id))
      _ <- result.traverse_(run => publishRunComplete(run, validationTriggered))
    yield result

  override def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]] =
    for
      result <- updateRun(id)(RunState.fail(_, endedAt, failedScriptId))
      _ <- result.traverse_(_ => clearLease(id))
      _ <- result.traverse_(_ => publishRunFailed(id, failedScriptId, reason))
    yield result

  override def currentStatus(id: String): IO[Option[String]] =
    ref.get.map(_.get(id).map(_.status))

  override def scriptStarted(id: String, scriptId: String, filename: String, order: Int, total: Int): IO[Boolean] =
    for
      changed <- updateScript(id, scriptId)(_.copy(status = "running"))
      _ <-
        if changed then
          publishScriptStart(id, scriptId, filename, order, total) *> log(id, "info", s"running $filename")
        else IO.unit
    yield changed

  override def scriptCompleted(id: String, scriptId: String, filename: String, durationMs: Long): IO[Boolean] =
    for
      changed <- updateScript(id, scriptId)(_.copy(status = "completed", duration_ms = Some(durationMs)))
      _ <-
        if changed then publishScriptComplete(id, scriptId, durationMs) *> log(id, "info", s"completed $filename")
        else IO.unit
    yield changed

  override def scriptFailed(
    id: String,
    scriptId: String,
    filename: String,
    error: ScriptError,
    durationMs: Long
  ): IO[Boolean] =
    for
      changed <- updateScript(id, scriptId)(
        _.copy(status = "failed", error = Some(error), duration_ms = Some(durationMs))
      )
      _ <-
        if changed then
          publishScriptError(id, scriptId, error) *> log(id, "error", s"failed $filename: ${error.message}")
        else IO.unit
    yield changed

  override def claim(id: String, ownerId: String, leaseFor: FiniteDuration): IO[Option[RunLease]] =
    for
      now <- Clock[IO].realTimeInstant
      token <- IO.delay(UUID.randomUUID().toString)
      active <- ref.get.map(_.get(id).exists(run => !RunState.isTerminal(run.status)))
      result <-
        if !active then IO.pure(None)
        else
          leases.modify { values =>
            val state = values.getOrElse(id, MemoryLeaseState(None, 0L, 0))
            val available = state.current.forall(current => !java.time.Instant.parse(current.expiresAt).isAfter(now))
            if !available || state.attempts >= 5 then values -> None
            else
              val lease = RunLease(
                id,
                ownerId,
                token,
                state.fence + 1L,
                state.attempts + 1,
                now.plusNanos(leaseFor.toNanos).toString
              )
              values.updated(id, MemoryLeaseState(Some(lease), lease.fence, lease.attemptCount)) -> Some(lease)
          }
    yield result

  override def claimNext(ownerId: String, leaseFor: FiniteDuration): IO[Option[RunClaim]] =
    ref.get.flatMap { runs =>
      runs.values.toList
        .filter(run => !RunState.isTerminal(run.status))
        .sortBy(_.started_at)
        .foldM(Option.empty[RunClaim]) { (claimed, run) =>
          claimed.fold(claim(run.id, ownerId, leaseFor).map(_.map(RunClaim(run, _))))(value => IO.pure(Some(value)))
        }
    }

  override def renew(lease: RunLease, leaseFor: FiniteDuration): IO[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      leases.modify { values =>
        values.get(lease.runId) match
          case Some(state) if state.current.exists(current => sameLease(current, lease) && java.time.Instant.parse(current.expiresAt).isAfter(now)) =>
            val renewed = lease.copy(expiresAt = now.plusNanos(leaseFor.toNanos).toString)
            values.updated(lease.runId, state.copy(current = Some(renewed))) -> true
          case _ => values -> false
      }
    }

  override def ownsLease(lease: RunLease): IO[Boolean] =
    Clock[IO].realTimeInstant.flatMap { now =>
      leases.get.map(
        _.get(lease.runId).flatMap(_.current).exists(current =>
          sameLease(current, lease) && java.time.Instant.parse(current.expiresAt).isAfter(now)
        )
      )
    }

  override def release(lease: RunLease): IO[Boolean] =
    leases.modify { values =>
      values.get(lease.runId) match
        case Some(state) if state.current.exists(sameLease(_, lease)) =>
          values.updated(lease.runId, state.copy(current = None)) -> true
        case _ => values -> false
    }

  override def startRun(lease: RunLease): IO[Boolean] =
    withLease(lease) {
      updateRun(lease.runId)(run => if run.status == "running" then Some(run) else RunState.start(run)).map(_.nonEmpty)
    }.map(_.contains(true))

  override def completeRun(lease: RunLease, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    withLease(lease) {
      for
        result <- updateRun(lease.runId)(RunState.complete(_, endedAt))
        _ <- result.traverse_(run => clearLease(lease.runId) *> publishRunComplete(run, validationTriggered))
      yield result
    }.map(_.flatten)

  override def failRun(
    lease: RunLease,
    endedAt: String,
    failedScriptId: String,
    reason: String
  ): IO[Option[Run]] =
    withLease(lease) {
      for
        result <- updateRun(lease.runId)(RunState.fail(_, endedAt, failedScriptId))
        _ <- result.traverse_(_ => clearLease(lease.runId) *> publishRunFailed(lease.runId, failedScriptId, reason))
      yield result
    }.map(_.flatten)

  override def scriptStarted(
    lease: RunLease,
    scriptId: String,
    filename: String,
    order: Int,
    total: Int
  ): IO[Boolean] =
    withLease(lease)(scriptStarted(lease.runId, scriptId, filename, order, total)).map(_.contains(true))

  override def scriptCompleted(
    lease: RunLease,
    scriptId: String,
    filename: String,
    durationMs: Long
  ): IO[Boolean] =
    withLease(lease)(scriptCompleted(lease.runId, scriptId, filename, durationMs)).map(_.contains(true))

  override def scriptFailed(
    lease: RunLease,
    scriptId: String,
    filename: String,
    error: ScriptError,
    durationMs: Long
  ): IO[Boolean] =
    withLease(lease)(scriptFailed(lease.runId, scriptId, filename, error, durationMs)).map(_.contains(true))

  private def withLease[A](lease: RunLease)(operation: IO[A]): IO[Option[A]] =
    ownsLease(lease).flatMap(owned => if owned then operation.map(Some(_)) else IO.pure(None))

  private def sameLease(left: RunLease, right: RunLease): Boolean =
    left.ownerId == right.ownerId && left.token == right.token && left.fence == right.fence

  private def clearLease(id: String): IO[Unit] =
    leases.update(values =>
      values.get(id).fold(values)(state => values.updated(id, state.copy(current = None)))
    )

  private def updateScript(id: String, scriptId: String)(f: ScriptRun => ScriptRun): IO[Boolean] =
    updateRun(id)(RunState.updateScript(_, scriptId)(f)).map(_.nonEmpty)

  private def updateRun(id: String)(f: Run => Option[Run]): IO[Option[Run]] =
    ref.modify { values =>
      values.get(id).flatMap(f) match
        case None => values -> None
        case Some(next) => values.updated(id, next) -> Some(next)
    }
