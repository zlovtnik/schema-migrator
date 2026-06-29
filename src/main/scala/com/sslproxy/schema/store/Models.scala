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
  applied_at: Option[String]
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
  detected_at: String
)

final case class DriftResponse(
  target_id: String,
  db_kind: String,
  supported: Boolean,
  checked_at: String,
  items: List[DriftItem],
  warnings: List[String]
)

final case class RunEvent(run_id: String, name: String, payload: Json)

final case class AuthTokenRequest(subject: Option[String], secret: Option[String]):
  override def toString: String =
    s"AuthTokenRequest(subject=$subject, secret=${redacted(secret)})"

final case class AuthTokenResponse(token: String, token_type: String, expires_in: Long):
  override def toString: String =
    s"AuthTokenResponse(token=<redacted>, token_type=$token_type, expires_in=$expires_in)"

object Models:
  given Decoder[TargetPayload] = deriveDecoder
  given Decoder[TriggerRunPayload] = deriveDecoder
  given Decoder[AuthTokenRequest] = deriveDecoder

  given Encoder[Target] = deriveEncoder
  given Encoder[ConnectionTestResult] = deriveEncoder
  given Encoder[ScriptError] = deriveEncoder
  given Encoder[Script] = deriveEncoder
  given Encoder[Patch] = deriveEncoder
  given Encoder[ScriptRun] = deriveEncoder
  given Encoder[Run] = deriveEncoder
  given Encoder[InvalidObject] = deriveEncoder
  given Encoder[ValidationResult] = deriveEncoder
  given Encoder[SchemaCatalogObject] = deriveEncoder
  given Encoder[SchemaCatalogResponse] = deriveEncoder
  given Encoder[DriftItem] = deriveEncoder
  given Encoder[DriftResponse] = deriveEncoder
  given Encoder[AuthTokenResponse] = deriveEncoder
