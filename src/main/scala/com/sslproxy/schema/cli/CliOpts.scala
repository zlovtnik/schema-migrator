package com.sslproxy.schema.cli

import cats.syntax.all.*
import com.monovore.decline.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig, MongoConfig, ServerConfig}

import java.nio.file.{Path, Paths}
import java.util.Locale
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
    Opts
      .option[Int]("connect-retries", help = "Connection retry count")
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

  private val hostOpt: Opts[String] =
    Opts
      .option[String]("host", help = "HTTP server bind host")
      .withDefault(env.getOrElse("BEDROCK_HTTP_HOST", "127.0.0.1"))

  private val portOpt: Opts[Int] =
    Opts
      .option[Int]("port", help = "HTTP server bind port")
      .withDefault(envInt("BEDROCK_HTTP_PORT", 8080))
      .validate("port must be between 1 and 65535")(port => port >= 1 && port <= 65535)

  private val corsOriginsOpt: Opts[Set[String]] =
    Opts
      .option[String]("cors-origins", help = "Comma-separated allowed CORS origins")
      .orNone
      .map(_.orElse(env.get("BEDROCK_CORS_ORIGINS")).fold(Set("http://localhost:5173"))(commaSet))

  private val encryptKeyOpt: Opts[Option[String]] =
    Opts.option[String]("encrypt-key", help = "Base64 AES-256-GCM response encryption key").orNone

  private val jwtSecretOpt: Opts[String] =
    Opts
      .option[String]("jwt-secret", help = "JWT HS256 signing secret")
      .orNone
      .map(_.orElse(env.get("BEDROCK_JWT_SECRET")).flatMap(nonBlank).getOrElse(""))

  private val devAuthSecretOpt: Opts[String] =
    Opts
      .option[String]("dev-auth-secret", help = "Development secret accepted by /api/auth/token")
      .orNone
      .map(_.orElse(env.get("BEDROCK_DEV_AUTH_SECRET")).flatMap(nonBlank).getOrElse(""))

  private val apiBearerTokenOpt: Opts[Option[String]] =
    Opts
      .option[String]("api-bearer-token", help = "Static bearer token accepted by protected HTTP API routes")
      .orNone
      .map(_.orElse(env.get("BEDROCK_API_BEARER_TOKEN")).flatMap(nonBlank))

  private val patchStageDirOpt: Opts[Path] =
    Opts
      .option[Path]("patch-stage-dir", help = "Directory used to stage uploaded patch files")
      .orNone
      .map(_.orElse(env.get("BEDROCK_PATCH_STAGE_DIR").map(Paths.get(_))).getOrElse(defaultPatchStageDir))

  private val dbTestAllowedHostsOpt: Opts[Set[String]] =
    Opts
      .option[String]("db-test-allowed-hosts", help = "Comma-separated JDBC hosts allowed for /targets/test")
      .orNone
      .map(_.orElse(env.get("BEDROCK_DB_TEST_ALLOWED_HOSTS")).fold(Set.empty[String])(commaSet).map(_.toLowerCase(Locale.ROOT)))

  private val mongoUriOpt: Opts[Option[String]] =
    Opts
      .option[String]("mongo-uri", help = "MongoDB connection URI for persisted HTTP API targets")
      .orNone
      .map(_.orElse(env.get("BEDROCK_MONGO_URI")).flatMap(nonBlank))

  private val mongoDatabaseOpt: Opts[Option[String]] =
    Opts
      .option[String]("mongo-database", help = "MongoDB database for persisted HTTP API targets")
      .orNone
      .map(_.orElse(env.get("BEDROCK_MONGO_DATABASE")).flatMap(nonBlank))

  private val mongoTargetsCollectionOpt: Opts[Option[String]] =
    Opts
      .option[String]("mongo-targets-collection", help = "MongoDB collection for persisted HTTP API targets")
      .orNone
      .map(_.orElse(env.get("BEDROCK_MONGO_TARGETS_COLLECTION")).flatMap(nonBlank))

  private val sqlFilesCollectionOpt: Opts[String] =
    Opts
      .option[String]("sql-files-collection", help = "MongoDB collection for uploaded SQL files")
      .orNone
      .map(_.orElse(env.get("BEDROCK_SQL_FILES_COLLECTION")).flatMap(nonBlank).getOrElse("sql_files"))

  private val patchesCollectionOpt: Opts[String] =
    Opts
      .option[String]("patches-collection", help = "MongoDB collection for migration patches")
      .orNone
      .map(_.orElse(env.get("BEDROCK_PATCHES_COLLECTION")).flatMap(nonBlank).getOrElse("patches"))

  private val runsCollectionOpt: Opts[String] =
    Opts
      .option[String]("runs-collection", help = "MongoDB collection for migration runs")
      .orNone
      .map(_.orElse(env.get("BEDROCK_RUNS_COLLECTION")).flatMap(nonBlank).getOrElse("runs"))

  private val validationsCollectionOpt: Opts[String] =
    Opts
      .option[String]("validations-collection", help = "MongoDB collection for validation results")
      .orNone
      .map(_.orElse(env.get("BEDROCK_VALIDATIONS_COLLECTION")).flatMap(nonBlank).getOrElse("validations"))

  private val snapshotsCollectionOpt: Opts[String] =
    Opts
      .option[String]("snapshots-collection", help = "MongoDB collection for SQL manifest snapshots")
      .orNone
      .map(_.orElse(env.get("BEDROCK_SNAPSHOTS_COLLECTION")).flatMap(nonBlank).getOrElse("snapshots"))

  private val auditCollectionOpt: Opts[String] =
    Opts
      .option[String]("audit-collection", help = "MongoDB collection for audit events")
      .orNone
      .map(_.orElse(env.get("BEDROCK_AUDIT_COLLECTION")).flatMap(nonBlank).getOrElse("audit_events"))

  private val serverOpts: Opts[ServerConfig] =
    (
      hostOpt,
      portOpt,
      corsOriginsOpt,
      encryptKeyOpt,
      jwtSecretOpt,
      devAuthSecretOpt,
      apiBearerTokenOpt,
      dbTestAllowedHostsOpt,
      patchStageDirOpt,
      mongoUriOpt,
      mongoDatabaseOpt,
      mongoTargetsCollectionOpt,
      sqlFilesCollectionOpt,
      patchesCollectionOpt,
      runsCollectionOpt,
      validationsCollectionOpt,
      snapshotsCollectionOpt,
      auditCollectionOpt
    ).mapN {
      (
        host,
        port,
        corsOrigins,
        encryptKey,
        jwtSecret,
        devAuthSecret,
        apiBearerToken,
        dbTestAllowedHosts,
        patchStageDir,
        mongoUri,
        mongoDatabase,
        mongoTargetsCollection,
        sqlFilesCollection,
        patchesCollection,
        runsCollection,
        validationsCollection,
        snapshotsCollection,
        auditCollection
      ) =>
        val mongoResult = mongoConfigFromOptions(mongoUri, mongoDatabase, mongoTargetsCollection)
        ServerConfig(
          host = host,
          port = port,
          corsOrigins = corsOrigins,
          encryptKeyBase64 = encryptKey.flatMap(nonBlank).orElse(env.get("BEDROCK_ENCRYPT_KEY").flatMap(nonBlank)),
          jwtSecret = jwtSecret,
          devAuthSecret = devAuthSecret,
          apiBearerToken = apiBearerToken,
          dbTestAllowedHosts = dbTestAllowedHosts,
          patchStageDir = patchStageDir,
          mongo = mongoResult.toOption.flatten,
          sqlFilesCollection = sqlFilesCollection,
          patchesCollection = patchesCollection,
          runsCollection = runsCollection,
          validationsCollection = validationsCollection,
          snapshotsCollection = snapshotsCollection,
          auditCollection = auditCollection,
          mongoConfigError = mongoResult.swap.toOption
        )
    }

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
      Opts.flag("json", help = "Print machine-readable JSON").orFalse,
      serverOpts
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
        json,
        serverConfig
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
          json = json,
          server = serverConfig
        )
    }

  private val commandOpts: Opts[CliCommand] =
    Opts
      .subcommand("apply", "Apply pending objects")(Opts(CliCommand.Apply))
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
      .orElse(
        Opts.subcommand("check-connection", "Open and validate a database connection")(Opts(CliCommand.CheckConnection))
      )
      .orElse(Opts.subcommand("serve", "Start the schema migrator HTTP API")(Opts(CliCommand.Serve)))
      .orElse(Opts(CliCommand.Apply))

  val opts: Opts[(MigratorConfig, CliCommand)] =
    (configOpts, Opts.flag("serve", help = "Start the schema migrator HTTP API").orFalse, commandOpts).mapN {
      (config, serve, command) =>
        (config, if serve then CliCommand.Serve else command)
    }

  private def defaultSqlDir(dbKind: DbKind): Path =
    dbKind match
      case DbKind.Postgres => Paths.get("./sql")
      case DbKind.Oracle => Paths.get("./sql/oracle")

  private def commaSet(value: String): Set[String] =
    value.split(",").map(_.trim).filter(_.nonEmpty).toSet

  private def nonBlank(value: String): Option[String] =
    Option(value.trim).filter(_.nonEmpty)

  private def mongoConfigFromOptions(
    mongoUri: Option[String],
    mongoDatabase: Option[String],
    mongoTargetsCollection: Option[String]
  ): Either[String, Option[MongoConfig]] =
    val provided = List(
      mongoUri.map(_ => "BEDROCK_MONGO_URI"),
      mongoDatabase.map(_ => "BEDROCK_MONGO_DATABASE"),
      mongoTargetsCollection.map(_ => "BEDROCK_MONGO_TARGETS_COLLECTION")
    ).flatten
    if provided.isEmpty then Right(None)
    else
      val missing = List(
        Option.when(mongoUri.isEmpty)("BEDROCK_MONGO_URI"),
        Option.when(mongoDatabase.isEmpty)("BEDROCK_MONGO_DATABASE"),
        Option.when(mongoTargetsCollection.isEmpty)("BEDROCK_MONGO_TARGETS_COLLECTION")
      ).flatten
      if missing.nonEmpty then Left(s"Mongo configuration is incomplete; missing ${missing.mkString(", ")}")
      else Right(Some(MongoConfig(mongoUri.get, mongoDatabase.get, mongoTargetsCollection.get)))

  private def envInt(name: String, defaultValue: Int): Int =
    env
      .get(name)
      .flatMap(value => Either.catchNonFatal(value.toInt).toOption)
      .getOrElse(defaultValue)

  private def defaultPatchStageDir: Path =
    Paths.get(sys.props.getOrElse("java.io.tmpdir", "."), "schema-migrator-patches")

sealed trait CliCommand

object CliCommand:
  case object Apply extends CliCommand
  case object Validate extends CliCommand
  case object ListFiles extends CliCommand
  case object Status extends CliCommand
  final case class Rollback(objectName: String) extends CliCommand
  final case class Ready(strict: Boolean) extends CliCommand
  case object CheckConnection extends CliCommand
  case object Serve extends CliCommand
