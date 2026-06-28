package com.sslproxy.schema.store

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*

private def redacted(value: Option[String]): String =
  value.fold("None")(_ => "Some(<redacted>)")

final case class Target(
  id: String,
  label: String,
  app_name: String,
  env: String,
  host: String,
  port: Int,
  dbname: String,
  user: String,
  schema: String,
  ssl_mode: String,
  created_at: String
)

final case class TargetPayload(
  label: String,
  app_name: String,
  env: String,
  host: String,
  port: Int,
  dbname: String,
  user: String,
  password: Option[String],
  schema: String,
  ssl_mode: String
):
  override def toString: String =
    s"TargetPayload(label=$label, app_name=$app_name, env=$env, host=$host, port=$port, dbname=$dbname, user=$user, password=${redacted(password)}, schema=$schema, ssl_mode=$ssl_mode)"

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
  given Encoder[AuthTokenResponse] = deriveEncoder
