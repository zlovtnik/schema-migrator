package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.{ErrorCategory, MongoWriteException}
import com.mongodb.client.model.{IndexOptions, Indexes}
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.sslproxy.schema.effect.{Retry, RetryPolicy}
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json
import org.bson.Document

import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

trait RunStore:
  def list(targetId: Option[String]): IO[List[Run]]
  def create(payload: TriggerRunPayload, patch: Patch, triggeredBy: String): IO[Run]
  def get(id: String): IO[Option[Run]]
  def abort(id: String): IO[Option[Run]]
  def startRun(id: String): IO[Boolean]
  def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]]
  def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]]
  def currentStatus(id: String): IO[Option[String]]
  def scriptStarted(id: String, scriptId: String, filename: String, order: Int, total: Int): IO[Boolean]
  def scriptCompleted(id: String, scriptId: String, filename: String, durationMs: Long): IO[Boolean]
  def scriptFailed(id: String, scriptId: String, filename: String, error: ScriptError, durationMs: Long): IO[Boolean]
  def log(runId: String, level: String, message: String): IO[Unit]
  def events: Stream[IO, RunEvent]
  def runEvents(id: String): Resource[IO, Stream[IO, RunEvent]]

object RunStore:
  final case class ConcurrentRun(targetId: String)
      extends RuntimeException(s"target '$targetId' already has an active run")

  def isTerminalStatus(status: String): Boolean =
    RunState.isTerminal(status)

  def mongo(config: com.sslproxy.schema.config.MongoConfig, collectionName: String): Resource[IO, RunStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .flatMap(client => mongo(config, collectionName, client))

  def mongo(
    config: com.sslproxy.schema.config.MongoConfig,
    collectionName: String,
    client: MongoClient
  ): Resource[IO, RunStore] =
    Resource.eval {
      for
        topic <- Topic[IO, RunEvent]
        store = MongoRunStore(client.getDatabase(config.database).getCollection(collectionName), topic)
        _ <- store.initialize
      yield store: RunStore
    }

  def inMemory: IO[RunStore] =
    for
      ref <- Ref.of[IO, Map[String, Run]](Map.empty)
      topic <- Topic[IO, RunEvent]
    yield InMemoryRunStore(ref, topic)

private object RunState:
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

  def updateScript(run: Run, scriptId: String)(f: ScriptRun => ScriptRun): Option[Run] =
    Option.when(!isTerminal(run.status)) {
      run.copy(scripts = run.scripts.map(script => if script.script_id == scriptId then f(script) else script))
    }

  def hasActiveRun(runs: Iterable[Run], targetId: String): Boolean =
    runs.exists(run => run.target_id == targetId && !isTerminal(run.status))

private trait RunStoreEvents:
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

  def events: Stream[IO, RunEvent] =
    topic.subscribe(1024)

  def runEvents(id: String): Resource[IO, Stream[IO, RunEvent]] =
    topic.subscribeAwait(1024).map(_.filter(_.run_id == id))

private final class InMemoryRunStore(ref: Ref[IO, Map[String, Run]], protected val topic: Topic[IO, RunEvent])
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
    yield result

  override def get(id: String): IO[Option[Run]] =
    ref.get.map(_.get(id))

  override def abort(id: String): IO[Option[Run]] =
    for
      ended <- nowString
      result <- updateRun(id)(RunState.abort(_, ended))
      _ <- result.traverse_(_ => publishRunFailed(id, "", "aborted"))
    yield result

  override def startRun(id: String): IO[Boolean] =
    updateRun(id)(RunState.start).map(_.nonEmpty)

  override def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    for
      result <- updateRun(id)(RunState.complete(_, endedAt))
      _ <- result.traverse_(run => publishRunComplete(run, validationTriggered))
    yield result

  override def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]] =
    for
      result <- updateRun(id)(RunState.fail(_, endedAt, failedScriptId))
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

  private def updateScript(id: String, scriptId: String)(f: ScriptRun => ScriptRun): IO[Boolean] =
    updateRun(id)(RunState.updateScript(_, scriptId)(f)).map(_.nonEmpty)

  private def updateRun(id: String)(f: Run => Option[Run]): IO[Option[Run]] =
    ref.modify { values =>
      values.get(id).flatMap(f) match
        case None => values -> None
        case Some(next) => values.updated(id, next) -> Some(next)
    }

private final class MongoRunStore(collection: MongoCollection[Document], protected val topic: Topic[IO, RunEvent])
    extends RunStore
    with RunStoreEvents:
  import MongoRunStore.*

  override def list(targetId: Option[String]): IO[List[Run]] =
    IO.blocking {
      val filter = targetId.fold(new Document())(id => new Document("target_id", id))
      collection
        .find(filter)
        .sort(Indexes.ascending("started_at"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
    }

  override def create(payload: TriggerRunPayload, patch: Patch, triggeredBy: String): IO[Run] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- nowString
      run = Run(
        id = id,
        target_id = payload.target_id,
        patch_id = payload.patch_id,
        status = "pending",
        scripts = patch.scripts.map(script =>
          ScriptRun(
            script_id = script.id,
            filename = script.filename,
            order = script.order,
            status = "pending",
            error = None,
            duration_ms = None
          )
        ),
        started_at = now,
        ended_at = None,
        triggered_by = triggeredBy
      )
      _ <- IO.blocking(collection.insertOne(toDocument(run))).void.handleErrorWith {
        case error: MongoWriteException if error.getError.getCategory == ErrorCategory.DUPLICATE_KEY =>
          IO.raiseError(RunStore.ConcurrentRun(payload.target_id))
        case error => IO.raiseError(error)
      }
    yield run

  override def get(id: String): IO[Option[Run]] =
    IO.blocking(Option(collection.find(idFilter(id)).first()).map(fromDocument))

  override def abort(id: String): IO[Option[Run]] =
    for
      ended <- nowString
      result <- updateRun(id)(RunState.abort(_, ended))
      _ <- result.traverse_(_ => publishRunFailed(id, "", "aborted"))
    yield result

  override def startRun(id: String): IO[Boolean] =
    updateRun(id)(RunState.start).map(_.nonEmpty)

  override def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    for
      result <- updateRun(id)(RunState.complete(_, endedAt))
      _ <- result.traverse_(run => publishRunComplete(run, validationTriggered))
    yield result

  override def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]] =
    for
      result <- updateRun(id)(RunState.fail(_, endedAt, failedScriptId))
      _ <- result.traverse_(_ => publishRunFailed(id, failedScriptId, reason))
    yield result

  override def currentStatus(id: String): IO[Option[String]] =
    get(id).map(_.map(_.status))

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

  private[store] def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("target_id", "started_at"))
      collection.createIndex(Indexes.ascending("status"))
      collection.createIndex(
        Indexes.ascending("target_id"),
        IndexOptions()
          .unique(true)
          .partialFilterExpression(new Document("status", new Document("$in", List("pending", "running").asJava)))
      )
    }.void

  private def updateScript(id: String, scriptId: String)(f: ScriptRun => ScriptRun): IO[Boolean] =
    updateRun(id)(RunState.updateScript(_, scriptId)(f)).map(_.nonEmpty)

  private def updateRun(id: String)(f: Run => Option[Run]): IO[Option[Run]] =
    Retry.withBackoff[IO, Option[Run]](CasRetryPolicy, { case _: CasConflict => true; case _ => false }) {
      updateRunOnce(id)(f)
    }

  private def updateRunOnce(id: String)(f: Run => Option[Run]): IO[Option[Run]] =
    IO.blocking(Option(collection.find(idFilter(id)).first()).map(fromDocument)).flatMap {
      case None => IO.pure(None)
      case Some(run) =>
        f(run) match
          case None => IO.pure(None)
          case Some(next) =>
            IO.blocking(collection.replaceOne(snapshotFilter(run), toDocument(next)))
              .flatMap { result =>
                if result.getMatchedCount > 0 then IO.pure(Some(next))
                else IO.raiseError(CasConflict(id))
              }
    }

  private def toDocument(run: Run): Document =
    new Document()
      .append("_id", run.id)
      .append("target_id", run.target_id)
      .append("patch_id", run.patch_id)
      .append("status", run.status)
      .append("started_at", run.started_at)
      .append("ended_at", run.ended_at.orNull)
      .append("triggered_by", run.triggered_by)
      .append("scripts", run.scripts.sortBy(_.order).map(scriptDocument).asJava)

  private def scriptDocument(script: ScriptRun): Document =
    val document = new Document()
      .append("script_id", script.script_id)
      .append("filename", script.filename)
      .append("order", script.order)
      .append("status", script.status)
      .append("duration_ms", script.duration_ms.map(Long.box).orNull)
    script.error.foreach(error => document.append("error", scriptErrorDocument(error)))
    document

  private def scriptErrorDocument(error: ScriptError): Document =
    new Document()
      .append("db_code", error.db_code)
      .append("message", error.message)
      .append("hint", error.hint.orNull)
      .append("context", error.context.orNull)
      .append("line", error.line.map(Int.box).orNull)

  private def fromDocument(document: Document): Run =
    Run(
      id = requiredString(document, "_id"),
      target_id = requiredString(document, "target_id"),
      patch_id = requiredString(document, "patch_id"),
      status = requiredString(document, "status"),
      scripts = scriptDocuments(document).map(scriptFromDocument).sortBy(_.order),
      started_at = requiredString(document, "started_at"),
      ended_at = optionalString(document, "ended_at"),
      triggered_by = requiredString(document, "triggered_by")
    )

  private def scriptFromDocument(document: Document): ScriptRun =
    ScriptRun(
      script_id = requiredString(document, "script_id"),
      filename = requiredString(document, "filename"),
      order = intValue(document, "order"),
      status = requiredString(document, "status"),
      error = optionalDocument(document, "error").map(scriptErrorFromDocument),
      duration_ms = optionalLong(document, "duration_ms")
    )

  private def scriptErrorFromDocument(document: Document): ScriptError =
    ScriptError(
      db_code = requiredString(document, "db_code"),
      message = requiredString(document, "message"),
      hint = optionalString(document, "hint"),
      context = optionalString(document, "context"),
      line = optionalInt(document, "line")
    )

  private def scriptDocuments(document: Document): List[Document] =
    Option(document.get("scripts")) match
      case Some(values: java.util.List[?]) =>
        values.asScala.toList.collect { case doc: Document => doc }
      case _ => Nil

  private def idFilter(id: String): Document =
    new Document("_id", id)

  private def snapshotFilter(run: Run): Document =
    val snapshot = toDocument(run)
    new Document("_id", run.id)
      .append("status", run.status)
      .append("ended_at", snapshot.get("ended_at"))
      .append("scripts", snapshot.get("scripts"))

  private def requiredString(document: Document, field: String): String =
    optionalString(document, field)
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"run document is missing required field '$field'"))

  private def optionalString(document: Document, field: String): Option[String] =
    Option(document.getString(field)).filter(_.nonEmpty)

  private def optionalDocument(document: Document, field: String): Option[Document] =
    Option(document.get(field)).collect { case doc: Document => doc }

  private def intValue(document: Document, field: String): Int =
    Option(document.get(field)).collect { case number: java.lang.Number => number.intValue() }.getOrElse(0)

  private def optionalInt(document: Document, field: String): Option[Int] =
    Option(document.get(field)).collect { case number: java.lang.Number => number.intValue() }

  private def optionalLong(document: Document, field: String): Option[Long] =
    Option(document.get(field)).collect { case number: java.lang.Number => number.longValue() }

private object MongoRunStore:
  private val CasRetryPolicy = RetryPolicy(maxAttempts = 5, baseDelay = 10.millis)

  private final case class CasConflict(id: String) extends RuntimeException(s"concurrent update for run '$id'")
