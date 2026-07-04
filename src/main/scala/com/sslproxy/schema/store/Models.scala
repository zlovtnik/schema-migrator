package com.sslproxy.schema.store

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*

private def redacted(value: Option[String]): String =
  value.fold("None")(_ => "Some(<redacted>)")

private def redactedJdbcUrl(value: String): String =
  value
    .replaceAll("(?i)(password=)[^&;\\s]+", "$1<redacted>")
    .replaceAll("(?i)(pwd=)[^&;\\s]+", "$1<redacted>")
    .replaceAll("(?i)(jdbc:oracle:thin:[^/\\s:@]+/)[^@\\s]+(@)", "$1<redacted>$2")
    .replaceAll("(?i)(//[^:/?#]+:)[^@/?#]+(@)", "$1<redacted>$2")

final case class Target(
  id: String,
  label: String,
  app_name: String,
  env: String,
  jdbc_url: String,
  created_at: String
):
  override def toString: String =
    s"Target(id=$id, label=$label, app_name=$app_name, env=$env, jdbc_url=${redactedJdbcUrl(jdbc_url)}, created_at=$created_at)"

final case class TargetPayload(
  label: String,
  app_name: String,
  env: String,
  jdbc_url: String,
  password: Option[String]
):
  override def toString: String =
    s"TargetPayload(label=$label, app_name=$app_name, env=$env, jdbc_url=${redactedJdbcUrl(jdbc_url)}, password=${redacted(password)})"

object TargetPayload:
  def rejectInlineCredentials(jdbcUrl: String): Either[String, Unit] =
    if containsInlineCredentials(jdbcUrl) then
      Left("JDBC URL must not contain inline credentials; provide credentials in the password field")
    else Right(())

  private def containsInlineCredentials(jdbcUrl: String): Boolean =
    List(
      "(?i)(^|[?&;])(password|pwd)=",
      "(?i)//[^:/?#\\s]+:[^@/?#\\s]+@",
      "(?i)^jdbc:oracle:thin:[^/\\s:@]+/[^@\\s]+@"
    ).exists(pattern => pattern.r.findFirstIn(jdbcUrl).nonEmpty)

final case class StoredTarget(target: Target, password: Option[String]):
  override def toString: String =
    s"StoredTarget(target=$target, password=${redacted(password)})"

final case class ConnectionTestResult(ok: Boolean, latency_ms: Option[Long], error: Option[String])

final case class ScriptError(
  db_code: String,
  message: String,
  hint: Option[String],
  context: Option[String],
  line: Option[Int]
)

final case class Script(
  id: String,
  patch_id: String,
  order: Int,
  filename: String,
  checksum: String,
  status: String,
  error: Option[ScriptError],
  duration_ms: Option[Long]
)

final case class Patch(
  id: String,
  target_id: String,
  version: String,
  label: String,
  scripts: List[Script],
  status: String,
  applied_at: Option[String],
  source_snapshot_id: Option[String] = None
)

final case class ScriptRun(
  script_id: String,
  filename: String,
  order: Int,
  status: String,
  error: Option[ScriptError],
  duration_ms: Option[Long]
)

final case class Run(
  id: String,
  target_id: String,
  patch_id: String,
  status: String,
  scripts: List[ScriptRun],
  started_at: String,
  ended_at: Option[String],
  triggered_by: String
)

final case class TriggerRunPayload(patch_id: String, target_id: String)

final case class SnapshotFile(
  path: String,
  folder: String,
  filename: String,
  sha256: String,
  content_base64: Option[String],
  uploaded_at: String,
  size_bytes: Long
)

final case class Snapshot(
  id: String,
  target_id: String,
  label: String,
  created_at: String,
  created_by: String,
  file_count: Int,
  files: List[SnapshotFile]
)

final case class SnapshotDiffItem(
  path: String,
  diff_type: String,
  before_sha256: Option[String],
  after_sha256: Option[String]
)

final case class SnapshotDiff(
  snapshot_id: String,
  other_snapshot_id: String,
  generated_at: String,
  items: List[SnapshotDiffItem]
)

final case class AuditEvent(
  id: String,
  actor: String,
  role: String,
  action: String,
  entity_type: String,
  entity_id: String,
  target_id: Option[String],
  at: String,
  metadata: Option[Json]
)

final case class CreateSnapshotPayload(target_id: String, label: Option[String])

final case class RollbackToSnapshotPayload(
  target_id: String,
  source_type: Option[String],
  source_id: Option[String]
)

final case class InvalidObject(
  object_type: String,
  schema: String,
  name: String,
  error: String,
  severity: String
)

final case class ValidationResult(
  run_id: String,
  target_id: String,
  checked_at: String,
  invalid: List[InvalidObject],
  status: String
)

final case class SchemaCatalogObject(
  schema: String,
  name: String,
  object_type: String,
  status: String,
  source_file: Option[String],
  checksum: Option[String],
  apply_status: Option[String],
  actual_ddl: Option[String],
  expected_ddl: Option[String],
  last_checked: String
)

final case class SchemaCatalogResponse(
  target_id: String,
  db_kind: String,
  supported: Boolean,
  checked_at: String,
  objects: List[SchemaCatalogObject],
  warnings: List[String]
)

final case class DriftItem(
  schema: String,
  name: String,
  object_type: String,
  drift_type: String,
  expected: String,
  actual: String,
  source_file: Option[String],
  checksum: Option[String],
  apply_status: Option[String],
  detected_at: String
)

final case class SchemaControlSummary(
  total_count: Long,
  applied_count: Long,
  skipped_count: Long,
  pending_count: Long,
  failed_count: Long,
  ready: Boolean,
  failed_objects: List[String],
  last_applied_at: Option[String],
  last_updated_at: Option[String]
)

final case class DriftResponse(
  target_id: String,
  db_kind: String,
  supported: Boolean,
  checked_at: String,
  control_summary: Option[SchemaControlSummary],
  items: List[DriftItem],
  warnings: List[String]
)

final case class RunEvent(run_id: String, name: String, payload: Json)

final case class AuthTokenRequest(subject: Option[String], secret: Option[String], role: Option[String]):
  override def toString: String =
    s"AuthTokenRequest(subject=$subject, secret=${redacted(secret)}, role=$role)"

final case class AuthTokenResponse(token: String, token_type: String, expires_in: Long):
  override def toString: String =
    s"AuthTokenResponse(token=<redacted>, token_type=$token_type, expires_in=$expires_in)"

object Models:
  given Decoder[TargetPayload] = deriveDecoder
  given Decoder[TriggerRunPayload] = deriveDecoder
  given Decoder[CreateSnapshotPayload] = deriveDecoder
  given Decoder[RollbackToSnapshotPayload] = deriveDecoder
  given Decoder[AuthTokenRequest] = deriveDecoder

  given Encoder[Target] = deriveEncoder
  given Encoder[ConnectionTestResult] = deriveEncoder
  given Encoder[ScriptError] = deriveEncoder
  given Encoder[Script] = deriveEncoder
  given Encoder[Patch] = deriveEncoder
  given Encoder[ScriptRun] = deriveEncoder
  given Encoder[Run] = deriveEncoder
  given Encoder[SnapshotFile] = deriveEncoder
  given Encoder[Snapshot] = deriveEncoder
  given Encoder[SnapshotDiffItem] = deriveEncoder
  given Encoder[SnapshotDiff] = deriveEncoder
  given Encoder[AuditEvent] = deriveEncoder
  given Encoder[InvalidObject] = deriveEncoder
  given Encoder[ValidationResult] = deriveEncoder
  given Encoder[SchemaCatalogObject] = deriveEncoder
  given Encoder[SchemaCatalogResponse] = deriveEncoder
  given Encoder[DriftItem] = deriveEncoder
  given Encoder[SchemaControlSummary] = deriveEncoder
  given Encoder[DriftResponse] = deriveEncoder
  given Encoder[AuthTokenResponse] = deriveEncoder
