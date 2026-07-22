package com.sslproxy.schema.cli

import cats.syntax.all.*
import com.monovore.decline.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig, ServerConfig, StateStoreConfig}

import java.nio.file.{Path, Paths}
import java.util.Locale
import scala.concurrent.duration.*

object CliOpts:
  private given Argument[DbKind] =
    Argument.from("postgres|oracle|tidb|mysql")(value => DbKind.parse(value).toValidatedNel)

  private given Argument[Path] =
    Argument.from("path")(value => Either.catchNonFatal(Paths.get(value)).leftMap(_.getMessage).toValidatedNel)

  private def env: Map[String, String] = sys.env

  private val dbKindOpt: Opts[DbKind] =
    Opts
      .option[DbKind]("db-kind", help = "Database engine: postgres, oracle, or tidb")
      .withDefault(env.get("SCHEMA_MIGRATOR_DB_KIND").flatMap(DbKind.parse(_).toOption).getOrElse(DbKind.Postgres))

  private val sqlDirOpt: Opts[Option[Path]] =
    Opts.option[Path]("sql-dir", help = "Root SQL directory").orNone

  private val customerOpt: Opts[Option[String]] =
    Opts.option[String]("customer", help = "Customer overlay name under customers/<name>").orNone

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

  private val tidbUserOpt: Opts[Option[String]] =
    Opts.option[String]("tidb-user", help = "TiDB/MySQL username").orNone

  private val tidbPasswordOpt: Opts[Option[String]] =
    Opts.option[String]("tidb-password", help = "TiDB/MySQL password").orNone

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
      .map(_.orElse(env.get("BEDROCK_CORS_ORIGINS")).fold(defaultCorsOrigins)(commaSet))

  private val defaultCorsOrigins: Set[String] =
    Set(
      "http://localhost:5173",
      "http://127.0.0.1:5173",
      "http://localhost:4174",
      "http://127.0.0.1:4174"
    )

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

  private val devAuthEnabledOpt: Opts[Boolean] =
    Opts
      .flag("dev-auth-enabled", help = "Enable the development /api/auth/token endpoint")
      .orNone
      .map(_.isDefined || envBoolean("BEDROCK_DEV_AUTH_ENABLED", false))

  private val keycloakEnabledOpt: Opts[Boolean] =
    Opts
      .flag("keycloak-enabled", help = "Enable Keycloak RS256 bearer token verification")
      .orNone
      .map(_.isDefined || envBoolean("BEDROCK_KEYCLOAK_ENABLED", false))

  private val keycloakIssuerOpt: Opts[Option[String]] =
    Opts
      .option[String]("keycloak-issuer", help = "Keycloak realm issuer URL")
      .orNone
      .map(_.orElse(env.get("BEDROCK_KEYCLOAK_ISSUER")).flatMap(nonBlank))

  private val keycloakJwksUriOpt: Opts[Option[String]] =
    Opts
      .option[String]("keycloak-jwks-uri", help = "Keycloak JWKS certificate URL")
      .orNone
      .map(_.orElse(env.get("BEDROCK_KEYCLOAK_JWKS_URI")).flatMap(nonBlank))

  private val keycloakClientIdOpt: Opts[Option[String]] =
    Opts
      .option[String]("keycloak-client-id", help = "Keycloak client ID used for resource_access role lookup")
      .orNone
      .map(_.orElse(env.get("BEDROCK_KEYCLOAK_CLIENT_ID")).flatMap(nonBlank))

  private val keycloakAudienceOpt: Opts[Option[String]] =
    Opts
      .option[String]("keycloak-audience", help = "Optional accepted Keycloak audience or azp value")
      .orNone
      .map(_.orElse(env.get("BEDROCK_KEYCLOAK_AUDIENCE")).flatMap(nonBlank))

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

  private val repoCacheDirOpt: Opts[Path] =
    Opts
      .option[Path]("repo-cache-dir", help = "Directory used for temporary repository clones")
      .orNone
      .map(_.orElse(env.get("BEDROCK_REPO_CACHE_DIR").map(Paths.get(_))).getOrElse(defaultRepoCacheDir))

  private val repoCloneTimeoutSecondsOpt: Opts[Int] =
    Opts
      .option[Int]("repo-clone-timeout-seconds", help = "Repository clone timeout in seconds")
      .orNone
      .map(
        _.orElse(
          env.get("BEDROCK_REPO_CLONE_TIMEOUT_SECONDS").flatMap(value => Either.catchNonFatal(value.toInt).toOption)
        ).getOrElse(60)
      )
      .validate("repo-clone-timeout-seconds must be at least 1")(_ >= 1)

  private val dbTestAllowedHostsOpt: Opts[Set[String]] =
    Opts
      .option[String]("db-test-allowed-hosts", help = "Comma-separated JDBC hosts allowed for /targets/test")
      .orNone
      .map(
        _.orElse(env.get("BEDROCK_DB_TEST_ALLOWED_HOSTS"))
          .fold(Set.empty[String])(commaSet)
          .map(_.toLowerCase(Locale.ROOT))
      )

  private val stateStoreOpt: Opts[Either[String, Option[StateStoreConfig]]] =
    (
      Opts.option[String]("state-db-url", help = "JDBC MySQL/TiDB URL for persisted HTTP API state").orNone,
      Opts.option[String]("state-db-user", help = "Dedicated TiDB user for persisted HTTP API state").orNone,
      Opts.option[String]("state-db-password", help = "TiDB password for persisted HTTP API state").orNone,
      Opts.option[Int]("state-db-pool-size", help = "TiDB state connection pool size").orNone
    ).mapN { (urlArg, userArg, passwordArg, poolArg) =>
      stateStoreConfigFromOptions(
        urlArg.orElse(env.get("BEDROCK_STATE_DB_URL")).flatMap(nonBlank),
        userArg.orElse(env.get("BEDROCK_STATE_DB_USER")).flatMap(nonBlank),
        passwordArg.orElse(env.get("BEDROCK_STATE_DB_PASSWORD")).flatMap(nonBlank),
        poolArg.orElse(env.get("BEDROCK_STATE_DB_POOL_SIZE").flatMap(value => Either.catchNonFatal(value.toInt).toOption)).getOrElse(10)
      )
    }

  private val serverOpts: Opts[ServerConfig] =
    (
      hostOpt,
      portOpt,
      corsOriginsOpt,
      encryptKeyOpt,
      jwtSecretOpt,
      devAuthSecretOpt,
      devAuthEnabledOpt,
      keycloakEnabledOpt,
      keycloakIssuerOpt,
      keycloakJwksUriOpt,
      keycloakClientIdOpt,
      keycloakAudienceOpt,
      apiBearerTokenOpt,
      dbTestAllowedHostsOpt,
      patchStageDirOpt,
      repoCacheDirOpt,
      repoCloneTimeoutSecondsOpt,
      stateStoreOpt
    ).mapN {
      (
        host,
        port,
        corsOrigins,
        encryptKey,
        jwtSecret,
        devAuthSecret,
        devAuthEnabled,
        keycloakEnabled,
        keycloakIssuer,
        keycloakJwksUri,
        keycloakClientId,
        keycloakAudience,
        apiBearerToken,
        dbTestAllowedHosts,
        patchStageDir,
        repoCacheDir,
        repoCloneTimeoutSeconds,
        stateStoreResult
      ) =>
        ServerConfig(
          host = host,
          port = port,
          corsOrigins = corsOrigins,
          encryptKeyBase64 = encryptKey.flatMap(nonBlank).orElse(env.get("BEDROCK_ENCRYPT_KEY").flatMap(nonBlank)),
          jwtSecret = jwtSecret,
          devAuthSecret = devAuthSecret,
          devAuthEnabled = devAuthEnabled,
          keycloakEnabled = keycloakEnabled,
          keycloakIssuer = keycloakIssuer,
          keycloakJwksUri = keycloakJwksUri,
          keycloakClientId = keycloakClientId,
          keycloakAudience = keycloakAudience,
          apiBearerToken = apiBearerToken,
          dbTestAllowedHosts = dbTestAllowedHosts,
          patchStageDir = patchStageDir,
          stateStore = stateStoreResult.toOption.flatten,
          repoCacheDir = repoCacheDir,
          repoCloneTimeoutSeconds = repoCloneTimeoutSeconds,
          stateStoreConfigError = stateStoreResult.swap.toOption
        )
    }

  private val configOpts: Opts[MigratorConfig] =
    (
      dbKindOpt,
      databaseUrlOpt,
      sqlDirOpt,
      customerOpt,
      Opts.flag("dry-run", help = "Print SQL without executing").orFalse,
      Opts.flag("verbose", help = "Echo each statement before running").orFalse,
      Opts.flag("continue-on-error", help = "Continue processing after SQL errors").orFalse,
      retriesOpt,
      backoffOpt,
      oracleWalletOpt,
      oracleAliasOpt,
      oracleUserOpt,
      oraclePasswordFileOpt,
      tidbUserOpt,
      tidbPasswordOpt,
      Opts.flag("json", help = "Print machine-readable JSON").orFalse,
      serverOpts
    ).mapN {
      (
        dbKind,
        databaseUrl,
        sqlDir,
        customer,
        dryRun,
        verbose,
        continueOnError,
        retries,
        backoff,
        oracleWallet,
        oracleAlias,
        oracleUser,
        oraclePasswordFile,
        tidbUser,
        tidbPassword,
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
          tidbUser = tidbUser.orElse(env.get("TIDB_USER")).flatMap(nonBlank),
          tidbPassword = tidbPassword.orElse(env.get("TIDB_PASSWORD")).flatMap(nonBlank),
          json = json,
          server = serverConfig,
          customer = customer.flatMap(nonBlank)
        )
    }

  private val commandOpts: Opts[CliCommand] =
    Opts
      .subcommand("apply", "Apply pending objects")(Opts(CliCommand.Apply))
      .orElse(Opts.subcommand("validate", "Parse and validate SQL files")(Opts(CliCommand.Validate)))
      .orElse(Opts.subcommand("list", "Print discovered SQL files in apply order")(Opts(CliCommand.ListFiles)))
      .orElse(
        Opts.subcommand("generate-baseline", "Generate _generated_baseline.sql from manifest order")(
          Opts(CliCommand.GenerateBaseline)
        )
      )
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
        Opts.subcommand(
          "drift-check",
          "Compare live Postgres catalog with the manifest and record drift registry rows"
        )(Opts(CliCommand.DriftCheck))
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
      case DbKind.TiDB => Paths.get("./sql/tidb")

  private def commaSet(value: String): Set[String] =
    value.split(",").map(_.trim).filter(_.nonEmpty).toSet

  private def nonBlank(value: String): Option[String] =
    Option(value.trim).filter(_.nonEmpty)

  private def stateStoreConfigFromOptions(
    url: Option[String],
    user: Option[String],
    password: Option[String],
    poolSize: Int
  ): Either[String, Option[StateStoreConfig]] =
    val provided = List(
      url.map(_ => "BEDROCK_STATE_DB_URL"),
      user.map(_ => "BEDROCK_STATE_DB_USER"),
      password.map(_ => "BEDROCK_STATE_DB_PASSWORD")
    ).flatten
    if provided.isEmpty then Right(None)
    else
      val missing = List(
        Option.when(url.isEmpty)("BEDROCK_STATE_DB_URL"),
        Option.when(user.isEmpty)("BEDROCK_STATE_DB_USER"),
        Option.when(password.isEmpty)("BEDROCK_STATE_DB_PASSWORD")
      ).flatten
      if missing.nonEmpty then Left(s"State database configuration is incomplete; missing ${missing.mkString(", ")}")
      else Right(Some(StateStoreConfig(url.get, user.get, password.get, poolSize)))

  private def envInt(name: String, defaultValue: Int): Int =
    env
      .get(name)
      .flatMap(value => Either.catchNonFatal(value.toInt).toOption)
      .getOrElse(defaultValue)

  private def envBoolean(name: String, defaultValue: Boolean): Boolean =
    env
      .get(name)
      .flatMap(value => Either.catchNonFatal(value.trim.toBoolean).toOption)
      .getOrElse(defaultValue)

  private def defaultPatchStageDir: Path =
    Paths.get(sys.props.getOrElse("java.io.tmpdir", "."), "schema-migrator-patches")

  private def defaultRepoCacheDir: Path =
    Paths.get(sys.props.getOrElse("java.io.tmpdir", "."), "schema-migrator-repos")

sealed trait CliCommand

object CliCommand:
  case object Apply extends CliCommand
  case object Validate extends CliCommand
  case object ListFiles extends CliCommand
  case object GenerateBaseline extends CliCommand
  case object Status extends CliCommand
  final case class Rollback(objectName: String) extends CliCommand
  final case class Ready(strict: Boolean) extends CliCommand
  case object DriftCheck extends CliCommand
  case object CheckConnection extends CliCommand
  case object Serve extends CliCommand
