package com.sslproxy.schema.output

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.discovery.DiscoveryResult
import com.sslproxy.schema.engine.{ApplyReport, ObjectStatus, SchemaReadyStatus}
import com.sslproxy.schema.validation.ValidationReport

object ReportPrinter:
  def warnings(warnings: List[String]): IO[Unit] =
    warnings.traverse_(warning => IO.println(s"warning: $warning"))

  def discovery(discovery: DiscoveryResult): IO[Unit] =
    warnings(discovery.warnings) *>
      discovery.files.traverse_(file => IO.println(file.relativePath))

  def validation(report: ValidationReport): IO[Unit] =
    report.warnings.traverse_(warning => IO.println(s"warning: $warning")) *>
      report.errors.traverse_(error => IO.println(s"error: $error"))

  def dryRun(files: List[(String, String)]): IO[Unit] =
    files.traverse_ { case (path, preview) => IO.println(f"$path%-48s $preview") }

  def applyReport(report: ApplyReport): IO[Unit] =
    IO.println(
      s"schema apply complete: applied=${report.appliedFiles} skipped=${report.skippedFiles} failed=${report.failedFiles}"
    )

  def status(statuses: List[ObjectStatus], ready: SchemaReadyStatus): IO[Unit] =
    val header = IO.println(f"${"KIND"}%-18s ${"OBJECT"}%-44s ${"STATUS"}%-10s ${"APPLIED_AT"}%-19s ${"SHA256"}%-12s SOURCE")
    val rows = statuses.traverse_ { status =>
      val sha = if status.contentSha256.length > 12 then status.contentSha256.take(12) else status.contentSha256
      val row = IO.println(
        f"${truncate(status.kind, 18)}%-18s ${truncate(status.objectName, 44)}%-44s ${status.applyStatus}%-10s ${status.appliedAt.getOrElse("-")}%-19s $sha%-12s ${status.sourceFile}"
      )
      status.lastError match
        case Some(error) => row *> IO.println(s"  error: ${truncate(error, 140)}")
        case None        => row
    }
    header *> rows *> readySummary(ready)

  def readySummary(ready: SchemaReadyStatus): IO[Unit] =
    IO.println(
      s"schema_ready: ready=${ready.ready} total=${ready.totalCount} applied=${ready.appliedCount} pending=${ready.pendingCount} failed=${ready.failedCount} last_applied_at=${ready.lastAppliedAt.getOrElse("-")} last_updated_at=${ready.lastUpdatedAt.getOrElse("-")}"
    )

  private def truncate(value: String, max: Int): String =
    if value.length <= max then value else value.take(math.max(0, max - 3)) + "..."
