package com.sslproxy.schema.config

import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration

enum DbKind:
  case Postgres, Oracle

object DbKind:
  def parse(value: String): Either[String, DbKind] =
    value.trim.toLowerCase match
      case "postgres" | "postgresql" => Right(Postgres)
      case "oracle"                  => Right(Oracle)
      case other                     => Left(s"unsupported db kind '$other'")

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
    json: Boolean
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
    if Files.notExists(sqlDir) then
      Left(s"sql directory '$sqlDir' does not exist or is not accessible")
    else if !Files.isDirectory(sqlDir) then
      Left(s"path '$sqlDir' is not a directory")
    else Right(())