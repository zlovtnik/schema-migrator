package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, ReplaceOptions}
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.sslproxy.schema.config.{DbKind, MongoConfig}
import com.sslproxy.schema.discovery.SqlFile
import com.sslproxy.schema.validation.{ValidationReport, Validator}
import org.bson.Document

import scala.jdk.CollectionConverters.*

trait ValidationStore:
  def list(targetId: Option[String]): IO[List[ValidationResult]]
  def get(runId: String): IO[Option[ValidationResult]]
  def record(runId: String, targetId: String, report: ValidationReport): IO[ValidationResult]

  def validateFiles(run: Run, dbKind: DbKind, files: List[SqlFile]): IO[ValidationResult] =
    Validator[IO](dbKind).validate(files).flatMap(report => record(run.id, run.target_id, report))

  def validateRun(run: Run, patch: Patch, patchStore: PatchStore, dbKind: DbKind): IO[ValidationResult] =
    patchStore.sqlFiles(patch, dbKind).flatMap(files => validateFiles(run, dbKind, files.map(_.sqlFile)))

object ValidationStore:
  def mongo(config: MongoConfig, collectionName: String): Resource[IO, ValidationStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .flatMap(client => mongo(config, collectionName, client))

  def mongo(config: MongoConfig, collectionName: String, client: MongoClient): Resource[IO, ValidationStore] =
    Resource.eval {
      val store = MongoValidationStore(client.getDatabase(config.database).getCollection(collectionName))
      store.initialize.as(store: ValidationStore)
    }

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

private final class MongoValidationStore(collection: MongoCollection[Document]) extends ValidationStore:
  override def list(targetId: Option[String]): IO[List[ValidationResult]] =
    IO.blocking {
      val filter = targetId.fold(new Document())(id => new Document("target_id", id))
      collection
        .find(filter)
        .sort(Indexes.ascending("checked_at"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
    }

  override def get(runId: String): IO[Option[ValidationResult]] =
    IO.blocking(Option(collection.find(idFilter(runId)).first()).map(fromDocument))

  override def record(runId: String, targetId: String, report: ValidationReport): IO[ValidationResult] =
    for
      now <- Clock[IO].realTimeInstant.map(_.toString)
      result = ValidationStore.resultFromReport(runId, targetId, now, report)
      _ <- IO.blocking(collection.replaceOne(idFilter(runId), toDocument(result), ReplaceOptions().upsert(true))).void
    yield result

  private[store] def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("target_id", "checked_at"))
      collection.createIndex(Indexes.ascending("status"))
    }.void

  private def toDocument(result: ValidationResult): Document =
    new Document()
      .append("_id", result.run_id)
      .append("run_id", result.run_id)
      .append("target_id", result.target_id)
      .append("checked_at", result.checked_at)
      .append("status", result.status)
      .append("invalid", result.invalid.map(invalidDocument).asJava)

  private def invalidDocument(invalid: InvalidObject): Document =
    new Document()
      .append("object_type", invalid.object_type)
      .append("schema", invalid.schema)
      .append("name", invalid.name)
      .append("error", invalid.error)
      .append("severity", invalid.severity)

  private def fromDocument(document: Document): ValidationResult =
    ValidationResult(
      run_id = requiredString(document, "run_id"),
      target_id = requiredString(document, "target_id"),
      checked_at = requiredString(document, "checked_at"),
      invalid = invalidDocuments(document).map(invalidFromDocument),
      status = requiredString(document, "status")
    )

  private def invalidFromDocument(document: Document): InvalidObject =
    InvalidObject(
      object_type = requiredString(document, "object_type"),
      schema = requiredString(document, "schema"),
      name = requiredString(document, "name"),
      error = requiredString(document, "error"),
      severity = requiredString(document, "severity")
    )

  private def invalidDocuments(document: Document): List[Document] =
    MongoDocument.documentList(document, "invalid")

  private def idFilter(runId: String): Document =
    new Document("_id", runId)

  private def requiredString(document: Document, field: String): String =
    MongoDocument.requiredString(document, field, "validation")
