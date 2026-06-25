package com.sslproxy.schema.cli

import cats.syntax.all.*
import com.monovore.decline.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*

object CliOpts:
  private given Argument[DbKind] =
    Argument.from("postgres|oracle")(value => DbKind.parse(value).toValidatedNel)

  private given Argument[Path] =
    Argument.from("path")(value => Either.catchNonFatal(Paths.get(value)).leftMap(_.getMessage).toValidatedNel)

  private def env: Map[String, String] = sys.env

  private val dbKindOpt: Opts[DbKind] =
    Opts
      .option[DbKind]("db-kind", help = "Database engine: postgres or oracle")
      .withDefault(env.get("SCHEMA_MIGRATOR_DB_KIND").flatMap(DbKind.parse(_).toOption).getOrElse(DbKind.Postgres))

  private val sqlDirOpt: Opts[Option[Path]] =
    Opts.option[Path]("sql-dir", help = "Root SQL directory").orNone

  private val databaseUrlOpt: Opts[Option[String]] =
    Opts.option[String]("database-url", help = "JDBC or postgres:// database URL").orNone

  private val retriesOpt: Opts[Int] =
    Opts.option[Int]("connect-retries", help = "Connection retry count")
      .withDefault(0)
      .validate("connect-retries must be >= 0")(_ >= 0)

  private val backoffOpt: Opts[FiniteDuration] =
    Opts
      .option[Long]("connect-retry-backoff", help = "Base connection retry backoff seconds")
      .withDefault(2L)
      .map(_.seconds)

  private val oracleWalletOpt: Opts[Option[Path]] =
    Opts.option[Path]("oracle-wallet", help = "Oracle wallet or TNS admin directory").orNone

  private val oracleAliasOpt: Opts[Option[String]] =
    Opts.option[String]("oracle-tns-alias", help = "Oracle TNS alias").orNone

  private val oracleUserOpt: Opts[Option[String]] =
    Opts.option[String]("oracle-user", help = "Oracle username").orNone

  private val oraclePasswordFileOpt: Opts[Option[Path]] =
    Opts.option[Path]("oracle-pass-file", help = "File containing Oracle password").orNone

  private val configOpts: Opts[MigratorConfig] =
    (
      dbKindOpt,
      databaseUrlOpt,
      sqlDirOpt,
      Opts.flag("dry-run", help = "Print SQL without executing").orFalse,
      Opts.flag("verbose", help = "Echo each statement before running").orFalse,
      Opts.flag("continue-on-error", help = "Continue processing after SQL errors").orFalse,
      retriesOpt,
      backoffOpt,
      oracleWalletOpt,
      oracleAliasOpt,
      oracleUserOpt,
      oraclePasswordFileOpt,
      Opts.flag("json", help = "Print machine-readable JSON").orFalse
    ).mapN {
      (
          dbKind,
          databaseUrl,
          sqlDir,
          dryRun,
          verbose,
          continueOnError,
          retries,
          backoff,
          oracleWallet,
          oracleAlias,
          oracleUser,
          oraclePasswordFile,
          json
      ) =>
        MigratorConfig(
          dbKind = dbKind,
          databaseUrl = databaseUrl.orElse(env.get("DATABASE_URL")).orElse(env.get("ORACLE_JDBC_URL")),
          sqlDir = sqlDir.getOrElse(defaultSqlDir(dbKind)),
          dryRun = dryRun,
          verbose = verbose,
          continueOnError = continueOnError,
          connectRetries = retries,
          connectRetryBackoff = backoff,
          oracleWallet = oracleWallet.orElse(env.get("TNS_ADMIN").map(Paths.get(_))),
          oracleTnsAlias = oracleAlias.orElse(env.get("ORACLE_CONN")),
          oracleUser = oracleUser.orElse(env.get("ORACLE_USER")),
          oraclePasswordFile = oraclePasswordFile.orElse(env.get("ORACLE_PASS_FILE").map(Paths.get(_))),
          json = json
        )
    }

  private val commandOpts: Opts[CliCommand] =
    Opts.subcommand("apply", "Apply pending objects")(Opts(CliCommand.Apply))
      .orElse(Opts.subcommand("validate", "Parse and validate SQL files")(Opts(CliCommand.Validate)))
      .orElse(Opts.subcommand("list", "Print discovered SQL files in apply order")(Opts(CliCommand.ListFiles)))
      .orElse(Opts.subcommand("status", "Print schema_control object status")(Opts(CliCommand.Status)))
      .orElse(
        Opts.subcommand("rollback", "Execute rollback SQL for a tracked object") {
          Opts.argument[String]("object").map(CliCommand.Rollback.apply)
        }
      )
      .orElse(
        Opts.subcommand("ready", "Check schema readiness") {
          Opts.flag("strict", help = "Exit non-zero when schema is not ready").orFalse.map(CliCommand.Ready.apply)
        }
      )
      .orElse(Opts.subcommand("check-connection", "Open and validate a database connection")(Opts(CliCommand.CheckConnection)))
      .orElse(Opts(CliCommand.Apply))

  val opts: Opts[(MigratorConfig, CliCommand)] =
    (configOpts, commandOpts).tupled

  private def defaultSqlDir(dbKind: DbKind): Path =
    dbKind match
      case DbKind.Postgres => Paths.get("./sql")
      case DbKind.Oracle   => Paths.get("./sql/oracle")

sealed trait CliCommand

object CliCommand:
  case object Apply extends CliCommand
  case object Validate extends CliCommand
  case object ListFiles extends CliCommand
  case object Status extends CliCommand
  final case class Rollback(objectName: String) extends CliCommand
  final case class Ready(strict: Boolean) extends CliCommand
  case object CheckConnection extends CliCommand
