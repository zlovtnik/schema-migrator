package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref}
import com.sslproxy.schema.config.DbKind
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.SqlFile
import com.sslproxy.schema.engine.MigrationPlan
import com.sslproxy.schema.validation.ValidationReport


trait ValidationStore:
  def list(targetId: Option[String]): IO[List[ValidationResult]]
  def get(runId: String): IO[Option[ValidationResult]]
  def record(runId: String, targetId: String, report: ValidationReport): IO[ValidationResult]

  def validateFiles(run: Run, dbKind: DbKind, files: List[SqlFile]): IO[ValidationResult] =
    MigrationPlan
      .inspectFiles(dbKind, files, SqlDialect.forDbKind(dbKind))
      .flatMap(plan => record(run.id, run.target_id, plan.validation))

  def validateRun(run: Run, patch: Patch, patchStore: PatchStore, dbKind: DbKind): IO[ValidationResult] =
    patchStore.sqlFiles(patch, dbKind).flatMap(files => validateFiles(run, dbKind, files.map(_.sqlFile)))

object ValidationStore:
  def inMemory: IO[ValidationStore] =
    Ref.of[IO, Map[String, ValidationResult]](Map.empty).map(InMemoryValidationStore.apply)

  private[store] def resultFromReport(
    runId: String,
    targetId: String,
    checkedAt: String,
    report: ValidationReport
  ): ValidationResult =
    val (invalid, status) = invalidObjectsAndStatus(report)
    ValidationResult(
      run_id = runId,
      target_id = targetId,
      checked_at = checkedAt,
      invalid = invalid,
      status = status
    )

  def invalidObjectsAndStatus(report: ValidationReport): (List[InvalidObject], String) =
    val errors = report.errors.map(message => invalidObject(message, "error"))
    val warnings = report.warnings.map(message => invalidObject(message, "warning"))
    val status =
      if errors.nonEmpty then "errors"
      else if warnings.nonEmpty then "warnings"
      else "clean"
    (errors ++ warnings, status)

  private def invalidObject(message: String, severity: String): InvalidObject =
    val name =
      message.takeWhile(_ != ':').trim match
        case "" => "validation"
        case value => value
    InvalidObject(
      object_type = "other",
      schema = "",
      name = name,
      error = message,
      severity = severity
    )

private final class InMemoryValidationStore(ref: Ref[IO, Map[String, ValidationResult]]) extends ValidationStore:
  override def list(targetId: Option[String]): IO[List[ValidationResult]] =
    ref.get.map { results =>
      results.values.toList
        .filter(result => targetId.forall(_ == result.target_id))
        .sortBy(_.checked_at)
    }

  override def get(runId: String): IO[Option[ValidationResult]] =
    ref.get.map(_.get(runId))

  override def record(runId: String, targetId: String, report: ValidationReport): IO[ValidationResult] =
    for
      now <- nowString
      result = ValidationStore.resultFromReport(runId, targetId, now, report)
      _ <- ref.update(_ + (runId -> result))
    yield result

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)


