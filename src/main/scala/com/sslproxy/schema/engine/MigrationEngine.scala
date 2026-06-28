package com.sslproxy.schema.engine

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.db.{DbProvider, DbSession}
import com.sslproxy.schema.discovery.{DiscoveryResult, DiscoveryService}
import com.sslproxy.schema.effect.{Lock, Retry, RetryPolicy}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.output.ReportPrinter

import java.nio.file.Files
import scala.concurrent.duration.*

final class MigrationEngine(provider: DbProvider, discoveryService: DiscoveryService[IO]):
  def discover(config: MigratorConfig): IO[DiscoveryResult] =
    discoveryService.discover(config.sqlDir, config.dbKind)

  def dryRun(config: MigratorConfig): IO[List[(String, String)]] =
    discover(config).flatMap { discovery =>
      discovery.files.traverse { file =>
        IO.blocking {
          val sql = Files.readString(file.path)
          file.relativePath -> compactPreview(sql, 120)
        }
      }
    }

  def apply(config: MigratorConfig): IO[ApplyReport] =
    for
      discovery <- discover(config)
      _ <- ReportPrinter.warnings(discovery.warnings)
      manifest <- ManifestBuilder[IO](provider.dialect).build(discovery.files)
      sorted <- IO.fromEither(Graph.topologicalSort(manifest))
      report <- provider.session.use { session =>
        val schemaLock = Lock.fromAcquireRelease(session.acquireLock, session.releaseLock)
        session.bootstrap *>
          schemaLock.withLock(applyWithLock(session, sorted, config))
      }
    yield report

  private def applyWithLock(session: DbSession, manifest: List[SchemaObject], config: MigratorConfig): IO[ApplyReport] =
    for
      prepared <- session.prepare(manifest)
      structural = prepared.filter(_.objectDef.phase == Phase.Structural)
      behavioral = prepared.filter(_.objectDef.phase == Phase.Behavioral)
      structuralReport <-
        if structural.isEmpty then IO.pure(ApplyReport())
        else IO.println("-- Phase 1/2: Structural objects --") *> applyPhase(session, structural, config)
      behavioralReport <-
        if behavioral.isEmpty || (structuralReport.failedFiles > 0 && !config.continueOnError) then
          IO.pure(ApplyReport())
        else IO.println("-- Phase 2/2: Behavioral objects --") *> applyPhase(session, behavioral, config)
    yield structuralReport.combine(behavioralReport)

  private def applyPhase(session: DbSession, objects: List[PreparedObject], config: MigratorConfig): IO[ApplyReport] =
    objects.zipWithIndex.foldM(ApplyReport()) { case (report, (prepared, index)) =>
      val position = index + 1
      val total = objects.length
      if !prepared.needsApply then
        session.recordSkipped(prepared) *>
          IO.println(s"[$position/$total] skipped ${prepared.objectDef.sourceFile}") *>
          IO.pure(report.copy(skippedFiles = report.skippedFiles + 1))
      else
        val echo =
          if config.verbose then
            IO.println(s"executing ${prepared.objectDef.sourceFile}\n${prepared.objectDef.rawSql}\n")
          else IO.unit
        echo *> IO.println(s"[$position/$total] running ${prepared.objectDef.sourceFile}") *>
          session.executeObject(prepared).attempt.flatMap {
            case Right(()) =>
              IO.println(s"[$position/$total] applied ${prepared.objectDef.sourceFile}") *>
                IO.pure(report.copy(appliedFiles = report.appliedFiles + 1))
            case Left(error) =>
              IO.println(s"[$position/$total] failed ${prepared.objectDef.sourceFile}: ${error.getMessage}") *>
                (if config.continueOnError && !error.isInstanceOf[MigratorError.NonRetryableApply]
                 then IO.pure(report.copy(failedFiles = report.failedFiles + 1))
                 else IO.raiseError(error))
          }
    }

  private def compactPreview(sql: String, max: Int): String =
    val compact = sql.split("\\s+").filter(_.nonEmpty).mkString(" ")
    if compact.length <= max then compact else compact.take(max - 1) + "..."
