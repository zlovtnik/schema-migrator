package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, ServerConfig}
import com.sslproxy.schema.server.crypto.AesGcm
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fs2.concurrent.Topic
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.{Base64, UUID}
import javax.crypto.spec.SecretKeySpec

private[store] object PostgresStoreSupport:
  import Models.given

  final case class TargetRow(
    id: UUID,
    label: String,
    appName: String,
    environment: String,
    jdbcUrl: String,
    dbKind: String,
    createdAt: Instant,
    repoUrl: String,
    repoBranch: String,
    repoSqlPath: String,
    lastSyncedCommit: Option[String],
    lastSyncedAt: Option[Instant],
    passwordCiphertext: Option[Array[Byte]],
    passwordIv: Option[Array[Byte]]
  ):
    def target: Target =
      Target(
        id.toString,
        label,
        appName,
        environment,
        jdbcUrl,
        createdAt.toString,
        repoUrl,
        repoBranch,
        repoSqlPath,
        lastSyncedCommit,
        lastSyncedAt.map(_.toString),
        dbKind
      )

  final case class PatchRow(
    id: UUID,
    targetId: UUID,
    version: String,
    label: String,
    status: String,
    appliedAt: Option[Instant],
    sourceSnapshotId: Option[UUID]
  )

  final case class PatchScriptRow(
    id: String,
    patchId: UUID,
    scriptOrder: Int,
    filename: String,
    checksum: String,
    status: String,
    errorJson: Option[String],
    durationMs: Option[Long],
    content: Array[Byte]
  ):
    def script: Script =
      Script(
        id,
        patchId.toString,
        scriptOrder,
        filename,
        checksum,
        status,
        errorJson.flatMap(value => decode[ScriptError](value).toOption),
        durationMs
      )

  final case class RunRow(
    id: UUID,
    targetId: UUID,
    patchId: UUID,
    status: String,
    startedAt: Instant,
    endedAt: Option[Instant],
    triggeredBy: String
  )

  final case class RunScriptRow(
    runId: UUID,
    scriptId: String,
    filename: String,
    scriptOrder: Int,
    status: String,
    errorJson: Option[String],
    durationMs: Option[Long]
  ):
    def script: ScriptRun =
      ScriptRun(
        scriptId,
        filename,
        scriptOrder,
        status,
        errorJson.flatMap(value => decode[ScriptError](value).toOption),
        durationMs
      )

  final case class ValidationRow(runId: UUID, targetId: UUID, checkedAt: Instant, status: String)
  final case class ValidationIssueRow(
    runId: UUID,
    issueOrder: Int,
    objectType: String,
    schemaName: String,
    objectName: String,
    error: String,
    severity: String
  ):
    def issue: InvalidObject = InvalidObject(objectType, schemaName, objectName, error, severity)

  final case class SnapshotRow(
    id: UUID,
    targetId: UUID,
    label: String,
    createdAt: Instant,
    createdBy: String,
    fileCount: Int
  )
  final case class SnapshotFileRow(
    snapshotId: UUID,
    path: String,
    folder: String,
    filename: String,
    sha256: String,
    content: Array[Byte],
    uploadedAt: Instant,
    sizeBytes: Long
  ):
    def file: SnapshotFile =
      SnapshotFile(
        path,
        folder,
        filename,
        sha256,
        Some(Base64.getEncoder.encodeToString(content)),
        uploadedAt.toString,
        sizeBytes
      )

  final case class AuditRow(
    id: UUID,
    actor: String,
    role: String,
    action: String,
    entityType: String,
    entityId: String,
    targetId: Option[UUID],
    at: Instant,
    metadataJson: Option[String]
  ):
    def event: AuditEvent =
      AuditEvent(
        id.toString,
        actor,
        role,
        action,
        entityType,
        entityId,
        targetId.map(_.toString),
        at.toString,
        metadataJson.flatMap(io.circe.parser.parse(_).toOption)
      )

  val targetColumns =
    fr"id, label, app_name, environment, jdbc_url, db_kind, created_at, repo_url, repo_branch, repo_sql_path, last_synced_commit, last_synced_at, password_ciphertext, password_iv"
  val patchColumns = fr"id, target_id, version, label, status, applied_at, source_snapshot_id"
  val patchScriptColumns =
    fr"id, patch_id, script_order, filename, checksum, status, error::text, duration_ms, content"
  val runColumns = fr"id, target_id, patch_id, status, started_at, ended_at, triggered_by"
  val runScriptColumns = fr"run_id, script_id, filename, script_order, status, error::text, duration_ms"

  def uuid(value: String): UUID = UUID.fromString(value)
  def instant(value: String): Instant = Instant.parse(value)
  def errorJson(value: Option[ScriptError]): Option[String] = value.map(_.asJson.noSpaces)

private final case class EncryptedPassword(ciphertext: Array[Byte], iv: Array[Byte])

private final class PasswordCrypto(key: SecretKeySpec):
  def encrypt(password: String): IO[EncryptedPassword] =
    AesGcm.encrypt(key, password.getBytes(StandardCharsets.UTF_8)).map { case (ciphertext, iv) =>
      EncryptedPassword(ciphertext, iv)
    }

  def decrypt(ciphertext: Option[Array[Byte]], iv: Option[Array[Byte]]): IO[Option[String]] =
    (ciphertext, iv) match
      case (Some(value), Some(nonce)) =>
        AesGcm.decrypt(key, value, nonce).map(bytes => Some(String(bytes, StandardCharsets.UTF_8)))
      case (None, None) => IO.pure(None)
      case _ => IO.raiseError(IllegalStateException("target row has incomplete encrypted password fields"))

private[store] final class PostgresTargetStore(database: StateDatabase, passwordKey: SecretKeySpec) extends TargetStore:
  import PostgresStoreSupport.*

  private val crypto = PasswordCrypto(passwordKey)

  override def list: IO[List[Target]] =
    (fr"select" ++ targetColumns ++ fr"from targets order by created_at")
      .query[TargetRow]
      .to[List]
      .map(_.map(_.target))
      .transact(database.transactor)

  override def create(payload: TargetPayload): IO[Target] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      encrypted <- payload.password.filter(_.nonEmpty).traverse(crypto.encrypt)
      target = Target.fromPayload(id.toString, now.toString, payload)
      _ <- sql"""
        insert into targets (
          id, label, app_name, environment, jdbc_url, db_kind, created_at, updated_at,
          repo_url, repo_branch, repo_sql_path, password_ciphertext, password_iv
        ) values (
          $id, ${payload.label}, ${payload.app_name}, ${payload.env}, ${payload.jdbc_url}, ${target.db_kind},
          $now, $now, ${payload.repo_url}, ${payload.repo_branch}, ${payload.repo_sql_path},
          ${encrypted.map(_.ciphertext)}, ${encrypted.map(_.iv)}
        )
      """.update.run.transact(database.transactor)
    yield target

  override def get(id: String): IO[Option[Target]] = row(id).map(_.map(_.target))

  override def getStored(id: String): IO[Option[StoredTarget]] =
    row(id).flatMap(_.traverse(value => crypto.decrypt(value.passwordCiphertext, value.passwordIv).map(StoredTarget(value.target, _))))

  override def update(id: String, payload: TargetPayload): IO[Option[Target]] =
    payload.password.filter(_.nonEmpty).traverse(crypto.encrypt).flatMap { encrypted =>
      val targetId = uuid(id)
      val action = for
        existing <- (fr"select" ++ targetColumns ++ fr"from targets where id = $targetId for update")
          .query[TargetRow]
          .option
        result <- existing match
          case None => none[Target].pure[ConnectionIO]
          case Some(current) =>
            val repoChanged = current.repoUrl != payload.repo_url || current.repoBranch != payload.repo_branch || current.repoSqlPath != payload.repo_sql_path
            val next = Target
              .fromPayload(id, current.createdAt.toString, payload)
              .copy(
                last_synced_commit = Option.unless(repoChanged)(current.lastSyncedCommit).flatten,
                last_synced_at = Option.unless(repoChanged)(current.lastSyncedAt.map(_.toString)).flatten
              )
            val passwordCiphertext = encrypted.map(_.ciphertext).orElse(current.passwordCiphertext)
            val passwordIv = encrypted.map(_.iv).orElse(current.passwordIv)
            sql"""
              update targets set
                label = ${next.label}, app_name = ${next.app_name}, environment = ${next.env},
                jdbc_url = ${next.jdbc_url}, db_kind = ${next.db_kind}, updated_at = now(),
                repo_url = ${next.repo_url}, repo_branch = ${next.repo_branch}, repo_sql_path = ${next.repo_sql_path},
                last_synced_commit = ${next.last_synced_commit},
                last_synced_at = ${next.last_synced_at.map(instant)},
                password_ciphertext = $passwordCiphertext, password_iv = $passwordIv
              where id = $targetId
            """.update.run.as(Some(next))
      yield result
      action.transact(database.transactor)
    }

  override def recordRepoSync(id: String, commitSha: String, syncedAt: String): IO[Boolean] =
    sql"update targets set last_synced_commit = $commitSha, last_synced_at = ${instant(syncedAt)}, updated_at = now() where id = ${uuid(id)}"
      .update.run.map(_ > 0).transact(database.transactor)

  override def clearRepoSync(id: String): IO[Boolean] =
    sql"update targets set last_synced_commit = null, last_synced_at = null, updated_at = now() where id = ${uuid(id)}"
      .update.run.map(_ > 0).transact(database.transactor)

  override def delete(id: String): IO[Boolean] =
    sql"delete from targets where id = ${uuid(id)}".update.run.map(_ > 0).transact(database.transactor)

  private def row(id: String): IO[Option[TargetRow]] =
    (fr"select" ++ targetColumns ++ fr"from targets where id = ${uuid(id)}")
      .query[TargetRow].option.transact(database.transactor)

private[store] final class PostgresSqlFileStore(database: StateDatabase) extends SqlFileStore:
  import PostgresStoreSupport.*

  private type Row = (String, String, String, Array[Byte], String, Instant)

  override def list(targetId: String): IO[List[StoredSqlFile]] =
    sql"""
      select path, folder, filename, content, sha256, uploaded_at
      from sql_files where target_id = ${uuid(targetId)} order by folder, filename
    """.query[Row].to[List].map(_.map(fromRow)).transact(database.transactor)

  override def replaceAll(targetId: String, files: List[StoredSqlFile]): IO[Unit] =
    val id = uuid(targetId)
    val rows = files.map(file =>
      (id, file.path, file.folder, file.filename, Base64.getDecoder.decode(file.contentBase64), file.sha256, instant(file.uploadedAt))
    )
    val insert = Update[(UUID, String, String, String, Array[Byte], String, Instant)](
      "insert into sql_files (target_id, path, folder, filename, content, sha256, uploaded_at) values (?, ?, ?, ?, ?, ?, ?)"
    )
    (sql"delete from sql_files where target_id = $id".update.run *> insert.updateMany(rows)).void
      .transact(database.transactor)

  override def clear(targetId: String): IO[Unit] =
    sql"delete from sql_files where target_id = ${uuid(targetId)}".update.run.void.transact(database.transactor)

  override def isEmpty(targetId: String): IO[Boolean] =
    sql"select not exists(select 1 from sql_files where target_id = ${uuid(targetId)})"
      .query[Boolean].unique.transact(database.transactor)

  private def fromRow(row: Row): StoredSqlFile =
    StoredSqlFile(row._1, row._2, row._3, Base64.getEncoder.encodeToString(row._4), row._5, row._6.toString)

private[store] final class PostgresPatchStore(database: StateDatabase) extends PatchStore:
  import PostgresStoreSupport.*

  override def list(targetId: Option[String]): IO[List[Patch]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${uuid(value)}")
    ((fr"select" ++ patchColumns ++ fr"from patches" ++ filter ++ fr"order by version").query[PatchRow].to[List]
      .flatMap(_.traverse(toPatch))).transact(database.transactor)

  override def create(targetId: String, uploads: List[PatchUpload], sourceSnapshotId: Option[String]): IO[Patch] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      scripts = uploads.sortBy(_.order).map(upload => PatchStore.scriptFromUpload(id.toString, upload))
      patch = Patch(id.toString, targetId, PatchStore.versionFormatter.format(now), scripts.map(_.filename).mkString(", "), scripts, "pending", None, sourceSnapshotId)
      _ <- insertPatch(patch, uploads).transact(database.transactor)
    yield patch

  override def get(id: String): IO[Option[Patch]] =
    load(uuid(id)).transact(database.transactor)

  override def delete(id: String): IO[Boolean] =
    sql"delete from patches where id = ${uuid(id)}".update.run.map(_ > 0).transact(database.transactor)

  override def markApplied(id: String, appliedAt: String): IO[Unit] =
    val patchId = uuid(id)
    (sql"update patches set status = 'applied', applied_at = ${instant(appliedAt)} where id = $patchId".update.run *>
      sql"update patch_scripts set status = 'completed', duration_ms = coalesce(duration_ms, 0) where patch_id = $patchId".update.run).void
      .transact(database.transactor)

  override def markFailed(id: String): IO[Unit] =
    sql"update patches set status = 'failed' where id = ${uuid(id)}".update.run.void.transact(database.transactor)

  override def sqlFiles(patch: Patch, dbKind: DbKind): IO[List[PatchSqlFile]] =
    sql"select id, patch_id, script_order, filename, checksum, status, error::text, duration_ms, content from patch_scripts where patch_id = ${uuid(patch.id)} order by script_order"
      .query[PatchScriptRow].to[List].flatMap { rows =>
        rows.traverse { row =>
          val script = row.script
          val content = String(row.content, StandardCharsets.UTF_8)
          PatchStore.sqlFile(script, Path.of(script.filename), content, dbKind).pure[ConnectionIO]
        }
      }.transact(database.transactor)

  private def insertPatch(patch: Patch, uploads: List[PatchUpload]): ConnectionIO[Unit] =
    val patchId = uuid(patch.id)
    val rows = patch.scripts.zip(uploads.sortBy(_.order)).map { case (script, upload) =>
      (script.id, patchId, script.order, script.filename, script.checksum, script.status, errorJson(script.error), script.duration_ms, upload.bytes)
    }
    val insertScripts = Update[(String, UUID, Int, String, String, String, Option[String], Option[Long], Array[Byte])](
      "insert into patch_scripts (id, patch_id, script_order, filename, checksum, status, error, duration_ms, content) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)"
    )
    sql"""
      insert into patches (id, target_id, version, label, status, applied_at, source_snapshot_id)
      values ($patchId, ${uuid(patch.target_id)}, ${patch.version}, ${patch.label}, ${patch.status},
        ${patch.applied_at.map(instant)}, ${patch.source_snapshot_id.map(uuid)})
    """.update.run *> insertScripts.updateMany(rows).void

  private def load(id: UUID): ConnectionIO[Option[Patch]] =
    (fr"select" ++ patchColumns ++ fr"from patches where id = $id").query[PatchRow].option.flatMap(_.traverse(toPatch))

  private def toPatch(row: PatchRow): ConnectionIO[Patch] =
    (fr"select" ++ patchScriptColumns ++ fr"from patch_scripts where patch_id = ${row.id} order by script_order")
      .query[PatchScriptRow].to[List].map(scripts =>
        Patch(row.id.toString, row.targetId.toString, row.version, row.label, scripts.map(_.script), row.status, row.appliedAt.map(_.toString), row.sourceSnapshotId.map(_.toString))
      )

private[store] final class PostgresRunStore(database: StateDatabase, protected val topic: Topic[IO, RunEvent])
    extends RunStore with RunStoreEvents:
  import PostgresStoreSupport.*

  override def list(targetId: Option[String]): IO[List[Run]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${uuid(value)}")
    ((fr"select" ++ runColumns ++ fr"from runs" ++ filter ++ fr"order by started_at").query[RunRow].to[List]
      .flatMap(_.traverse(toRun))).transact(database.transactor)

  override def create(payload: TriggerRunPayload, patch: Patch, triggeredBy: String): IO[Run] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      run = Run(id.toString, payload.target_id, payload.patch_id, "pending", patch.scripts.map(script => ScriptRun(script.id, script.filename, script.order, "pending", None, None)), now.toString, None, triggeredBy)
      result <- insertRun(run).transact(database.transactor).attempt
      _ <- result match
        case Left(error: java.sql.SQLException) if error.getSQLState == "23505" => IO.raiseError(RunStore.ConcurrentRun(payload.target_id))
        case Left(error) => IO.raiseError(error)
        case Right(_) => IO.unit
    yield run

  override def get(id: String): IO[Option[Run]] = load(uuid(id), lock = false).transact(database.transactor)

  override def abort(id: String): IO[Option[Run]] =
    nowString.flatMap(ended => updateRun(id)(RunState.abort(_, ended))).flatTap(_.traverse_(_ => publishRunFailed(id, "", "aborted")))

  override def resolveFailed(id: String): IO[Option[Run]] =
    updateRun(id)(RunState.resolveFailed).flatTap(_.traverse_(_ => publishRunFailed(id, "", "resolved")))

  override def startRun(id: String): IO[Boolean] = updateRun(id)(RunState.start).map(_.nonEmpty)

  override def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    updateRun(id)(RunState.complete(_, endedAt)).flatTap(_.traverse_(publishRunComplete(_, validationTriggered)))

  override def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]] =
    updateRun(id)(RunState.fail(_, endedAt, failedScriptId)).flatTap(_.traverse_(_ => publishRunFailed(id, failedScriptId, reason)))

  override def currentStatus(id: String): IO[Option[String]] =
    sql"select status from runs where id = ${uuid(id)}".query[String].option.transact(database.transactor)

  override def scriptStarted(id: String, scriptId: String, filename: String, order: Int, total: Int): IO[Boolean] =
    updateScript(id, scriptId)(_.copy(status = "running")).flatTap(changed => if changed then publishScriptStart(id, scriptId, filename, order, total) *> log(id, "info", s"running $filename") else IO.unit)

  override def scriptCompleted(id: String, scriptId: String, filename: String, durationMs: Long): IO[Boolean] =
    updateScript(id, scriptId)(_.copy(status = "completed", duration_ms = Some(durationMs))).flatTap(changed => if changed then publishScriptComplete(id, scriptId, durationMs) *> log(id, "info", s"completed $filename") else IO.unit)

  override def scriptFailed(id: String, scriptId: String, filename: String, error: ScriptError, durationMs: Long): IO[Boolean] =
    updateScript(id, scriptId)(_.copy(status = "failed", error = Some(error), duration_ms = Some(durationMs))).flatTap(changed => if changed then publishScriptError(id, scriptId, error) *> log(id, "error", s"failed $filename: ${error.message}") else IO.unit)

  private def updateScript(id: String, scriptId: String)(f: ScriptRun => ScriptRun): IO[Boolean] =
    updateRun(id)(RunState.updateScript(_, scriptId)(f)).map(_.nonEmpty)

  private def updateRun(id: String)(f: Run => Option[Run]): IO[Option[Run]] =
    val action = load(uuid(id), lock = true).flatMap {
      case None => none[Run].pure[ConnectionIO]
      case Some(run) => f(run).fold(none[Run].pure[ConnectionIO])(next => persistRun(next).as(Some(next)))
    }
    action.transact(database.transactor)

  private def insertRun(run: Run): ConnectionIO[Unit] =
    val runId = uuid(run.id)
    sql"""
      insert into runs (id, target_id, patch_id, status, started_at, ended_at, triggered_by)
      values ($runId, ${uuid(run.target_id)}, ${uuid(run.patch_id)}, ${run.status}, ${instant(run.started_at)}, ${run.ended_at.map(instant)}, ${run.triggered_by})
    """.update.run *> insertScripts(run).void

  private def persistRun(run: Run): ConnectionIO[Unit] =
    val runId = uuid(run.id)
    (sql"update runs set status = ${run.status}, ended_at = ${run.ended_at.map(instant)} where id = $runId".update.run *>
      sql"delete from run_scripts where run_id = $runId".update.run *> insertScripts(run)).void

  private def insertScripts(run: Run): ConnectionIO[Int] =
    val runId = uuid(run.id)
    val rows = run.scripts.map(script => (runId, script.script_id, script.filename, script.order, script.status, errorJson(script.error), script.duration_ms))
    Update[(UUID, String, String, Int, String, Option[String], Option[Long])](
      "insert into run_scripts (run_id, script_id, filename, script_order, status, error, duration_ms) values (?, ?, ?, ?, ?, ?::jsonb, ?)"
    ).updateMany(rows)

  private def load(id: UUID, lock: Boolean): ConnectionIO[Option[Run]] =
    val suffix = if lock then fr"for update" else Fragment.empty
    (fr"select" ++ runColumns ++ fr"from runs where id = $id" ++ suffix).query[RunRow].option.flatMap(_.traverse(toRun))

  private def toRun(row: RunRow): ConnectionIO[Run] =
    (fr"select" ++ runScriptColumns ++ fr"from run_scripts where run_id = ${row.id} order by script_order")
      .query[RunScriptRow].to[List].map(scripts => Run(row.id.toString, row.targetId.toString, row.patchId.toString, row.status, scripts.map(_.script), row.startedAt.toString, row.endedAt.map(_.toString), row.triggeredBy))

private[store] final class PostgresValidationStore(database: StateDatabase) extends ValidationStore:
  import PostgresStoreSupport.*

  override def list(targetId: Option[String]): IO[List[ValidationResult]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${uuid(value)}")
    ((fr"select run_id, target_id, checked_at, status from validations" ++ filter ++ fr"order by checked_at").query[ValidationRow].to[List]
      .flatMap(_.traverse(toResult))).transact(database.transactor)

  override def get(runId: String): IO[Option[ValidationResult]] =
    sql"select run_id, target_id, checked_at, status from validations where run_id = ${uuid(runId)}"
      .query[ValidationRow].option.flatMap(_.traverse(toResult)).transact(database.transactor)

  override def record(runId: String, targetId: String, report: com.sslproxy.schema.validation.ValidationReport): IO[ValidationResult] =
    for
      now <- Clock[IO].realTimeInstant
      result = ValidationStore.resultFromReport(runId, targetId, now.toString, report)
      _ <- persist(result).transact(database.transactor)
    yield result

  private def persist(result: ValidationResult): ConnectionIO[Unit] =
    val id = uuid(result.run_id)
    val rows = result.invalid.zipWithIndex.map { case (issue, index) =>
      (id, index, issue.object_type, issue.schema, issue.name, issue.error, issue.severity)
    }
    val insertIssues = Update[(UUID, Int, String, String, String, String, String)](
      "insert into validation_issues (run_id, issue_order, object_type, schema_name, object_name, error, severity) values (?, ?, ?, ?, ?, ?, ?)"
    )
    (sql"""
      insert into validations (run_id, target_id, checked_at, status)
      values ($id, ${uuid(result.target_id)}, ${instant(result.checked_at)}, ${result.status})
      on conflict (run_id) do update set target_id = excluded.target_id, checked_at = excluded.checked_at, status = excluded.status
    """.update.run *> sql"delete from validation_issues where run_id = $id".update.run *> insertIssues.updateMany(rows)).void

  private def toResult(row: ValidationRow): ConnectionIO[ValidationResult] =
    sql"select run_id, issue_order, object_type, schema_name, object_name, error, severity from validation_issues where run_id = ${row.runId} order by issue_order"
      .query[ValidationIssueRow].to[List].map(issues => ValidationResult(row.runId.toString, row.targetId.toString, row.checkedAt.toString, issues.map(_.issue), row.status))

private[store] final class PostgresSnapshotStore(database: StateDatabase) extends SnapshotStore:
  import PostgresStoreSupport.*

  override def list(targetId: Option[String]): IO[List[Snapshot]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${uuid(value)}")
    ((fr"select id, target_id, label, created_at, created_by, file_count from snapshots" ++ filter ++ fr"order by created_at desc").query[SnapshotRow].to[List]
      .flatMap(_.traverse(toSnapshot))).transact(database.transactor)

  override def create(targetId: String, label: Option[String], files: List[StoredSqlFile], createdBy: String): IO[Snapshot] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      snapshot = SnapshotStore.snapshotFromFiles(id.toString, targetId, label, now.toString, createdBy, files)
      _ <- persist(snapshot).transact(database.transactor)
    yield snapshot

  override def get(id: String): IO[Option[Snapshot]] =
    sql"select id, target_id, label, created_at, created_by, file_count from snapshots where id = ${uuid(id)}"
      .query[SnapshotRow].option.flatMap(_.traverse(toSnapshot)).transact(database.transactor)

  private def persist(snapshot: Snapshot): ConnectionIO[Unit] =
    val id = uuid(snapshot.id)
    val rows = snapshot.files.map(file =>
      (id, file.path, file.folder, file.filename, file.sha256, Base64.getDecoder.decode(file.content_base64.getOrElse("")), instant(file.uploaded_at), file.size_bytes)
    )
    val insertFiles = Update[(UUID, String, String, String, String, Array[Byte], Instant, Long)](
      "insert into snapshot_files (snapshot_id, path, folder, filename, sha256, content, uploaded_at, size_bytes) values (?, ?, ?, ?, ?, ?, ?, ?)"
    )
    sql"insert into snapshots (id, target_id, label, created_at, created_by, file_count) values ($id, ${uuid(snapshot.target_id)}, ${snapshot.label}, ${instant(snapshot.created_at)}, ${snapshot.created_by}, ${snapshot.file_count})"
      .update.run *> insertFiles.updateMany(rows).void

  private def toSnapshot(row: SnapshotRow): ConnectionIO[Snapshot] =
    sql"select snapshot_id, path, folder, filename, sha256, content, uploaded_at, size_bytes from snapshot_files where snapshot_id = ${row.id} order by folder, filename"
      .query[SnapshotFileRow].to[List].map(files => Snapshot(row.id.toString, row.targetId.toString, row.label, row.createdAt.toString, row.createdBy, row.fileCount, files.map(_.file)))

private[store] final class PostgresAuditStore(database: StateDatabase) extends AuditStore:
  import PostgresStoreSupport.*

  override def list(filters: AuditFilters): IO[List[AuditEvent]] =
    val clauses = List(
      filters.actor.map(value => fr"actor = $value"),
      filters.entityType.map(value => fr"entity_type = $value"),
      filters.entityId.map(value => fr"entity_id = $value"),
      filters.targetId.map(value => fr"target_id = ${uuid(value)}")
    ).flatten
    val where = clauses.headOption.fold(Fragment.empty)(head => Fragments.whereAnd(head, clauses.tail*))
    (fr"select id, actor, role, action, entity_type, entity_id, target_id, at, metadata::text from audit_events" ++ where ++ fr"order by at desc limit ${filters.limit}")
      .query[AuditRow].to[List].map(_.map(_.event)).transact(database.transactor)

  override def record(actor: String, role: String, action: String, entityType: String, entityId: String, targetId: Option[String], metadata: Option[Json]): IO[AuditEvent] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      event = AuditEvent(id.toString, actor, role, action, entityType, entityId, targetId, now.toString, metadata)
      _ <- sql"""
        insert into audit_events (id, actor, role, action, entity_type, entity_id, target_id, at, metadata)
        values ($id, $actor, $role, $action, $entityType, $entityId, ${targetId.map(uuid)}, $now, ${metadata.map(_.noSpaces)}::jsonb)
      """.update.run.transact(database.transactor)
    yield event

private[store] object PostgresKeycloakConfigStore:
  def persist(config: ServerConfig, database: StateDatabase): IO[Unit] =
    Clock[IO].realTimeInstant.flatMap { now =>
      sql"""
        insert into keycloak_config (id, enabled, issuer, jwks_uri, client_id, audience, updated_at)
        values ('keycloak', ${config.keycloakEnabled}, ${config.keycloakIssuer}, ${config.keycloakJwksUri},
          ${config.keycloakClientId}, ${config.keycloakAudience}, $now)
        on conflict (id) do update set
          enabled = excluded.enabled, issuer = excluded.issuer, jwks_uri = excluded.jwks_uri,
          client_id = excluded.client_id, audience = excluded.audience, updated_at = excluded.updated_at
      """.update.run.void.transact(database.transactor)
    }

object PostgresStores:
  final case class Bundle(
    targetStore: TargetStore,
    sqlFileStore: SqlFileStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    snapshotStore: SnapshotStore,
    auditStore: AuditStore
  )

  def resource(database: StateDatabase, passwordKey: SecretKeySpec): Resource[IO, Bundle] =
    run(database).map { runStore =>
      Bundle(
        PostgresTargetStore(database, passwordKey),
        PostgresSqlFileStore(database),
        PostgresPatchStore(database),
        runStore,
        PostgresValidationStore(database),
        PostgresSnapshotStore(database),
        PostgresAuditStore(database)
      )
    }

  def run(database: StateDatabase): Resource[IO, RunStore] =
    Resource.eval(Topic[IO, RunEvent].map(topic => PostgresRunStore(database, topic): RunStore))
