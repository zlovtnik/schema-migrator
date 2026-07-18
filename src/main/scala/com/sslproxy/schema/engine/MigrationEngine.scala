package com.sslproxy.schema.engine

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.db.{DbProvider, DbSession}
import com.sslproxy.schema.discovery.{DiscoveryService, SqlFile}
import com.sslproxy.schema.effect.{Lock, MigrationRunContext}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.output.ReportPrinter

import scala.concurrent.duration.*

final case class ApplyCallbacks(
  runStarted: MigrationRunContext => IO[Unit],
  warnings: List[String] => IO[Unit],
  phaseStarted: String => IO[Unit],
  scriptStarted: (PreparedObject, Int, Int) => IO[Unit],
  scriptSkipped: (PreparedObject, Int, Int, Long) => IO[Unit],
  scriptCompleted: (PreparedObject, Int, Int, Long) => IO[Unit],
  scriptFailed: (PreparedObject, Int, Int, Throwable, Long) => IO[Unit],
  shouldContinue: IO[Boolean]
)

object ApplyCallbacks:
  val silent: ApplyCallbacks =
    ApplyCallbacks(
      runStarted = _ => IO.unit,
      warnings = _ => IO.unit,
      phaseStarted = _ => IO.unit,
      scriptStarted = (_, _, _) => IO.unit,
      scriptSkipped = (_, _, _, _) => IO.unit,
      scriptCompleted = (_, _, _, _) => IO.unit,
      scriptFailed = (_, _, _, _, _) => IO.unit,
      shouldContinue = IO.pure(true)
    )

  def console(config: MigratorConfig): ApplyCallbacks =
    silent.copy(
      warnings = ReportPrinter.warnings,
      phaseStarted = name => IO.println(s"-- $name --"),
      scriptStarted = (prepared, position, total) =>
        val echo =
          if config.verbose then
            IO.println(s"executing ${prepared.objectDef.sourceFile}\n${prepared.objectDef.rawSql}\n")
          else IO.unit
        echo *> IO.println(s"[$position/$total] running ${prepared.objectDef.sourceFile}")
      ,
      scriptSkipped =
        (prepared, position, total, _) => IO.println(s"[$position/$total] skipped ${prepared.objectDef.sourceFile}"),
      scriptCompleted =
        (prepared, position, total, _) => IO.println(s"[$position/$total] applied ${prepared.objectDef.sourceFile}"),
      scriptFailed = (prepared, position, total, error, _) =>
        IO.println(s"[$position/$total] failed ${prepared.objectDef.sourceFile}: ${error.getMessage}")
    )

final class MigrationEngine(provider: DbProvider, discoveryService: DiscoveryService):
  def apply(config: MigratorConfig, callbacks: ApplyCallbacks): IO[ApplyReport] =
    for
      plan <- MigrationPlan.prepare(config, provider.dialect, discoveryService)
      report <- applyPlan(config, plan, callbacks, MigrationRunContext.fromConfig(config))
    yield report

  def applyFiles(
    config: MigratorConfig,
    files: List[SqlFile],
    callbacks: ApplyCallbacks,
    context: MigrationRunContext
  ): IO[ApplyReport] =
    for
      plan <- MigrationPlan.prepareFiles(config.dbKind, files, provider.dialect, discoveryService)
      report <- applyPlan(config, plan, callbacks, context)
    yield report

  private def applyPlan(
    config: MigratorConfig,
    plan: MigrationPlan,
    callbacks: ApplyCallbacks,
    context: MigrationRunContext
  ): IO[ApplyReport] =
    for
      _ <- callbacks.runStarted(context)
      _ <- callbacks.warnings(plan.warnings)
      report <- provider.session.use { session =>
        session.bootstrap *>
          Lock.use(session.acquireLock, session.releaseLock)(
            session.retire(plan.retiredObjects) *> applyWithLock(session, plan.objects, config, callbacks)
          )
      }
    yield report

  private def applyWithLock(
    session: DbSession,
    manifest: List[SchemaObject],
    config: MigratorConfig,
    callbacks: ApplyCallbacks
  ): IO[ApplyReport] =
    session.prepare(manifest).flatMap { prepared =>
      val structural = prepared.filter(_.objectDef.phase == Phase.Structural)
      val behavioral = prepared.filter(_.objectDef.phase == Phase.Behavioral)

      val structuralReport =
        if structural.isEmpty then IO.pure(ApplyReport())
        else
          callbacks.phaseStarted("Phase 1/2: Structural objects") *> applyPhase(
            session,
            structural,
            config,
            callbacks
          )

      structuralReport.flatMap { first =>
        val behavioralReport =
          if behavioral.isEmpty || (first.failedFiles > 0 && !config.continueOnError) then IO.pure(ApplyReport())
          else
            callbacks.phaseStarted("Phase 2/2: Behavioral objects") *> applyPhase(
              session,
              behavioral,
              config,
              callbacks
            )
        behavioralReport.map(first.combine)
      }
    }

  private def applyPhase(
    session: DbSession,
    objects: List[PreparedObject],
    config: MigratorConfig,
    callbacks: ApplyCallbacks
  ): IO[ApplyReport] =
    objects.zipWithIndex.foldM(ApplyReport()) { case (report, (prepared, index)) =>
      val position = index + 1
      val total = objects.length
      callbacks.shouldContinue.flatMap {
        case false => IO.pure(report)
        case true if !prepared.needsApply =>
          timed(session.recordSkipped(prepared)).flatMap { elapsed =>
            callbacks.scriptSkipped(prepared, position, total, elapsed) *>
              IO.pure(report.copy(skippedFiles = report.skippedFiles + 1))
          }
        case true =>
          callbacks.scriptStarted(prepared, position, total) *>
            timedAttempt(session.executeObject(prepared)).flatMap {
              case Right(elapsed) =>
                callbacks.scriptCompleted(prepared, position, total, elapsed) *>
                  IO.pure(report.copy(appliedFiles = report.appliedFiles + 1))
              case Left((error, elapsed)) =>
                callbacks.scriptFailed(prepared, position, total, error, elapsed) *>
                  (if config.continueOnError && !error.isInstanceOf[MigratorError.NonRetryableApply]
                   then IO.pure(report.copy(failedFiles = report.failedFiles + 1))
                   else IO.raiseError(error))
            }
      }
    }

  private def timed(operation: IO[Unit]): IO[Long] =
    for
      started <- IO.monotonic
      _ <- operation
      elapsed <- elapsedSince(started)
    yield elapsed

  private def timedAttempt(operation: IO[Unit]): IO[Either[(Throwable, Long), Long]] =
    for
      started <- IO.monotonic
      result <- operation.attempt
      elapsed <- elapsedSince(started)
    yield result.leftMap(error => error -> elapsed).as(elapsed)

  private def elapsedSince(started: FiniteDuration): IO[Long] =
    IO.monotonic.map(duration => (duration - started).toMillis.max(1L))
