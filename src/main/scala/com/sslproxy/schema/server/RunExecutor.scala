package com.sslproxy.schema.server

import cats.effect.{Clock, Deferred, IO, Ref, Temporal}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.engine.{ApplyCallbacks, MigrationEngine, PreparedObject}
import com.sslproxy.schema.effect.MigrationRunContext
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.server.auth.UserRole
import com.sslproxy.schema.store.{
  Patch,
  PatchSqlFile,
  PatchStore,
  Run,
  RunClaim,
  RunLease,
  RunStore,
  Script,
  ScriptError,
  StoredTarget,
  ValidationStore,
  AuditStore,
  TargetStore
}
import com.sslproxy.schema.validation.ValidationReport

import java.sql.SQLException
import java.util.UUID
import scala.concurrent.duration.*

trait RunExecutor:
  def run(target: StoredTarget, run: Run, patch: Patch): IO[Unit]
  def runClaimed(target: StoredTarget, runRecord: Run, patch: Patch, lease: RunLease): IO[Unit] =
    IO.pure(lease).void *> run(target, runRecord, patch)
  def submit(target: StoredTarget, runRecord: Run, patch: Patch): IO[Unit] =
    run(target, runRecord, patch)
  def recoverPending(targetStore: TargetStore, patchStore: PatchStore): IO[Unit] =
    IO.pure(targetStore -> patchStore).void

object RunExecutor:
  private val LeaseDuration = 30.seconds
  private val LeaseRenewInterval = 10.seconds

  final case class LeaseLost(runId: String)
      extends RuntimeException(s"run '$runId' lost its TiDB control lease")

  def supervised(delegate: RunExecutor, supervisor: Supervisor[IO], runStore: RunStore): RunExecutor =
    val ownerId = s"schema-migrator-${UUID.randomUUID()}"
    new RunExecutor:
      override def run(target: StoredTarget, run: Run, patch: Patch): IO[Unit] =
        delegate.run(target, run, patch)

      override def runClaimed(target: StoredTarget, run: Run, patch: Patch, lease: RunLease): IO[Unit] =
        delegate.runClaimed(target, run, patch, lease)

      override def submit(target: StoredTarget, run: Run, patch: Patch): IO[Unit] =
        runStore.claim(run.id, ownerId, LeaseDuration).flatMap(_.traverse_(lease => launch(target, run, patch, lease)))

      override def recoverPending(targetStore: TargetStore, patchStore: PatchStore): IO[Unit] =
        runStore.claimNext(ownerId, LeaseDuration).flatMap {
          case None => IO.unit
          case Some(RunClaim(run, lease)) =>
            (targetStore.getStored(run.target_id), patchStore.get(run.patch_id)).tupled.flatMap {
              case (Some(target), Some(patch)) if patch.target_id == run.target_id => launch(target, run, patch, lease)
              case _ =>
                Clock[IO].realTimeInstant.flatMap(now =>
                  runStore.failRun(lease, now.toString, "", "recovery could not load the persisted target and patch").void
                )
            }
        }

      private def launch(target: StoredTarget, run: Run, patch: Patch, lease: RunLease): IO[Unit] =
        supervisor.supervise(withRenewal(target, run, patch, lease)).void

      private def withRenewal(target: StoredTarget, run: Run, patch: Patch, lease: RunLease): IO[Unit] =
        Deferred[IO, Unit].flatMap { lost =>
          def renewLoop: IO[Unit] =
            IO.sleep(LeaseRenewInterval) *> runStore.renew(lease, LeaseDuration).attempt.flatMap {
              case Right(true) => renewLoop
              case Right(false) | Left(_) => lost.complete(()).void
            }

          for
            renewal <- renewLoop.start
            outcome <- IO.race(delegate.runClaimed(target, run, patch, lease), lost.get)
              .guarantee(renewal.cancel)
            _ <- outcome match
              case Left(_) => IO.unit
              case Right(_) => IO.raiseError(LeaseLost(run.id))
          yield ()
        }.guarantee(runStore.release(lease).void)

  def real(
    config: MigratorConfig,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    auditStore: Option[AuditStore] = None
  ): RunExecutor =
    RealRunExecutor(config, patchStore, runStore, validationStore, auditStore)

  def simulated(
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    delay: FiniteDuration = 75.millis,
    auditStore: Option[AuditStore] = None
  ): RunExecutor =
    SimulatedRunExecutor(patchStore, runStore, validationStore, delay, auditStore)

private final class RealRunExecutor(
  config: MigratorConfig,
  patchStore: PatchStore,
  runStore: RunStore,
  validationStore: ValidationStore,
  auditStore: Option[AuditStore]
) extends RunExecutor:
  override def run(target: StoredTarget, run: Run, patch: Patch): IO[Unit] =
    execute(target, run, patch, None)

  override def runClaimed(target: StoredTarget, run: Run, patch: Patch, lease: RunLease): IO[Unit] =
    execute(target, run, patch, Some(lease))

  private def execute(target: StoredTarget, run: Run, patch: Patch, lease: Option[RunLease]): IO[Unit] =
    lease.fold(runStore.startRun(run.id))(runStore.startRun).flatMap {
      case false => IO.unit
      case true =>
        executeStarted(target, run, patch, lease)
          .handleErrorWith(error => failRun(run, patch, "", error.getMessage, lease))
    }

  private def executeStarted(target: StoredTarget, run: Run, patch: Patch, lease: Option[RunLease]): IO[Unit] =
    for
      dbKind <- IO.fromEither(TargetDatabase.dbKindFor(target.target.jdbc_url).leftMap(MigratorError.Validation(_)))
      patchFiles <- patchStore.sqlFiles(patch, dbKind)
      validation <- validationStore.validateFiles(run, dbKind, patchFiles.map(_.sqlFile))
      _ <-
        if validation.status == "errors" then
          val reason = validation.invalid.headOption.map(_.error).getOrElse("validation failed")
          failRun(run, patch, "", reason, lease)
        else
          TargetDatabase.providerFor(config, target).flatMap { case (_, provider) =>
            Ref.of[IO, Option[(String, Throwable)]](None).flatMap { failedScript =>
              val callbacks = callbacksFor(run, patchFiles, failedScript, lease)
              val context = MigrationRunContext
                .fromConfig(config.copy(dbKind = dbKind, databaseUrl = Some(target.target.jdbc_url)))
                .copy(runId = Some(run.id), targetId = Some(run.target_id))
              MigrationEngine(provider, com.sslproxy.schema.discovery.DiscoveryService())
                .applyFiles(
                  config.copy(dbKind = dbKind, databaseUrl = Some(target.target.jdbc_url)),
                  patchFiles.map(_.sqlFile),
                  callbacks,
                  context
                )
                .attempt
                .flatMap {
                  case Right(report) if report.failedFiles > 0 =>
                    failedScript.get.flatMap { failed =>
                      failRun(
                        run,
                        patch,
                        failed.map(_._1).getOrElse(""),
                        s"${report.failedFiles} migration script(s) failed",
                        lease
                      )
                    }
                  case Right(_) =>
                    completeRun(run, patch, lease)
                  case Left(error) =>
                    failedScript.get.flatMap { failed =>
                      failRun(run, patch, failed.map(_._1).getOrElse(""), error.getMessage, lease)
                    }
                }
            }
          }
    yield ()

  private def callbacksFor(
    run: Run,
    patchFiles: List[PatchSqlFile],
    failedScript: Ref[IO, Option[(String, Throwable)]],
    lease: Option[RunLease]
  ): ApplyCallbacks =
    val scriptsBySource = patchFiles.map(file => file.sqlFile.relativePath -> file.script).toMap
    val total = run.scripts.length

    ApplyCallbacks.silent
      .copy(
        runStarted = context =>
          runStore.log(
            run.id,
            "info",
            s"starting ${context.dbKind} migration for target ${context.targetId.getOrElse(run.target_id)}"
          ),
        warnings = warnings => warnings.traverse_(warning => runStore.log(run.id, "warn", warning)),
        scriptStarted = (prepared, _, _) =>
          scriptFor(prepared, scriptsBySource)
            .traverse_(script => lease.fold(runStore.scriptStarted(run.id, script.id, script.filename, script.order, total))(
              runStore.scriptStarted(_, script.id, script.filename, script.order, total)
            )),
        scriptSkipped = (prepared, _, _, elapsed) =>
          scriptFor(prepared, scriptsBySource).traverse_(script =>
            lease.fold(runStore.scriptCompleted(run.id, script.id, script.filename, elapsed))(
              runStore.scriptCompleted(_, script.id, script.filename, elapsed)
            ) *>
              runStore.log(run.id, "info", s"skipped ${script.filename}")
          ),
        scriptCompleted = (prepared, _, _, elapsed) =>
          scriptFor(prepared, scriptsBySource)
            .traverse_(script => lease.fold(runStore.scriptCompleted(run.id, script.id, script.filename, elapsed))(
              runStore.scriptCompleted(_, script.id, script.filename, elapsed)
            )),
        scriptFailed = (prepared, _, _, error, elapsed) =>
          scriptFor(prepared, scriptsBySource).traverse_ { script =>
            val scriptError = scriptErrorFor(error)
            failedScript.set(Some(script.id -> error)) *>
              lease.fold(runStore.scriptFailed(run.id, script.id, script.filename, scriptError, elapsed))(
                runStore.scriptFailed(_, script.id, script.filename, scriptError, elapsed)
              )
          },
        shouldContinue = lease match
          case None => runStore.currentStatus(run.id).map(_.forall(status => !RunStore.isTerminalStatus(status)))
          case Some(value) =>
            runStore.ownsLease(value).flatMap {
              case false => IO.raiseError(RunExecutor.LeaseLost(run.id))
              case true => runStore.currentStatus(run.id).map(_.exists(status => !RunStore.isTerminalStatus(status)))
            }
      )

  private def scriptFor(prepared: PreparedObject, scriptsBySource: Map[String, Script]): Option[Script] =
    scriptsBySource.get(prepared.objectDef.sourceFile)

  private def completeRun(run: Run, patch: Patch, lease: Option[RunLease]): IO[Unit] =
    nowString.flatMap { ended =>
      lease.fold(runStore.completeRun(run.id, ended, validationTriggered = true))(
        runStore.completeRun(_, ended, validationTriggered = true)
      ).flatMap {
        case Some(completed) =>
          patchStore.markApplied(patch.id, ended) *>
            recordRunAudit(completed, "run.complete", None)
        case None => IO.unit
      }
    }

  private def failRun(
    run: Run,
    patch: Patch,
    failedScriptId: String,
    reason: String,
    lease: Option[RunLease]
  ): IO[Unit] =
    runStore.currentStatus(run.id).flatMap {
      case Some("aborted") | Some("completed") => IO.unit
      case _ =>
        nowString.flatMap { ended =>
          lease.fold(runStore.failRun(run.id, ended, failedScriptId, reason))(
            runStore.failRun(_, ended, failedScriptId, reason)
          ).flatMap {
            case Some(failed) => patchStore.markFailed(patch.id) *> recordRunAudit(failed, "run.fail", Some(reason))
            case None => IO.unit
          }
        }
    }

  private def recordRunAudit(run: Run, action: String, reason: Option[String]): IO[Unit] =
    auditStore.traverse_ { store =>
      store
        .record(
          actor = "system",
          role = UserRole.Admin,
          action = action,
          entityType = "run",
          entityId = run.id,
          targetId = Some(run.target_id),
          metadata = Some(
            io.circe.Json.obj(
              "patch_id" -> io.circe.Json.fromString(run.patch_id),
              "triggered_by" -> io.circe.Json.fromString(run.triggered_by),
              "reason" -> reason.fold(io.circe.Json.Null)(io.circe.Json.fromString)
            )
          )
        )
        .void
    }

  private def scriptErrorFor(error: Throwable): ScriptError =
    findSqlException(error) match
      case Some(sql) =>
        ScriptError(
          db_code = Option(sql.getSQLState).filter(_.nonEmpty).getOrElse(sql.getErrorCode.toString),
          message = Option(sql.getMessage).getOrElse(error.getMessage),
          hint = Option(sql.getNextException).map(_.getMessage),
          context = None,
          line = None
        )
      case None =>
        val code = error match
          case _: MigratorError.Validation => "VALIDATION"
          case _: MigratorError.Connection => "CONNECTION"
          case _: MigratorError.NonRetryableApply => "NON_RETRYABLE_APPLY"
          case _: MigratorError.Apply => "APPLY"
          case _ => "ERROR"
        ScriptError(
          db_code = code,
          message = Option(error.getMessage).getOrElse(error.toString),
          hint = None,
          context = None,
          line = None
        )

  private def findSqlException(error: Throwable): Option[SQLException] =
    error match
      case sql: SQLException => Some(sql)
      case other if other.getCause != null => findSqlException(other.getCause)
      case _ => None

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)

private final class SimulatedRunExecutor(
  patchStore: PatchStore,
  runStore: RunStore,
  validationStore: ValidationStore,
  delay: FiniteDuration,
  auditStore: Option[AuditStore]
) extends RunExecutor:
  override def run(target: StoredTarget, run: Run, patch: Patch): IO[Unit] =
    runStore.startRun(run.id).flatMap {
      case false => IO.unit
      case true =>
        val total = run.scripts.length
        val program = run.scripts.traverse_ { script =>
          runStore.currentStatus(run.id).flatMap {
            case Some(status) if RunStore.isTerminalStatus(status) => IO.unit
            case _ =>
              for
                started <- runStore.scriptStarted(run.id, script.script_id, script.filename, script.order, total)
                startedAt <- Clock[IO].monotonic
                _ <- if started then Temporal[IO].sleep(delay) else IO.unit
                elapsed <- Clock[IO].monotonic.map(duration => (duration - startedAt).toMillis.max(1L))
                _ <-
                  if started then runStore.scriptCompleted(run.id, script.script_id, script.filename, elapsed)
                  else IO.unit
              yield ()
          }
        }
        program *> complete(run, patch)
    }

  private def complete(run: Run, patch: Patch): IO[Unit] =
    Clock[IO].realTimeInstant.map(_.toString).flatMap { ended =>
      runStore.completeRun(run.id, ended, validationTriggered = true).flatMap {
        case Some(completed) =>
          patchStore.markApplied(patch.id, ended) *>
            validationStore.record(run.id, run.target_id, ValidationReport()).void *>
            recordRunAudit(completed)
        case None => IO.unit
      }
    }

  private def recordRunAudit(run: Run): IO[Unit] =
    auditStore.traverse_ { store =>
      store
        .record(
          actor = "system",
          role = UserRole.Admin,
          action = "run.complete",
          entityType = "run",
          entityId = run.id,
          targetId = Some(run.target_id),
          metadata = Some(
            io.circe.Json.obj(
              "patch_id" -> io.circe.Json.fromString(run.patch_id),
              "triggered_by" -> io.circe.Json.fromString(run.triggered_by)
            )
          )
        )
        .void
    }
