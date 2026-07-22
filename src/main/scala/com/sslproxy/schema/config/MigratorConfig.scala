package com.sslproxy.schema.config

import java.nio.file.{Files, Path}
import java.net.URI
import java.util.Base64
import java.util.Locale
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

enum DbKind:
  case Postgres, Oracle, TiDB

object DbKind:
  def parse(value: String): Either[String, DbKind] =
    value.trim.toLowerCase match
      case "postgres" | "postgresql" => Right(Postgres)
      case "oracle" => Right(Oracle)
      case "tidb" | "mysql" => Right(TiDB)
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
  tidbUser: Option[String] = None,
  tidbPassword: Option[String] = None,
  json: Boolean,
  server: ServerConfig,
  customer: Option[String] = None
):
  /** Validate configuration at parse time, returning an error message
    * for invalid or missing combinations that would otherwise fail
    * at the first database call.
    */
  def validate: Either[String, Unit] =
    for
      _ <- validateDbSpecific()
      _ <- validateCustomer()
      _ <- validateSqlDir()
    yield ()

  def validateSqlOnly: Either[String, Unit] =
    validateCustomer().flatMap(_ => validateSqlDir())

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
      case DbKind.TiDB if databaseUrl.isEmpty =>
        Left("TiDB requires --database-url (or DATABASE_URL)")
      case _ => Right(())

  private def validateSqlDir(): Either[String, Unit] =
    if Files.notExists(sqlDir) then Left(s"sql directory '$sqlDir' does not exist or is not accessible")
    else if !Files.isDirectory(sqlDir) then Left(s"path '$sqlDir' is not a directory")
    else Right(())

  private def validateCustomer(): Either[String, Unit] =
    customer match
      case None => Right(())
      case Some(value) if value.trim.isEmpty => Left("customer must not be empty")
      case Some(value) if value.contains("/") || value.contains("\\") || value == "." || value == ".." =>
        Left("customer must be a single directory name")
      case Some(_) => Right(())

final case class StateStoreConfig(
  url: String,
  user: String,
  password: String,
  poolSize: Int = 10
):
  def validate: Either[String, Unit] =
    if url.trim.isEmpty then Left("BEDROCK_STATE_DB_URL must not be empty")
    else if !url.trim.startsWith("jdbc:mysql://") then
      Left("BEDROCK_STATE_DB_URL must be a JDBC MySQL/TiDB URL starting with jdbc:mysql://")
    else if user.trim.isEmpty then Left("BEDROCK_STATE_DB_USER must not be empty")
    else if user.trim.equalsIgnoreCase("root") then Left("BEDROCK_STATE_DB_USER must be a dedicated non-root TiDB user")
    else if password.trim.isEmpty then Left("BEDROCK_STATE_DB_PASSWORD must not be empty")
    else if poolSize < 1 then Left("BEDROCK_STATE_DB_POOL_SIZE must be at least 1")
    else validateJdbcUrl

  private def validateJdbcUrl: Either[String, Unit] =
    Try(URI.create(url.trim.stripPrefix("jdbc:"))).toEither
      .left
      .map(_ => "BEDROCK_STATE_DB_URL must be a valid JDBC MySQL/TiDB URL")
      .flatMap { uri =>
        val database = Option(uri.getPath).getOrElse("").stripPrefix("/")
        val params = Option(uri.getRawQuery)
          .toList
          .flatMap(_.split("&").toList)
          .flatMap { entry =>
            entry.split("=", 2).toList match
              case key :: value :: Nil => Some(key.toLowerCase(Locale.ROOT) -> value)
              case _ => None
          }
          .toMap
        if Option(uri.getHost).forall(_.trim.isEmpty) then Left("BEDROCK_STATE_DB_URL must include a TiDB host")
        else if Set("localhost", "127.0.0.1", "::1").contains(uri.getHost.toLowerCase(Locale.ROOT)) then
          Left("BEDROCK_STATE_DB_URL must use an external non-loopback TiDB host")
        else if Option(uri.getUserInfo).nonEmpty then
          Left("BEDROCK_STATE_DB_URL must not contain inline credentials")
        else if database != "schema_migrator" then
          Left("BEDROCK_STATE_DB_URL must select the schema_migrator database")
        else if !params.get("sslmode").exists(_.equalsIgnoreCase("VERIFY_IDENTITY")) then
          Left("BEDROCK_STATE_DB_URL must set sslMode=VERIFY_IDENTITY")
        else Right(())
      }

final case class ServerConfig(
  host: String,
  port: Int,
  corsOrigins: Set[String],
  encryptKeyBase64: Option[String],
  jwtSecret: String,
  devAuthSecret: String,
  devAuthEnabled: Boolean = false,
  keycloakEnabled: Boolean = false,
  keycloakIssuer: Option[String] = None,
  keycloakJwksUri: Option[String] = None,
  keycloakClientId: Option[String] = None,
  keycloakAudience: Option[String] = None,
  dbTestAllowedHosts: Set[String],
  patchStageDir: Path,
  apiBearerToken: Option[String] = None,
  stateStore: Option[StateStoreConfig] = None,
  repoCacheDir: Path = Path.of(sys.props.getOrElse("java.io.tmpdir", "."), "schema-migrator-repos"),
  repoCloneTimeoutSeconds: Int = 60,
  stateStoreConfigError: Option[String] = None
):
  def validate: Either[String, Unit] =
    if host.trim.isEmpty then Left("server host must not be empty")
    else if port < 1 || port > 65535 then Left("server port must be between 1 and 65535")
    else if encryptKeyBase64.forall(_.trim.isEmpty) then Left("BEDROCK_ENCRYPT_KEY must not be empty")
    else if jwtSecret.trim.isEmpty then Left("BEDROCK_JWT_SECRET must not be empty")
    else if devAuthEnabled && devAuthSecret.trim.isEmpty then
      Left("BEDROCK_DEV_AUTH_SECRET must not be empty when dev auth is enabled")
    else if keycloakEnabled && keycloakIssuer.forall(_.trim.isEmpty) then
      Left("BEDROCK_KEYCLOAK_ISSUER must not be empty when Keycloak auth is enabled")
    else if keycloakEnabled && keycloakAudience.forall(_.trim.isEmpty) && keycloakClientId.forall(_.trim.isEmpty) then
      Left("BEDROCK_KEYCLOAK_AUDIENCE or BEDROCK_KEYCLOAK_CLIENT_ID must be set when Keycloak auth is enabled")
    else if apiBearerToken.forall(_.trim.isEmpty) then Left("BEDROCK_API_BEARER_TOKEN must not be empty")
    else if repoCloneTimeoutSeconds < 1 then Left("BEDROCK_REPO_CLONE_TIMEOUT_SECONDS must be at least 1")
    else
      validateEncryptKeyBase64()
        .flatMap(_ => validateStateStore())
        .flatMap(_ => validatePatchStageDir())
        .flatMap(_ => validateRepoCacheDir())

  def stateStoreConfig: Either[String, StateStoreConfig] =
    stateStore.toRight(
      stateStoreConfigError.getOrElse(
        "BEDROCK_STATE_DB_URL, BEDROCK_STATE_DB_USER, and BEDROCK_STATE_DB_PASSWORD must be set"
      )
    )

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

  private def validateStateStore(): Either[String, Unit] =
    stateStoreConfig.flatMap(_.validate)

  private def validatePatchStageDir(): Either[String, Unit] =
    validateWritableDirectory(patchStageDir, "patch stage")

  private def validateRepoCacheDir(): Either[String, Unit] =
    validateWritableDirectory(repoCacheDir, "repo cache")

  private def validateWritableDirectory(path: Path, label: String): Either[String, Unit] =
    try
      if Files.exists(path) && !Files.isDirectory(path) then Left(s"$label path '$path' is not a directory")
      else
        if Files.notExists(path) then
          Files.createDirectories(path)
          ()
        if !Files.isDirectory(path) then Left(s"$label path '$path' is not a directory")
        else if !Files.isWritable(path) then Left(s"$label directory '$path' is not writable")
        else Right(())
    catch
      case error: Exception =>
        Left(s"$label directory '$path' is not usable: ${error.getMessage}")
