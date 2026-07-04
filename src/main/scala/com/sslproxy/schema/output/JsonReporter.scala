package com.sslproxy.schema.output

import cats.effect.IO
import com.sslproxy.schema.discovery.DiscoveryResult
import com.sslproxy.schema.engine.{ApplyReport, ObjectStatus, SchemaReadyStatus}
import com.sslproxy.schema.store.{DriftResponse, Models}
import com.sslproxy.schema.validation.ValidationReport
import io.circe.Json
import io.circe.syntax.*

object JsonReporter:
  import Models.given

  def discovery(discovery: DiscoveryResult): IO[Unit] =
    IO.println(
      Json
        .obj(
          "warnings" -> discovery.warnings.asJson,
          "files" -> discovery.files
            .map(file => Json.obj("folder" -> file.folder.asJson, "path" -> file.relativePath.asJson))
            .asJson
        )
        .noSpaces
    )

  def validation(report: ValidationReport): IO[Unit] =
    IO.println(Json.obj("warnings" -> report.warnings.asJson, "errors" -> report.errors.asJson).noSpaces)

  def applyReport(report: ApplyReport): IO[Unit] =
    IO.println(
      Json
        .obj(
          "applied_files" -> report.appliedFiles.asJson,
          "skipped_files" -> report.skippedFiles.asJson,
          "failed_files" -> report.failedFiles.asJson
        )
        .noSpaces
    )

  def status(statuses: List[ObjectStatus], ready: SchemaReadyStatus): IO[Unit] =
    IO.println(
      Json
        .obj(
          "objects" -> statuses.map(statusJson).asJson,
          "ready" -> readyJson(ready)
        )
        .noSpaces
    )

  def ready(ready: SchemaReadyStatus): IO[Unit] =
    IO.println(readyJson(ready).noSpaces)

  def drift(response: DriftResponse): IO[Unit] =
    IO.println(response.asJson.noSpaces)

  private def statusJson(status: ObjectStatus): Json =
    Json.obj(
      "kind" -> status.kind.asJson,
      "object_name" -> status.objectName.asJson,
      "source_file" -> status.sourceFile.asJson,
      "apply_status" -> status.applyStatus.asJson,
      "content_sha256" -> status.contentSha256.asJson,
      "applied_at" -> status.appliedAt.asJson,
      "last_error" -> status.lastError.asJson
    )

  private def readyJson(ready: SchemaReadyStatus): Json =
    Json.obj(
      "total_count" -> ready.totalCount.asJson,
      "pending_count" -> ready.pendingCount.asJson,
      "failed_count" -> ready.failedCount.asJson,
      "applied_count" -> ready.appliedCount.asJson,
      "ready" -> ready.ready.asJson,
      "failed_objects" -> ready.failedObjects.asJson,
      "last_updated_at" -> ready.lastUpdatedAt.asJson,
      "last_applied_at" -> ready.lastAppliedAt.asJson
    )
