package com.sslproxy.schema.engine

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.db.{DbProvider, DbSession}
import com.sslproxy.schema.discovery.{DiscoveryResult, DiscoveryService, SqlFile}
import com.sslproxy.schema.effect.{Lock, Retry, RetryPolicy}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.output.ReportPrinter

import scala.concurrent.duration.*

final case class ApplyCallbacks(
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
        echo *> IO.println(s"[$position/$total] running ${prepared.objectDef.sourceFile}"),
      scriptSkipped = (prepared, position, total, _) =>
        IO.println(s"[$position/$total] skipped ${prepared.objectDef.sourceFile}"),
      scriptCompleted = (prepared, position, total, _) =>
        IO.println(s"[$position/$total] applied ${prepared.objectDef.sourceFile}"),
      scriptFailed = (prepared, position, total, error, _) =>
        IO.println(s"[$position/$total] failed ${prepared.objectDef.sourceFile}: ${error.getMessage}")
    )

final class MigrationEngine(provider: DbProvider, discoveryService: DiscoveryService[IO]):
  def discover(config: MigratorConfig): IO[DiscoveryResult] =
    discoveryService.discover(config.sqlDir, config.dbKind)

  def dryRun(config: MigratorConfig): IO[List[(String, String)]] =
    discover(config).flatMap { discovery =>
      discovery.files.traverse { file =>
        IO.blocking {
          val sql = file.readString
          file.relativePath -> compactPreview(sql, 120)
        }
      }
    }

  def apply(config: MigratorConfig): IO[ApplyReport] =
    discover(config).flatMap(discovery => applyDiscovered(config, discovery, ApplyCallbacks.console(config)))

  def applyFiles(
    config: MigratorConfig,
    files: List[SqlFile],
    callbacks: ApplyCallbacks
  ): IO[ApplyReport] =
    val discovery = discoveryService.discoverFromFiles(files, config.dbKind)
    applyDiscovered(config, discovery, callbacks)

  private def applyDiscovered(
    config: MigratorConfig,
    discovery: DiscoveryResult,
    callbacks: ApplyCallbacks
  ): IO[ApplyReport] =
    for
      _ <- callbacks.warnings(discovery.warnings)
      manifest <- ManifestBuilder[IO](provider.dialect).build(discovery.files)
      sorted <- IO.fromEither(Graph.topologicalSort(manifest))
      report <- provider.session.use { (session: DbSession) =>
        val lock: Lock[IO] = Lock.fromAcquireRelease(session.acquireLock, session.releaseLock)
        val action: IO[ApplyReport] =
          session.bootstrap *> lock.withLock[ApplyReport](applyWithLock(session, sorted, config, callbacks))
        action
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

      val structuralReportIO: IO[ApplyReport] =
        if structural.isEmpty then IO.pure(ApplyReport())
        else callbacks.phaseStarted("Phase 1/2: Structural objects") *> applyPhase(session, structural, config, callbacks)

      structuralReportIO.flatMap { structuralReport =>
        val behavioralReportIO: IO[ApplyReport] =
          if behavioral.isEmpty || (structuralReport.failedFiles > 0 && !config.continueOnError) then
            IO.pure(ApplyReport())
          else callbacks.phaseStarted("Phase 2/2: Behavioral objects") *> applyPhase(session, behavioral, config, callbacks)

        behavioralReportIO.map { behavioralReport =>
          structuralReport.combine(behavioralReport)
        }
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
        case true =>
          if !prepared.needsApply then
            timed(session.recordSkipped(prepared)).flatMap { elapsed =>
              callbacks.scriptSkipped(prepared, position, total, elapsed) *>
                IO.pure(report.copy(skippedFiles = report.skippedFiles + 1))
            }
          else
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
      started <- Clock[IO].monotonic
      _ <- operation
      elapsed <- elapsedSince(started)
    yield elapsed

  private def timedAttempt(operation: IO[Unit]): IO[Either[(Throwable, Long), Long]] =
    for
      started <- Clock[IO].monotonic
      result <- operation.attempt
      elapsed <- elapsedSince(started)
    yield result.leftMap(error => error -> elapsed).as(elapsed)

  private def elapsedSince(started: FiniteDuration): IO[Long] =
    Clock[IO].monotonic.map(duration => (duration - started).toMillis.max(1L))

  private def compactPreview(sql: String, max: Int): String =
    val compact = sql.split("\\s+").filter(_.nonEmpty).mkString(" ")
    if compact.length <= max then compact else compact.take(max - 1) + "..."
