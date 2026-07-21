package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, ServerConfig}
import com.sslproxy.schema.server.crypto.AesGcm
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import fs2.concurrent.Topic
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.{Base64, UUID}
import javax.crypto.spec.SecretKeySpec

private[store] object TiDBStoreSupport:
  import Models.given

  final case class TargetRow(
    id: String,
    label: String,
    appName: String,
    environment: String,
    jdbcUrl: String,
    dbKind: String,
    createdAt: LocalDateTime,
    repoUrl: String,
    repoBranch: String,
    repoSqlPath: String,
    lastSyncedCommit: Option[String],
    lastSyncedAt: Option[LocalDateTime],
    passwordCiphertext: Option[Array[Byte]],
    passwordIv: Option[Array[Byte]]
  ):
    def target: Target =
      Target(
        id,
        label,
        appName,
        environment,
        jdbcUrl,
        apiTime(createdAt),
        repoUrl,
        repoBranch,
        repoSqlPath,
        lastSyncedCommit,
        lastSyncedAt.map(apiTime),
        dbKind
      )

  final case class PatchRow(
    id: String,
    targetId: String,
    version: String,
    label: String,
    status: String,
    appliedAt: Option[LocalDateTime],
    sourceSnapshotId: Option[String]
  )

  final case class PatchScriptRow(
    id: String,
    patchId: String,
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
        patchId,
        scriptOrder,
        filename,
        checksum,
        status,
        errorJson.flatMap(value => decode[ScriptError](value).toOption),
        durationMs
      )

  final case class RunRow(
    id: String,
    targetId: String,
    patchId: String,
    status: String,
    startedAt: LocalDateTime,
    endedAt: Option[LocalDateTime],
    triggeredBy: String,
    ownerId: Option[String],
    leaseToken: Option[String],
    leaseFence: Long,
    leaseExpiresAt: Option[LocalDateTime],
    attemptCount: Int,
    maxAttempts: Int,
    nextAttemptAt: LocalDateTime,
    leaseValid: Boolean
  )

  final case class RunScriptRow(
    runId: String,
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

  final case class ValidationRow(runId: String, targetId: String, checkedAt: LocalDateTime, status: String)
  final case class ValidationIssueRow(
    runId: String,
    issueOrder: Int,
    objectType: String,
    schemaName: String,
    objectName: String,
    error: String,
    severity: String
  ):
    def issue: InvalidObject = InvalidObject(objectType, schemaName, objectName, error, severity)

  final case class SnapshotRow(
    id: String,
    targetId: String,
    label: String,
    createdAt: LocalDateTime,
    createdBy: String,
    fileCount: Int
  )
  final case class SnapshotFileRow(
    snapshotId: String,
    path: String,
    folder: String,
    filename: String,
    sha256: String,
    content: Array[Byte],
    uploadedAt: LocalDateTime,
    sizeBytes: Long
  ):
    def file: SnapshotFile =
      SnapshotFile(
        path,
        folder,
        filename,
        sha256,
        Some(Base64.getEncoder.encodeToString(content)),
        apiTime(uploadedAt),
        sizeBytes
      )

  final case class AuditRow(
    id: String,
    actor: String,
    role: String,
    action: String,
    entityType: String,
    entityId: String,
    targetId: Option[String],
    at: LocalDateTime,
    metadataJson: Option[String]
  ):
    def event: AuditEvent =
      AuditEvent(
        id,
        actor,
        role,
        action,
        entityType,
        entityId,
        targetId,
        apiTime(at),
        metadataJson.flatMap(io.circe.parser.parse(_).toOption)
      )

  val targetColumns =
    fr"id, label, app_name, environment, jdbc_url, db_kind, created_at, repo_url, repo_branch, repo_sql_path, last_synced_commit, last_synced_at, password_ciphertext, password_iv"
  val patchColumns = fr"id, target_id, version, label, status, applied_at, source_snapshot_id"
  val patchScriptColumns =
    fr"id, patch_id, script_order, filename, checksum, status, error, duration_ms, content"
  val runColumns =
    fr"id, target_id, patch_id, status, started_at, ended_at, triggered_by, owner_id, lease_token, lease_fence, lease_expires_at, attempt_count, max_attempts, next_attempt_at, (lease_expires_at > utc_timestamp(6))"
  val runScriptColumns = fr"run_id, script_id, filename, script_order, status, error, duration_ms"

  def id(value: String): String = UUID.fromString(value).toString
  def idOption(value: String): Option[String] = Either.catchNonFatal(id(value)).toOption
  def dbTime(value: String): LocalDateTime = Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDateTime
  def dbTime(value: Instant): LocalDateTime = value.atOffset(ZoneOffset.UTC).toLocalDateTime
  def apiTime(value: LocalDateTime): String = value.toInstant(ZoneOffset.UTC).toString
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

private[store] final class TiDBTargetStore(database: StateDatabase, passwordKey: SecretKeySpec) extends TargetStore:
  import TiDBStoreSupport.*

  private val crypto = PasswordCrypto(passwordKey)

  override def list: IO[List[Target]] =
    database.transact(
      (fr"select" ++ targetColumns ++ fr"from targets order by created_at")
        .query[TargetRow]
        .to[List]
        .map(_.map(_.target))
    )

  override def create(payload: TargetPayload): IO[Target] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- Clock[IO].realTimeInstant
      encrypted <- payload.password.filter(_.nonEmpty).traverse(crypto.encrypt)
      target = Target.fromPayload(id, now.toString, payload)
      _ <- database.transact(sql"""
        insert into targets (
          id, label, app_name, environment, jdbc_url, db_kind, created_at, updated_at,
          repo_url, repo_branch, repo_sql_path, password_ciphertext, password_iv
        ) values (
          ${id}, ${payload.label}, ${payload.app_name}, ${payload.env}, ${payload.jdbc_url}, ${target.db_kind},
          ${dbTime(now)}, ${dbTime(now)}, ${payload.repo_url}, ${payload.repo_branch}, ${payload.repo_sql_path},
          ${encrypted.map(_.ciphertext)}, ${encrypted.map(_.iv)}
        )
      """.update.run)
    yield target

  override def get(id: String): IO[Option[Target]] = row(id).map(_.map(_.target))

  override def getStored(id: String): IO[Option[StoredTarget]] =
    row(id).flatMap(_.traverse(value => crypto.decrypt(value.passwordCiphertext, value.passwordIv).map(StoredTarget(value.target, _))))

  override def update(id: String, payload: TargetPayload): IO[Option[Target]] =
    idOption(id).fold(IO.pure(Option.empty[Target])) { targetId =>
      payload.password.filter(_.nonEmpty).traverse(crypto.encrypt).flatMap { encrypted =>
      val action = for
        existing <- (fr"select" ++ targetColumns ++ fr"from targets where id = $targetId for update")
          .query[TargetRow]
          .option
        result <- existing match
          case None => none[Target].pure[ConnectionIO]
          case Some(current) =>
            val repoChanged = current.repoUrl != payload.repo_url || current.repoBranch != payload.repo_branch || current.repoSqlPath != payload.repo_sql_path
            val next = Target
              .fromPayload(id, apiTime(current.createdAt), payload)
              .copy(
                last_synced_commit = Option.unless(repoChanged)(current.lastSyncedCommit).flatten,
                last_synced_at = Option.unless(repoChanged)(current.lastSyncedAt.map(apiTime)).flatten
              )
            val passwordCiphertext = encrypted.map(_.ciphertext).orElse(current.passwordCiphertext)
            val passwordIv = encrypted.map(_.iv).orElse(current.passwordIv)
            sql"""
              update targets set
                label = ${next.label}, app_name = ${next.app_name}, environment = ${next.env},
                jdbc_url = ${next.jdbc_url}, db_kind = ${next.db_kind}, updated_at = utc_timestamp(6),
                repo_url = ${next.repo_url}, repo_branch = ${next.repo_branch}, repo_sql_path = ${next.repo_sql_path},
                last_synced_commit = ${next.last_synced_commit},
                last_synced_at = ${next.last_synced_at.map(dbTime)},
                password_ciphertext = $passwordCiphertext, password_iv = $passwordIv
              where id = $targetId
            """.update.run.as(Some(next))
      yield result
      database.transact(action)
      }
    }

  override def recordRepoSync(id: String, commitSha: String, syncedAt: String): IO[Boolean] =
    idOption(id).fold(IO.pure(false)) { targetId =>
      database.transact(
        sql"update targets set last_synced_commit = $commitSha, last_synced_at = ${dbTime(syncedAt)}, updated_at = utc_timestamp(6) where id = $targetId"
          .update.run.map(_ > 0)
      )
    }

  override def clearRepoSync(id: String): IO[Boolean] =
    idOption(id).fold(IO.pure(false)) { targetId =>
      database.transact(
        sql"update targets set last_synced_commit = null, last_synced_at = null, updated_at = utc_timestamp(6) where id = $targetId"
          .update.run.map(_ > 0)
      )
    }

  override def delete(id: String): IO[Boolean] =
    idOption(id).fold(IO.pure(false))(targetId =>
      database.transact(sql"delete from targets where id = $targetId".update.run.map(_ > 0))
    )

  private def row(id: String): IO[Option[TargetRow]] =
    idOption(id).fold(IO.pure(Option.empty[TargetRow])) { targetId =>
      database.transact((fr"select" ++ targetColumns ++ fr"from targets where id = $targetId").query[TargetRow].option)
    }

private[store] final class TiDBSqlFileStore(database: StateDatabase) extends SqlFileStore:
  import TiDBStoreSupport.*

  private type Row = (String, String, String, Array[Byte], String, LocalDateTime)

  override def list(targetId: String): IO[List[StoredSqlFile]] =
    database.transact(
      sql"""
        select path, folder, filename, content, sha256, uploaded_at
        from sql_files where target_id = ${id(targetId)} order by folder, filename
      """.query[Row].to[List].map(_.map(fromRow))
    )

  override def replaceAll(targetId: String, files: List[StoredSqlFile]): IO[Unit] =
    val targetUuid = id(targetId)
    val rows = files.map(file =>
      (targetUuid, file.path, file.folder, file.filename, Base64.getDecoder.decode(file.contentBase64), file.sha256, dbTime(file.uploadedAt))
    )
    val insert = Update[(String, String, String, String, Array[Byte], String, LocalDateTime)](
      "insert into sql_files (target_id, path, folder, filename, content, sha256, uploaded_at) values (?, ?, ?, ?, ?, ?, ?)"
    )
    database.transact((sql"delete from sql_files where target_id = $targetUuid".update.run *> insert.updateMany(rows)).void)

  override def clear(targetId: String): IO[Unit] =
    database.transact(sql"delete from sql_files where target_id = ${id(targetId)}".update.run.void)

  override def isEmpty(targetId: String): IO[Boolean] =
    database.transact(sql"select not exists(select 1 from sql_files where target_id = ${id(targetId)})".query[Boolean].unique)

  private def fromRow(row: Row): StoredSqlFile =
    StoredSqlFile(row._1, row._2, row._3, Base64.getEncoder.encodeToString(row._4), row._5, apiTime(row._6))

private[store] final class TiDBPatchStore(database: StateDatabase) extends PatchStore:
  import TiDBStoreSupport.*

  override def list(targetId: Option[String]): IO[List[Patch]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${id(value)}")
    database.transact(
      (fr"select" ++ patchColumns ++ fr"from patches" ++ filter ++ fr"order by version").query[PatchRow].to[List]
        .flatMap(_.traverse(toPatch))
    )

  override def create(targetId: String, uploads: List[PatchUpload], sourceSnapshotId: Option[String]): IO[Patch] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      scripts = uploads.sortBy(_.order).map(upload => PatchStore.scriptFromUpload(id.toString, upload))
      patch = Patch(id.toString, targetId, PatchStore.versionFormatter.format(now), scripts.map(_.filename).mkString(", "), scripts, "pending", None, sourceSnapshotId)
      _ <- database.transact(insertPatch(patch, uploads))
    yield patch

  override def get(id: String): IO[Option[Patch]] =
    database.transact(load(TiDBStoreSupport.id(id)))

  override def delete(id: String): IO[Boolean] =
    database.transact(sql"delete from patches where id = ${TiDBStoreSupport.id(id)}".update.run.map(_ > 0))

  override def markApplied(id: String, appliedAt: String): IO[Unit] =
    val patchId = TiDBStoreSupport.id(id)
    database.transact((sql"update patches set status = 'applied', applied_at = ${dbTime(appliedAt)} where id = $patchId".update.run *>
      sql"update patch_scripts set status = 'completed', duration_ms = coalesce(duration_ms, 0) where patch_id = $patchId".update.run).void
    )

  override def markFailed(id: String): IO[Unit] =
    database.transact(sql"update patches set status = 'failed' where id = ${TiDBStoreSupport.id(id)}".update.run.void)

  override def sqlFiles(patch: Patch, dbKind: DbKind): IO[List[PatchSqlFile]] =
    database.transact(sql"select id, patch_id, script_order, filename, checksum, status, error, duration_ms, content from patch_scripts where patch_id = ${TiDBStoreSupport.id(patch.id)} order by script_order"
      .query[PatchScriptRow].to[List].flatMap { rows =>
        rows.traverse { row =>
          val script = row.script
          val content = String(row.content, StandardCharsets.UTF_8)
          PatchStore.sqlFile(script, Path.of(script.filename), content, dbKind).pure[ConnectionIO]
        }
      })

  private def insertPatch(patch: Patch, uploads: List[PatchUpload]): ConnectionIO[Unit] =
    val patchId = TiDBStoreSupport.id(patch.id)
    val rows = patch.scripts.zip(uploads.sortBy(_.order)).map { case (script, upload) =>
      (script.id, patchId, script.order, script.filename, script.checksum, script.status, errorJson(script.error), script.duration_ms, upload.bytes)
    }
    val insertScripts = Update[(String, String, Int, String, String, String, Option[String], Option[Long], Array[Byte])](
      "insert into patch_scripts (id, patch_id, script_order, filename, checksum, status, error, duration_ms, content) values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    )
    sql"""
      insert into patches (id, target_id, version, label, status, applied_at, source_snapshot_id)
      values ($patchId, ${id(patch.target_id)}, ${patch.version}, ${patch.label}, ${patch.status},
        ${patch.applied_at.map(dbTime)}, ${patch.source_snapshot_id.map(id)})
    """.update.run *> insertScripts.updateMany(rows).void

  private def load(patchId: String): ConnectionIO[Option[Patch]] =
    (fr"select" ++ patchColumns ++ fr"from patches where id = $patchId").query[PatchRow].option.flatMap(_.traverse(toPatch))

  private def toPatch(row: PatchRow): ConnectionIO[Patch] =
    (fr"select" ++ patchScriptColumns ++ fr"from patch_scripts where patch_id = ${row.id} order by script_order")
      .query[PatchScriptRow].to[List].map(scripts =>
        Patch(row.id, row.targetId, row.version, row.label, scripts.map(_.script), row.status, row.appliedAt.map(apiTime), row.sourceSnapshotId)
      )

private[store] final class TiDBRunStore(database: StateDatabase, protected val topic: Topic[IO, RunEvent])
    extends RunStore with RunStoreEvents:
  import TiDBStoreSupport.*

  override def list(targetId: Option[String]): IO[List[Run]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${id(value)}")
    database.transact(
      (fr"select" ++ runColumns ++ fr"from runs" ++ filter ++ fr"order by started_at").query[RunRow].to[List]
        .flatMap(_.traverse(toRun))
    )

  override def create(payload: TriggerRunPayload, patch: Patch, triggeredBy: String): IO[Run] =
    for
      runId <- IO.delay(UUID.randomUUID().toString)
      now <- Clock[IO].realTimeInstant
      run = Run(runId, payload.target_id, payload.patch_id, "pending", patch.scripts.map(script => ScriptRun(script.id, script.filename, script.order, "pending", None, None)), now.toString, None, triggeredBy)
      result <- database.transact(insertRun(run)).attempt
      _ <- result match
        case Left(error) if isDuplicate(error) => IO.raiseError(RunStore.ConcurrentRun(payload.target_id))
        case Left(error) => IO.raiseError(error)
        case Right(_) => IO.unit
    yield run

  override def get(id: String): IO[Option[Run]] =
    idOption(id).fold(IO.pure(Option.empty[Run]))(runId => database.transact(load(runId, lock = false).map(_.map(_._2))))

  override def abort(id: String): IO[Option[Run]] =
    nowString
      .flatMap(ended => updateRun(id, None, releaseOnSuccess = true)(RunState.abort(_, ended)))
      .flatTap(_.traverse_(_ => publishRunFailed(id, "", "aborted")))

  override def resolveFailed(id: String): IO[Option[Run]] =
    updateRun(id, None, releaseOnSuccess = true)(RunState.resolveFailed)
      .flatTap(_.traverse_(_ => publishRunFailed(id, "", "resolved")))

  override def startRun(id: String): IO[Boolean] =
    updateRun(id, None, releaseOnSuccess = false)(RunState.start).map(_.nonEmpty)

  override def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    updateRun(id, None, releaseOnSuccess = true)(RunState.complete(_, endedAt))
      .flatTap(_.traverse_(publishRunComplete(_, validationTriggered)))

  override def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]] =
    updateRun(id, None, releaseOnSuccess = true)(RunState.fail(_, endedAt, failedScriptId))
      .flatTap(_.traverse_(_ => publishRunFailed(id, failedScriptId, reason)))

  override def currentStatus(id: String): IO[Option[String]] =
    idOption(id).fold(IO.pure(Option.empty[String]))(runId =>
      database.transact(sql"select status from runs where id = $runId".query[String].option)
    )

  override def scriptStarted(id: String, scriptId: String, filename: String, order: Int, total: Int): IO[Boolean] =
    updateScript(id, scriptId, None)(_.copy(status = "running")).flatTap(changed =>
      if changed then publishScriptStart(id, scriptId, filename, order, total) *> log(id, "info", s"running $filename")
      else IO.unit
    )

  override def scriptCompleted(id: String, scriptId: String, filename: String, durationMs: Long): IO[Boolean] =
    updateScript(id, scriptId, None)(_.copy(status = "completed", duration_ms = Some(durationMs))).flatTap(changed =>
      if changed then publishScriptComplete(id, scriptId, durationMs) *> log(id, "info", s"completed $filename")
      else IO.unit
    )

  override def scriptFailed(id: String, scriptId: String, filename: String, error: ScriptError, durationMs: Long): IO[Boolean] =
    updateScript(id, scriptId, None)(_.copy(status = "failed", error = Some(error), duration_ms = Some(durationMs))).flatTap(changed =>
      if changed then publishScriptError(id, scriptId, error) *> log(id, "error", s"failed $filename: ${error.message}")
      else IO.unit
    )

  override def claim(id: String, ownerId: String, leaseFor: scala.concurrent.duration.FiniteDuration): IO[Option[RunLease]] =
    idOption(id).fold(IO.pure(Option.empty[RunLease])) { runId =>
      IO.delay(UUID.randomUUID().toString).flatMap { token =>
        database.transact(claimAction(runId, ownerId, token, leaseFor.toMicros))
      }
    }

  override def claimNext(
    ownerId: String,
    leaseFor: scala.concurrent.duration.FiniteDuration
  ): IO[Option[RunClaim]] =
    database
      .transact(
        expireExhausted *>
          sql"""
            select id from runs
            where status in ('pending', 'running')
              and attempt_count < max_attempts
              and next_attempt_at <= utc_timestamp(6)
              and (lease_token is null or lease_expires_at <= utc_timestamp(6))
            order by case when status = 'running' then 0 else 1 end, started_at
            limit 16
          """.query[String].to[List]
      )
      .flatMap(
        _.foldM(Option.empty[RunClaim]) { (claimed, runId) =>
          claimed.fold(
            claim(runId, ownerId, leaseFor).flatMap {
              case None => IO.pure(None)
              case Some(lease) => get(runId).map(_.map(RunClaim(_, lease)))
            }
          )(value => IO.pure(Some(value)))
        }
      )

  override def renew(lease: RunLease, leaseFor: scala.concurrent.duration.FiniteDuration): IO[Boolean] =
    database.transact(renewAction(lease, leaseFor.toMicros))

  override def ownsLease(lease: RunLease): IO[Boolean] =
    database.transact(leaseOwned(lease))

  override def release(lease: RunLease): IO[Boolean] =
    database.transact(releaseLease(lease))

  override def startRun(lease: RunLease): IO[Boolean] =
    database.transact {
      sql"""
        update runs set status = 'running', updated_at = utc_timestamp(6)
        where id = ${lease.runId} and status = 'pending'
          and owner_id = ${lease.ownerId} and lease_token = ${lease.token} and lease_fence = ${lease.fence}
          and lease_expires_at > utc_timestamp(6)
      """.update.run.flatMap {
        case 1 => true.pure[ConnectionIO]
        case _ =>
          leaseOwned(lease).flatMap(owned =>
            if !owned then false.pure[ConnectionIO]
            else sql"select status = 'running' from runs where id = ${lease.runId}".query[Boolean].option.map(_.contains(true))
          )
      }
    }

  override def completeRun(lease: RunLease, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
    updateRun(lease.runId, Some(lease), releaseOnSuccess = true)(RunState.complete(_, endedAt))
      .flatTap(_.traverse_(publishRunComplete(_, validationTriggered)))

  override def failRun(
    lease: RunLease,
    endedAt: String,
    failedScriptId: String,
    reason: String
  ): IO[Option[Run]] =
    updateRun(lease.runId, Some(lease), releaseOnSuccess = true)(RunState.fail(_, endedAt, failedScriptId))
      .flatTap(_.traverse_(_ => publishRunFailed(lease.runId, failedScriptId, reason)))

  override def scriptStarted(
    lease: RunLease,
    scriptId: String,
    filename: String,
    order: Int,
    total: Int
  ): IO[Boolean] =
    updateScript(lease.runId, scriptId, Some(lease))(_.copy(status = "running")).flatTap(changed =>
      if changed then publishScriptStart(lease.runId, scriptId, filename, order, total) *> log(lease.runId, "info", s"running $filename")
      else IO.unit
    )

  override def scriptCompleted(
    lease: RunLease,
    scriptId: String,
    filename: String,
    durationMs: Long
  ): IO[Boolean] =
    updateScript(lease.runId, scriptId, Some(lease))(_.copy(status = "completed", duration_ms = Some(durationMs)))
      .flatTap(changed =>
        if changed then publishScriptComplete(lease.runId, scriptId, durationMs) *> log(lease.runId, "info", s"completed $filename")
        else IO.unit
      )

  override def scriptFailed(
    lease: RunLease,
    scriptId: String,
    filename: String,
    error: ScriptError,
    durationMs: Long
  ): IO[Boolean] =
    updateScript(lease.runId, scriptId, Some(lease))(
      _.copy(status = "failed", error = Some(error), duration_ms = Some(durationMs))
    ).flatTap(changed =>
      if changed then publishScriptError(lease.runId, scriptId, error) *> log(lease.runId, "error", s"failed $filename: ${error.message}")
      else IO.unit
    )

  private def updateScript(
    id: String,
    scriptId: String,
    lease: Option[RunLease]
  )(f: ScriptRun => ScriptRun): IO[Boolean] =
    updateRun(id, lease, releaseOnSuccess = false)(RunState.updateScript(_, scriptId)(f)).map(_.nonEmpty)

  private def updateRun(
    id: String,
    lease: Option[RunLease],
    releaseOnSuccess: Boolean
  )(f: Run => Option[Run]): IO[Option[Run]] =
    idOption(id).fold(IO.pure(Option.empty[Run])) { runId =>
      val action = load(runId, lock = true).flatMap {
        case None => none[Run].pure[ConnectionIO]
        case Some((row, _)) if lease.exists(value => !matchesLease(row, value)) => none[Run].pure[ConnectionIO]
        case Some((_, run)) =>
          f(run).fold(none[Run].pure[ConnectionIO]) { next =>
            persistRun(next) *> Option.when(releaseOnSuccess)(clearLease(runId)).sequence_.as(Some(next))
          }
      }
      database.transact(action)
    }

  private def insertRun(run: Run): ConnectionIO[Unit] =
    val runId = id(run.id)
    sql"""
      insert into runs (
        id, target_id, patch_id, status, started_at, ended_at, triggered_by,
        lease_fence, attempt_count, max_attempts, next_attempt_at, updated_at
      ) values (
        $runId, ${id(run.target_id)}, ${id(run.patch_id)}, ${run.status}, ${dbTime(run.started_at)},
        ${run.ended_at.map(dbTime)}, ${run.triggered_by}, 0, 0, 3, utc_timestamp(6), utc_timestamp(6)
      )
    """.update.run *> insertScripts(run).void

  private def persistRun(run: Run): ConnectionIO[Unit] =
    val runId = id(run.id)
    (sql"update runs set status = ${run.status}, ended_at = ${run.ended_at.map(dbTime)}, updated_at = utc_timestamp(6) where id = $runId".update.run *>
      sql"delete from run_scripts where run_id = $runId".update.run *> insertScripts(run)).void

  private def insertScripts(run: Run): ConnectionIO[Int] =
    val runId = id(run.id)
    val rows = run.scripts.map(script => (runId, script.script_id, script.filename, script.order, script.status, errorJson(script.error), script.duration_ms))
    Update[(String, String, String, Int, String, Option[String], Option[Long])](
      "insert into run_scripts (run_id, script_id, filename, script_order, status, error, duration_ms) values (?, ?, ?, ?, ?, ?, ?)"
    ).updateMany(rows)

  private def load(runId: String, lock: Boolean): ConnectionIO[Option[(RunRow, Run)]] =
    val suffix = if lock then fr"for update" else Fragment.empty
    (fr"select" ++ runColumns ++ fr"from runs where id = $runId" ++ suffix).query[RunRow].option.flatMap(
      _.traverse(row => toRun(row).map(row -> _))
    )

  private def toRun(row: RunRow): ConnectionIO[Run] =
    (fr"select" ++ runScriptColumns ++ fr"from run_scripts where run_id = ${row.id} order by script_order")
      .query[RunScriptRow].to[List].map(scripts =>
        Run(row.id, row.targetId, row.patchId, row.status, scripts.map(_.script), apiTime(row.startedAt), row.endedAt.map(apiTime), row.triggeredBy)
      )

  private def claimAction(runId: String, ownerId: String, token: String, leaseMicros: Long): ConnectionIO[Option[RunLease]] =
    sql"""
      update runs set
        owner_id = $ownerId,
        lease_token = $token,
        lease_fence = lease_fence + 1,
        lease_expires_at = timestampadd(microsecond, $leaseMicros, utc_timestamp(6)),
        attempt_count = attempt_count + 1,
        last_error = null,
        updated_at = utc_timestamp(6)
      where id = $runId
        and status in ('pending', 'running')
        and attempt_count < max_attempts
        and next_attempt_at <= utc_timestamp(6)
        and (lease_token is null or lease_expires_at <= utc_timestamp(6))
    """.update.run.flatMap {
      case 0 => none[RunLease].pure[ConnectionIO]
      case 1 =>
        sql"select owner_id, lease_token, lease_fence, attempt_count, lease_expires_at from runs where id = $runId"
          .query[(String, String, Long, Int, LocalDateTime)]
          .unique
          .flatMap { case (owner, currentToken, fence, attempts, expiresAt) =>
            val lease = RunLease(runId, owner, currentToken, fence, attempts, apiTime(expiresAt))
            upsertControlLease(lease).as(Some(lease))
          }
      case count => IllegalStateException(s"claim for run $runId updated $count rows").raiseError
    }

  private def expireExhausted: ConnectionIO[Unit] =
    sql"""
      select id from runs
      where status in ('pending', 'running')
        and attempt_count >= max_attempts
        and (lease_token is null or lease_expires_at <= utc_timestamp(6))
      limit 64
    """.query[String].to[List].flatMap(_.traverse_ { runId =>
      sql"""
        update runs set
          status = 'failed', ended_at = utc_timestamp(6), last_error = 'run lease attempts exhausted',
          owner_id = null, lease_token = null, lease_expires_at = null, updated_at = utc_timestamp(6)
        where id = $runId and status in ('pending', 'running')
          and attempt_count >= max_attempts
          and (lease_token is null or lease_expires_at <= utc_timestamp(6))
      """.update.run.flatMap {
        case 0 => ().pure[ConnectionIO]
        case 1 =>
          sql"update run_scripts set status = 'skipped' where run_id = $runId and status in ('pending', 'running')"
            .update.run *> clearControlLease(runId, None)
        case count => IllegalStateException(s"exhausted run cleanup updated $count rows for $runId").raiseError
      }
    })

  private def renewAction(lease: RunLease, leaseMicros: Long): ConnectionIO[Boolean] =
    sql"""
      update runs set
        lease_expires_at = timestampadd(microsecond, $leaseMicros, utc_timestamp(6)),
        updated_at = utc_timestamp(6)
      where id = ${lease.runId}
        and owner_id = ${lease.ownerId}
        and lease_token = ${lease.token}
        and lease_fence = ${lease.fence}
        and status in ('pending', 'running')
        and lease_expires_at > utc_timestamp(6)
    """.update.run.flatMap {
      case 0 => false.pure[ConnectionIO]
      case 1 =>
        sql"""
          update control_leases set
            lease_expires_at = timestampadd(microsecond, $leaseMicros, utc_timestamp(6)),
            updated_at = utc_timestamp(6)
          where resource_type = 'run' and resource_id = ${lease.runId}
            and owner_id = ${lease.ownerId} and lease_token = ${lease.token} and fence = ${lease.fence}
        """.update.run.flatMap {
          case 1 => true.pure[ConnectionIO]
          case count => IllegalStateException(s"run lease renewal updated $count control rows").raiseError
        }
      case count => IllegalStateException(s"run lease renewal updated $count run rows").raiseError
    }

  private def leaseOwned(lease: RunLease): ConnectionIO[Boolean] =
    sql"""
      select exists(
        select 1 from runs
        where id = ${lease.runId}
          and owner_id = ${lease.ownerId}
          and lease_token = ${lease.token}
          and lease_fence = ${lease.fence}
          and status in ('pending', 'running')
          and lease_expires_at > utc_timestamp(6)
      )
    """.query[Boolean].unique

  private def releaseLease(lease: RunLease): ConnectionIO[Boolean] =
    sql"""
      update runs set owner_id = null, lease_token = null, lease_expires_at = null, updated_at = utc_timestamp(6)
      where id = ${lease.runId} and owner_id = ${lease.ownerId}
        and lease_token = ${lease.token} and lease_fence = ${lease.fence}
    """.update.run.flatMap {
      case 0 => false.pure[ConnectionIO]
      case 1 => clearControlLease(lease.runId, Some(lease)).as(true)
      case count => IllegalStateException(s"run lease release updated $count rows").raiseError
    }

  private def clearLease(runId: String): ConnectionIO[Unit] =
    sql"update runs set owner_id = null, lease_token = null, lease_expires_at = null, updated_at = utc_timestamp(6) where id = $runId"
      .update.run *> clearControlLease(runId, None)

  private def clearControlLease(runId: String, lease: Option[RunLease]): ConnectionIO[Unit] =
    val predicate = lease.fold(Fragment.empty)(value =>
      fr"and owner_id = ${value.ownerId} and lease_token = ${value.token} and fence = ${value.fence}"
    )
    (fr"update control_leases set owner_id = null, lease_token = null, lease_expires_at = null, updated_at = utc_timestamp(6) where resource_type = 'run' and resource_id = $runId" ++ predicate)
      .update.run.void

  private def upsertControlLease(lease: RunLease): ConnectionIO[Unit] =
    sql"""
      insert into control_leases (
        resource_type, resource_id, owner_id, lease_token, fence, attempt_count,
        lease_expires_at, next_attempt_at, last_error, updated_at
      ) values (
        'run', ${lease.runId}, ${lease.ownerId}, ${lease.token}, ${lease.fence}, ${lease.attemptCount},
        ${dbTime(lease.expiresAt)}, utc_timestamp(6), null, utc_timestamp(6)
      ) on duplicate key update
        owner_id = values(owner_id), lease_token = values(lease_token), fence = values(fence),
        attempt_count = values(attempt_count), lease_expires_at = values(lease_expires_at),
        next_attempt_at = values(next_attempt_at), last_error = values(last_error), updated_at = values(updated_at)
    """.update.run.void

  private def matchesLease(row: RunRow, lease: RunLease): Boolean =
    row.ownerId.contains(lease.ownerId) && row.leaseToken.contains(lease.token) &&
      row.leaseFence == lease.fence && row.leaseValid

  private def isDuplicate(error: Throwable): Boolean =
    error match
      case sql: java.sql.SQLException => sql.getErrorCode == 1062 || Option(sql.getSQLState).exists(_.startsWith("23"))
      case other => Option(other.getCause).exists(isDuplicate)

private[store] final class TiDBValidationStore(database: StateDatabase) extends ValidationStore:
  import TiDBStoreSupport.*

  override def list(targetId: Option[String]): IO[List[ValidationResult]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${id(value)}")
    database.transact(
      (fr"select run_id, target_id, checked_at, status from validations" ++ filter ++ fr"order by checked_at")
        .query[ValidationRow].to[List].flatMap(_.traverse(toResult))
    )

  override def get(runId: String): IO[Option[ValidationResult]] =
    database.transact(
      sql"select run_id, target_id, checked_at, status from validations where run_id = ${id(runId)}"
        .query[ValidationRow].option.flatMap(_.traverse(toResult))
    )

  override def record(runId: String, targetId: String, report: com.sslproxy.schema.validation.ValidationReport): IO[ValidationResult] =
    for
      now <- Clock[IO].realTimeInstant
      result = ValidationStore.resultFromReport(runId, targetId, now.toString, report)
      _ <- database.transact(persist(result))
    yield result

  private def persist(result: ValidationResult): ConnectionIO[Unit] =
    val runId = id(result.run_id)
    val rows = result.invalid.zipWithIndex.map { case (issue, index) =>
      (runId, index, issue.object_type, issue.schema, issue.name, issue.error, issue.severity)
    }
    val insertIssues = Update[(String, Int, String, String, String, String, String)](
      "insert into validation_issues (run_id, issue_order, object_type, schema_name, object_name, error, severity) values (?, ?, ?, ?, ?, ?, ?)"
    )
    (sql"""
      insert into validations (run_id, target_id, checked_at, status)
      values ($runId, ${id(result.target_id)}, ${dbTime(result.checked_at)}, ${result.status})
      on duplicate key update target_id = values(target_id), checked_at = values(checked_at), status = values(status)
    """.update.run *> sql"delete from validation_issues where run_id = $runId".update.run *> insertIssues.updateMany(rows)).void

  private def toResult(row: ValidationRow): ConnectionIO[ValidationResult] =
    sql"select run_id, issue_order, object_type, schema_name, object_name, error, severity from validation_issues where run_id = ${row.runId} order by issue_order"
      .query[ValidationIssueRow].to[List].map(issues =>
        ValidationResult(row.runId, row.targetId, apiTime(row.checkedAt), issues.map(_.issue), row.status)
      )

private[store] final class TiDBSnapshotStore(database: StateDatabase) extends SnapshotStore:
  import TiDBStoreSupport.*

  override def list(targetId: Option[String]): IO[List[Snapshot]] =
    val filter = targetId.fold(Fragment.empty)(value => fr"where target_id = ${id(value)}")
    database.transact(
      (fr"select id, target_id, label, created_at, created_by, file_count from snapshots" ++ filter ++ fr"order by created_at desc")
        .query[SnapshotRow].to[List].flatMap(_.traverse(toSnapshot))
    )

  override def create(targetId: String, label: Option[String], files: List[StoredSqlFile], createdBy: String): IO[Snapshot] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      snapshot = SnapshotStore.snapshotFromFiles(id.toString, targetId, label, now.toString, createdBy, files)
      _ <- database.transact(persist(snapshot))
    yield snapshot

  override def get(id: String): IO[Option[Snapshot]] =
    database.transact(
      sql"select id, target_id, label, created_at, created_by, file_count from snapshots where id = ${TiDBStoreSupport.id(id)}"
        .query[SnapshotRow].option.flatMap(_.traverse(toSnapshot))
    )

  private def persist(snapshot: Snapshot): ConnectionIO[Unit] =
    val snapshotId = id(snapshot.id)
    val rows = snapshot.files.map(file =>
      (snapshotId, file.path, file.folder, file.filename, file.sha256, Base64.getDecoder.decode(file.content_base64.getOrElse("")), dbTime(file.uploaded_at), file.size_bytes)
    )
    val insertFiles = Update[(String, String, String, String, String, Array[Byte], LocalDateTime, Long)](
      "insert into snapshot_files (snapshot_id, path, folder, filename, sha256, content, uploaded_at, size_bytes) values (?, ?, ?, ?, ?, ?, ?, ?)"
    )
    sql"insert into snapshots (id, target_id, label, created_at, created_by, file_count) values ($snapshotId, ${id(snapshot.target_id)}, ${snapshot.label}, ${dbTime(snapshot.created_at)}, ${snapshot.created_by}, ${snapshot.file_count})"
      .update.run *> insertFiles.updateMany(rows).void

  private def toSnapshot(row: SnapshotRow): ConnectionIO[Snapshot] =
    sql"select snapshot_id, path, folder, filename, sha256, content, uploaded_at, size_bytes from snapshot_files where snapshot_id = ${row.id} order by folder, filename"
      .query[SnapshotFileRow].to[List].map(files =>
        Snapshot(row.id, row.targetId, row.label, apiTime(row.createdAt), row.createdBy, row.fileCount, files.map(_.file))
      )

private[store] final class TiDBAuditStore(database: StateDatabase) extends AuditStore:
  import TiDBStoreSupport.*

  override def list(filters: AuditFilters): IO[List[AuditEvent]] =
    val clauses = List(
      filters.actor.map(value => fr"actor = $value"),
      filters.entityType.map(value => fr"entity_type = $value"),
      filters.entityId.map(value => fr"entity_id = $value"),
      filters.targetId.map(value => fr"target_id = ${id(value)}")
    ).flatten
    val where = clauses.headOption.fold(Fragment.empty)(head => Fragments.whereAnd(head, clauses.tail*))
    database.transact(
      (fr"select id, actor, role, action, entity_type, entity_id, target_id, at, metadata from audit_events" ++ where ++ fr"order by at desc limit ${filters.limit}")
        .query[AuditRow].to[List].map(_.map(_.event))
    )

  override def record(actor: String, role: String, action: String, entityType: String, entityId: String, targetId: Option[String], metadata: Option[Json]): IO[AuditEvent] =
    for
      id <- IO(UUID.randomUUID())
      now <- Clock[IO].realTimeInstant
      event = AuditEvent(id.toString, actor, role, action, entityType, entityId, targetId, now.toString, metadata)
      _ <- database.transact(sql"""
        insert into audit_events (id, actor, role, action, entity_type, entity_id, target_id, at, metadata)
        values (${id.toString}, $actor, $role, $action, $entityType, $entityId, ${targetId.map(TiDBStoreSupport.id)}, ${dbTime(now)}, ${metadata.map(_.noSpaces)})
      """.update.run)
    yield event

private[store] object TiDBKeycloakConfigStore:
  import TiDBStoreSupport.*

  private val ConfigId = "00000000-0000-0000-0000-000000000001"

  def persist(config: ServerConfig, database: StateDatabase): IO[Unit] =
    Clock[IO].realTimeInstant.flatMap { now =>
      database.transact(sql"""
        insert into keycloak_config (id, enabled, issuer, jwks_uri, client_id, audience, updated_at)
        values ($ConfigId, ${config.keycloakEnabled}, ${config.keycloakIssuer}, ${config.keycloakJwksUri},
          ${config.keycloakClientId}, ${config.keycloakAudience}, ${dbTime(now)})
        on duplicate key update
          enabled = values(enabled), issuer = values(issuer), jwks_uri = values(jwks_uri),
          client_id = values(client_id), audience = values(audience), updated_at = values(updated_at)
      """.update.run.void)
    }

object TiDBStores:
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
        TiDBTargetStore(database, passwordKey),
        TiDBSqlFileStore(database),
        TiDBPatchStore(database),
        runStore,
        TiDBValidationStore(database),
        TiDBSnapshotStore(database),
        TiDBAuditStore(database)
      )
    }

  def run(database: StateDatabase): Resource[IO, RunStore] =
    Resource.eval(Topic[IO, RunEvent].map(topic => TiDBRunStore(database, topic): RunStore))
