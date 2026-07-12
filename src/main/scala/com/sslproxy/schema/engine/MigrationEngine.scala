package com.sslproxy.schema.engine

import cats.Applicative
import cats.effect.{Async, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.db.{DbProvider, DbSession}
import com.sslproxy.schema.discovery.{DiscoveryResult, DiscoveryService, SqlFile}
import com.sslproxy.schema.effect.{Lock, MigrationContext, MigrationRunContext}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.output.ReportPrinter

import scala.concurrent.duration.*

final case class ApplyCallbacks[F[_]](
  runStarted: MigrationRunContext => F[Unit],
  warnings: List[String] => F[Unit],
  phaseStarted: String => F[Unit],
  scriptStarted: (PreparedObject, Int, Int) => F[Unit],
  scriptSkipped: (PreparedObject, Int, Int, Long) => F[Unit],
  scriptCompleted: (PreparedObject, Int, Int, Long) => F[Unit],
  scriptFailed: (PreparedObject, Int, Int, Throwable, Long) => F[Unit],
  shouldContinue: F[Boolean]
)

object ApplyCallbacks:
  def silent[F[_]: Applicative]: ApplyCallbacks[F] =
    ApplyCallbacks(
      runStarted = _ => Applicative[F].unit,
      warnings = _ => Applicative[F].unit,
      phaseStarted = _ => Applicative[F].unit,
      scriptStarted = (_, _, _) => Applicative[F].unit,
      scriptSkipped = (_, _, _, _) => Applicative[F].unit,
      scriptCompleted = (_, _, _, _) => Applicative[F].unit,
      scriptFailed = (_, _, _, _, _) => Applicative[F].unit,
      shouldContinue = Applicative[F].pure(true)
    )

  def console(config: MigratorConfig): ApplyCallbacks[IO] =
    silent[IO].copy(
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

final class MigrationEngine[F[_]: Async](provider: DbProvider[F], discoveryService: DiscoveryService[F]):
  def discover(config: MigratorConfig): F[DiscoveryResult] =
    discoveryService.discover(config.sqlDir, config.dbKind, config.customer)

  def dryRun(config: MigratorConfig): F[List[(String, String)]] =
    discover(config).flatMap { discovery =>
      discovery.files.traverse { file =>
        Async[F].blocking {
          val sql = file.readString
          file.relativePath -> compactPreview(sql, 120)
        }
      }
    }

  def apply(config: MigratorConfig, callbacks: ApplyCallbacks[F]): F[ApplyReport] =
    val context = MigrationRunContext.fromConfig(config)
    given MigrationContext[F] = MigrationContext.fixed(context)
    discover(config).flatMap(discovery => applyDiscovered(config, discovery, callbacks))

  def applyFiles(
    config: MigratorConfig,
    files: List[SqlFile],
    callbacks: ApplyCallbacks[F]
  ): F[ApplyReport] =
    applyFiles(config, files, callbacks, MigrationRunContext.fromConfig(config))

  def applyFiles(
    config: MigratorConfig,
    files: List[SqlFile],
    callbacks: ApplyCallbacks[F],
    context: MigrationRunContext
  ): F[ApplyReport] =
    given MigrationContext[F] = MigrationContext.fixed(context)
    val discovery = discoveryService.discoverFromFiles(files, config.dbKind)
    applyDiscovered(config, discovery, callbacks)

  private def applyDiscovered(
    config: MigratorConfig,
    discovery: DiscoveryResult,
    callbacks: ApplyCallbacks[F]
  )(using MigrationContext[F]): F[ApplyReport] =
    for
      context <- MigrationContext[F].current
      _ <- callbacks.runStarted(context)
      _ <- callbacks.warnings(discovery.warnings)
      manifest <- ManifestBuilder[F](provider.dialect).build(discovery.files)
      sorted <- Async[F].fromEither(Graph.topologicalSort(manifest))
      report <- provider.session.use { (session: DbSession[F]) =>
        val lock: Lock[F] = Lock.fromAcquireRelease(session.acquireLock, session.releaseLock)
        val action: F[ApplyReport] =
          session.bootstrap *> lock.withLock[ApplyReport](applyWithLock(session, sorted, config, callbacks))
        action
      }
    yield report

  private def applyWithLock(
    session: DbSession[F],
    manifest: List[SchemaObject],
    config: MigratorConfig,
    callbacks: ApplyCallbacks[F]
  ): F[ApplyReport] =
    session.prepare(manifest).flatMap { prepared =>
      val structural = prepared.filter(_.objectDef.phase == Phase.Structural)
      val behavioral = prepared.filter(_.objectDef.phase == Phase.Behavioral)

      val structuralReportIO: F[ApplyReport] =
        if structural.isEmpty then Async[F].pure(ApplyReport())
        else
          callbacks.phaseStarted("Phase 1/2: Structural objects") *> applyPhase(session, structural, config, callbacks)

      structuralReportIO.flatMap { structuralReport =>
        val behavioralReportIO: F[ApplyReport] =
          if behavioral.isEmpty || (structuralReport.failedFiles > 0 && !config.continueOnError) then
            Async[F].pure(ApplyReport())
          else
            callbacks
              .phaseStarted("Phase 2/2: Behavioral objects") *> applyPhase(session, behavioral, config, callbacks)

        behavioralReportIO.map { behavioralReport =>
          structuralReport.combine(behavioralReport)
        }
      }
    }

  private def applyPhase(
    session: DbSession[F],
    objects: List[PreparedObject],
    config: MigratorConfig,
    callbacks: ApplyCallbacks[F]
  ): F[ApplyReport] =
    objects.zipWithIndex.foldM(ApplyReport()) { case (report, (prepared, index)) =>
      val position = index + 1
      val total = objects.length
      callbacks.shouldContinue.flatMap {
        case false => Async[F].pure(report)
        case true =>
          if !prepared.needsApply then
            timed(session.recordSkipped(prepared)).flatMap { elapsed =>
              callbacks.scriptSkipped(prepared, position, total, elapsed) *>
                Async[F].pure(report.copy(skippedFiles = report.skippedFiles + 1))
            }
          else
            callbacks.scriptStarted(prepared, position, total) *>
              timedAttempt(session.executeObject(prepared)).flatMap {
                case Right(elapsed) =>
                  callbacks.scriptCompleted(prepared, position, total, elapsed) *>
                    Async[F].pure(report.copy(appliedFiles = report.appliedFiles + 1))
                case Left((error, elapsed)) =>
                  callbacks.scriptFailed(prepared, position, total, error, elapsed) *>
                    (if config.continueOnError && !error.isInstanceOf[MigratorError.NonRetryableApply]
                     then Async[F].pure(report.copy(failedFiles = report.failedFiles + 1))
                     else Async[F].raiseError(error))
              }
      }
    }

  private def timed(operation: F[Unit]): F[Long] =
    for
      started <- Async[F].monotonic
      _ <- operation
      elapsed <- elapsedSince(started)
    yield elapsed

  private def timedAttempt(operation: F[Unit]): F[Either[(Throwable, Long), Long]] =
    for
      started <- Async[F].monotonic
      result <- operation.attempt
      elapsed <- elapsedSince(started)
    yield result.leftMap(error => error -> elapsed).as(elapsed)

  private def elapsedSince(started: FiniteDuration): F[Long] =
    Async[F].monotonic.map(duration => (duration - started).toMillis.max(1L))

  private def compactPreview(sql: String, max: Int): String =
    val compact = sql.split("\\s+").filter(_.nonEmpty).mkString(" ")
    if compact.length <= max then compact else compact.take(max - 1) + "..."
