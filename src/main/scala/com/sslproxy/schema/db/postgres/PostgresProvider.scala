package com.sslproxy.schema.db.postgres

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.validation.RollbackValidator
import com.sslproxy.schema.db.{ApplyLog, DbProvider, DbSession, JdbcConnectionConfig, JdbcSupport, LockManager, SchemaControlStore}
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.engine.*
import com.sslproxy.schema.error.MigratorError

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, ResultSet, SQLException}
import scala.jdk.CollectionConverters.*

final class PostgresProvider(config: JdbcConnectionConfig) extends DbProvider:
  override val dialect: SqlDialect = SqlDialect.Postgres

  override def session: Resource[IO, DbSession] =
    JdbcSupport.connection(config).map(PostgresSession(_))

final class PostgresSession(connection: Connection) extends DbSession:
  import JdbcSupport.*

  private val lockManager  = LockManager.postgres(connection, PostgresSession.applyLockKey, PostgresSession.applyLockNamespace)
  private val store        = SchemaControlStore.postgres(connection)
  private val applyLog     = ApplyLog.postgres(connection, appliedBy)

  override def checkConnection: IO[Unit] =
    IO.blocking(queryOne(connection, "select 1")(_.getInt(1))).void

  override def bootstrap: IO[Unit] =
    IO.blocking(executeStatement(connection, PostgresStatements.bootstrapSql)).adaptError {
      case error: SQLException => MigratorError.Apply(s"schema_control bootstrap failed: ${error.getMessage}", error)
    }

  override def acquireLock: IO[Unit]           = lockManager.acquire
  override def releaseLock: IO[Unit]           = lockManager.release
  override def prepare(objects: List[SchemaObject]): IO[List[PreparedObject]] = store.prepare(objects)
  override def fetchStatus: IO[List[ObjectStatus]]         = store.fetchStatus
  override def fetchReady: IO[SchemaReadyStatus]           = store.fetchReady
  override def checkReady: IO[Boolean]                     = fetchReady.map(_.ready)

  override def recordSkipped(prepared: PreparedObject): IO[Unit] =
    applyLog.recordSkipped(prepared.objectDef, prepared.oldSha256)

  override def executeObject(prepared: PreparedObject): IO[Unit] =
    if prepared.objectDef.transactional then executeTransactional(prepared)
    else executeNonTransactional(prepared)

  private def executeTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      IO.blocking(connection.setAutoCommit(false)) *>
        IO.blocking(executeStatement(connection, prepared.objectDef.rawSql))
          .attempt
          .flatMap {
              case Right(()) =>
                (applyLog.recordApplied(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started)) *>
                  IO.blocking(connection.commit()))
                  .handleErrorWith(failure =>
                    IO.blocking(connection.rollback()).attempt.flatMap {
                      case Right(())                          => IO.raiseError(failure)
                      case Left(rollbackErr: SQLException)    => failure.addSuppressed(rollbackErr); IO.raiseError(failure)
                      case Left(rollbackErr)                  => IO.raiseError(new RuntimeException("rollback failed", rollbackErr).initCause(failure))
                    }
                  )
             case Left(error: SQLException) =>
               val formatted = JdbcSupport.sqlError(prepared.objectDef.sourceFile, error, "postgres")
               IO.blocking(connection.rollback()) *>
                 applyLog.recordFailed(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started), formatted)
                   .handleError(logError => { error.addSuppressed(logError); IO.unit }) *>
                 IO.raiseError(MigratorError.Apply(formatted, error))
            case Left(other) =>
              IO.blocking(connection.rollback()) *>
                IO.raiseError(other)
          }
          .guarantee(IO.blocking(connection.setAutoCommit(true)))
    }

  private def executeNonTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      IO.blocking(connection.setAutoCommit(true)) *>
        IO.blocking(executeStatement(connection, prepared.objectDef.rawSql))
          .attempt
          .flatMap {
             case Right(()) =>
               applyLog.recordApplied(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started))
                 .adaptError { case error: SQLException =>
                   MigratorError.NonRetryableApply(
                     s"${prepared.objectDef.sourceFile}: applied SQL but failed to record migration state: ${error.getMessage}",
                     error
                   )
                 }
             case Left(error: SQLException) =>
               val formatted = JdbcSupport.sqlError(prepared.objectDef.sourceFile, error, "postgres")
               applyLog.recordFailed(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started), formatted)
                 .handleError(logError => { error.addSuppressed(logError); IO.unit }) *>
                 IO.raiseError(MigratorError.Apply(formatted, error))
            case Left(other) =>
              IO.raiseError(other)
          }
    }

  override def rollbackObject(sqlDir: Path, objectName: String): IO[Unit] =
    store.fetchRollbackTarget(objectName).flatMap { target =>
      val pseudoFile = com.sslproxy.schema.discovery.SqlFile(
        folder = target.kind,
        path = sqlDir.resolve(target.sourceFile),
        name = target.sourceFile,
        relativePath = target.sourceFile
      )
      val rollbackPath = RollbackValidator.resolveExistingRollbackPath(pseudoFile, target.rollbackFile).getOrElse {
        throw MigratorError.Apply(s"${target.objectName} declares rollback file '${target.rollbackFile}' but it was not found")
      }
      val rollbackSql = IO.blocking(Files.readString(rollbackPath))
      rollbackSql.flatMap { sql =>
        if sql.trim.isEmpty then
          IO.raiseError(MigratorError.Apply(s"$rollbackPath: rollback SQL file is empty"))
        else
          IO(System.nanoTime()).flatMap { started =>
            IO.blocking(connection.setAutoCommit(false)) *>
              IO.blocking(executeStatement(connection, sql))
                .attempt
                .flatMap {
                    case Right(()) =>
                      (applyLog.recordRolledBack(target, JdbcSupport.durationMs(started)) *>
                        IO.blocking(connection.commit()))
                        .handleErrorWith(failure =>
                          IO.blocking(connection.rollback()).attempt.flatMap {
                            case Right(())                        => IO.raiseError(failure)
                            case Left(rollbackErr: SQLException)  => failure.addSuppressed(rollbackErr); IO.raiseError(failure)
                            case Left(rollbackErr)                => IO.raiseError(new RuntimeException("rollback failed", rollbackErr).initCause(failure))
                          }
                        )
                   case Left(error: SQLException) =>
                     IO.blocking(connection.rollback()) *>
                       IO.raiseError(MigratorError.Apply(JdbcSupport.sqlError(rollbackPath.toString, error, "postgres"), error))
                  case Left(other) =>
                    IO.blocking(connection.rollback()) *>
                      IO.raiseError(other)
                }
                .guarantee(IO.blocking(connection.setAutoCommit(true)))
          }
      }
    }

  private def appliedBy: String =
    JdbcSupport.appliedBy()

object PostgresProvider:
  def fromConfig(config: MigratorConfig): IO[PostgresProvider] =
    IO.fromEither {
      config.databaseUrl
        .orElse(sys.env.get("DATABASE_URL"))
        .toRight("DATABASE_URL is required for Postgres")
        .flatMap(normalize)
        .map(PostgresProvider(_))
        .leftMap(message => MigratorError.Connection(message))
    }

  def normalize(raw: String): Either[String, JdbcConnectionConfig] =
    if raw.startsWith("jdbc:postgresql://") then
      Right(JdbcConnectionConfig("org.postgresql.Driver", raw))
    else if raw.startsWith("postgres://") || raw.startsWith("postgresql://") then
      parsePostgresUri(raw)
    else Left("Postgres URL must start with postgres://, postgresql://, or jdbc:postgresql://")

  private def parsePostgresUri(raw: String): Either[String, JdbcConnectionConfig] =
    Either.catchNonFatal(URI(raw)).leftMap(error => s"invalid Postgres URL: ${error.getMessage}").flatMap { uri =>
      if uri.getHost == null then
        Left("invalid Postgres URL: host is required")
      else
        val path = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
        val port = if uri.getPort >= 0 then s":${uri.getPort}" else ""
        val query = Option(uri.getRawQuery).filter(_.nonEmpty).map(q => s"?$q").getOrElse("")
        val url = s"jdbc:postgresql://${uri.getHost}$port$path$query"
        val userInfo = Option(uri.getRawUserInfo).getOrElse("")
        val parts = userInfo.split(":", 2)
        val user = parts.headOption.filter(_.nonEmpty).map(decode)
        val pass = parts.drop(1).headOption.filter(_.nonEmpty).map(decode)
        Right(JdbcConnectionConfig("org.postgresql.Driver", url, user, pass))
    }

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

object PostgresSession:
  val applyLockNamespace = "ssl-proxy:schema-migrator:schema-apply"
  val applyLockKey: Long = advisoryLockKey(applyLockNamespace)

  private def advisoryLockKey(namespace: String): Long =
    var hash = BigInt("cbf29ce484222325", 16)
    val prime = BigInt("100000001b3", 16)
    val mask = BigInt("ffffffffffffffff", 16)
    namespace.getBytes(StandardCharsets.UTF_8).foreach { byte =>
      hash = (hash ^ BigInt(byte & 0xff)) * prime & mask
    }
    (hash & BigInt("7fffffffffffffff", 16)).toLong