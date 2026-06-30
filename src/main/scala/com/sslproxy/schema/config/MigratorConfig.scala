package com.sslproxy.schema.config

import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.FiniteDuration

enum DbKind:
  case Postgres, Oracle

object DbKind:
  def parse(value: String): Either[String, DbKind] =
    value.trim.toLowerCase match
      case "postgres" | "postgresql" => Right(Postgres)
      case "oracle" => Right(Oracle)
      case other => Left(s"unsupported db kind '$other'")

final case class MigratorConfig(
  dbKind: DbKind,
  databaseUrl: Option[String],
  sqlDir: Path,
  dryRun: Boolean,
  verbose: Boolean,
  continueOnError: Boolean,
  connectRetries: Int,
  connectRetryBackoff: FiniteDuration,
  oracleWallet: Option[Path],
  oracleTnsAlias: Option[String],
  oracleUser: Option[String],
  oraclePasswordFile: Option[Path],
  json: Boolean,
  server: ServerConfig
):
  /** Validate configuration at parse time, returning an error message
    * for invalid or missing combinations that would otherwise fail
    * at the first database call.
    */
  def validate: Either[String, Unit] =
    for
      _ <- validateDbSpecific()
      _ <- validateSqlDir()
    yield ()

  def validateServer: Either[String, Unit] =
    server.validate

  private def validateDbSpecific(): Either[String, Unit] =
    dbKind match
      case DbKind.Oracle if databaseUrl.isEmpty && oracleTnsAlias.isEmpty =>
        Left("Oracle requires --database-url, --oracle-tns-alias, or ORACLE_JDBC_URL / ORACLE_CONN")
      case DbKind.Oracle if oracleUser.isEmpty =>
        Left("Oracle requires --oracle-user (or ORACLE_USER)")
      case DbKind.Oracle if oraclePasswordFile.isEmpty =>
        Left("Oracle requires --oracle-pass-file (or ORACLE_PASS_FILE)")
      case DbKind.Postgres if databaseUrl.isEmpty =>
        Left("Postgres requires --database-url (or DATABASE_URL)")
      case _ => Right(())

  private def validateSqlDir(): Either[String, Unit] =
    if Files.notExists(sqlDir) then Left(s"sql directory '$sqlDir' does not exist or is not accessible")
    else if !Files.isDirectory(sqlDir) then Left(s"path '$sqlDir' is not a directory")
    else Right(())

final case class ServerConfig(
  host: String,
  port: Int,
  corsOrigins: Set[String],
  encryptKeyBase64: Option[String],
  jwtSecret: String,
  devAuthSecret: String,
  dbTestAllowedHosts: Set[String],
  patchStageDir: Path
):
  def validate: Either[String, Unit] =
    if host.trim.isEmpty then Left("server host must not be empty")
    else if port < 1 || port > 65535 then Left("server port must be between 1 and 65535")
    else if jwtSecret.trim.isEmpty then Left("BEDROCK_JWT_SECRET must not be empty")
    else if devAuthSecret.trim.isEmpty then Left("BEDROCK_DEV_AUTH_SECRET must not be empty")
    else validateEncryptKeyBase64().flatMap(_ => validatePatchStageDir())

  private def validateEncryptKeyBase64(): Either[String, Unit] =
    encryptKeyBase64 match
      case None => Right(())
      case Some(value) if value.trim.isEmpty => Left("BEDROCK_ENCRYPT_KEY must not be empty when set")
      case Some(value) =>
        try
          val bytes = Base64.getDecoder.decode(value.trim)
          if bytes.length == 32 then Right(())
          else Left("AES-GCM key must decode to 32 bytes")
        catch case error: IllegalArgumentException => Left(error.getMessage)

  private def validatePatchStageDir(): Either[String, Unit] =
    try
      if Files.exists(patchStageDir) && !Files.isDirectory(patchStageDir) then
        Left(s"patch stage path '$patchStageDir' is not a directory")
      else
        if Files.notExists(patchStageDir) then Files.createDirectories(patchStageDir)
        if !Files.isDirectory(patchStageDir) then Left(s"patch stage path '$patchStageDir' is not a directory")
        else if !Files.isWritable(patchStageDir) then Left(s"patch stage directory '$patchStageDir' is not writable")
        else Right(())
    catch
      case error: Exception =>
        Left(s"patch stage directory '$patchStageDir' is not usable: ${error.getMessage}")
