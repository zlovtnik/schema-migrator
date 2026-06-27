package com.sslproxy.schema.db.postgres

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.validation.RollbackValidator
import com.sslproxy.schema.db.{ApplyLog, DbProvider, DbSession, DoobieSupport, JdbcConnectionConfig, JdbcSupport, LockManager, SchemaControlStore}
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.engine.*
import com.sslproxy.schema.error.MigratorError
import doobie.*
import doobie.free.FS
import doobie.hi.HC
import doobie.implicits.*
import doobie.util.transactor.Transactor

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.SQLException

final class PostgresProvider(config: JdbcConnectionConfig) extends DbProvider:
  override val dialect: SqlDialect = SqlDialect.Postgres

  override def session: Resource[IO, DbSession] =
    DoobieSupport.postgresSessionTransactor(config).map(PostgresSession(_))

final class PostgresSession(transactor: Transactor[IO]) extends DbSession:
  private val nonTransactionalTransactor = DoobieSupport.withoutTransaction(transactor)

  private val lockManager  = LockManager.postgres(PostgresSession.applyLockKey, PostgresSession.applyLockNamespace)
  private val store        = SchemaControlStore.postgres
  private val applyLog     = ApplyLog.postgres(appliedBy)

  override def checkConnection: IO[Unit] =
    sql"select 1".query[Int].unique.transact(transactor).void

  override def bootstrap: IO[Unit] =
    executeStatement(PostgresStatements.bootstrapSql).transact(transactor).adaptError {
      case error: SQLException => MigratorError.Apply(s"schema_control bootstrap failed: ${error.getMessage}", error)
    }

  override def acquireLock: IO[Unit]           = lockManager.acquire.transact(transactor)
  override def releaseLock: IO[Unit]           = lockManager.release.transact(transactor)
  override def prepare(objects: List[SchemaObject]): IO[List[PreparedObject]] = store.prepare(objects).transact(transactor)
  override def fetchStatus: IO[List[ObjectStatus]]         = store.fetchStatus.transact(transactor)
  override def fetchReady: IO[SchemaReadyStatus]           = store.fetchReady.transact(transactor)
  override def checkReady: IO[Boolean]                     = fetchReady.map(_.ready)

  override def recordSkipped(prepared: PreparedObject): IO[Unit] =
    applyLog.recordSkipped(prepared.objectDef, prepared.oldSha256).transact(transactor)

  override def executeObject(prepared: PreparedObject): IO[Unit] =
    if prepared.objectDef.transactional then executeTransactional(prepared)
    else executeNonTransactional(prepared)

  private def executeTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      val action =
        sqlExecution(prepared.objectDef.rawSql) *>
          applyLog.recordApplied(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started))

      action.transact(transactor).handleErrorWith {
        case failure: SqlExecutionFailure =>
          recordFailedThenRaise(prepared, started, failure.error)
        case other =>
          IO.raiseError(other)
      }
    }

  private def executeNonTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      executeStatement(prepared.objectDef.rawSql)
        .transact(nonTransactionalTransactor)
        .attempt
        .flatMap {
          case Right(()) =>
            applyLog.recordApplied(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started))
              .transact(transactor)
              .adaptError { case error: SQLException =>
                MigratorError.NonRetryableApply(
                  s"${prepared.objectDef.sourceFile}: applied SQL but failed to record migration state: ${error.getMessage}",
                  error
                )
              }
          case Left(error: SQLException) =>
            recordFailedThenRaise(prepared, started, error)
          case Left(other) =>
            IO.raiseError(other)
        }
    }

  override def rollbackObject(sqlDir: Path, objectName: String): IO[Unit] =
    store.fetchRollbackTarget(objectName).transact(transactor).flatMap { target =>
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
            val action =
              sqlExecution(sql) *>
                applyLog.recordRolledBack(target, JdbcSupport.durationMs(started))

            action.transact(transactor).handleErrorWith {
              case failure: SqlExecutionFailure =>
                IO.raiseError(MigratorError.Apply(JdbcSupport.sqlError(rollbackPath.toString, failure.error, "postgres"), failure.error))
              case other =>
                IO.raiseError(other)
            }
          }
      }
    }

  private def appliedBy: String =
    JdbcSupport.appliedBy()

  private def executeStatement(sql: String): ConnectionIO[Unit] =
    HC.createStatement(FS.execute(sql)).void

  private def sqlExecution(sql: String): ConnectionIO[Unit] =
    executeStatement(sql).adaptError { case error: SQLException =>
      SqlExecutionFailure(error)
    }

  private def recordFailedThenRaise[A](
      prepared: PreparedObject,
      started: Long,
      error: SQLException
  ): IO[A] =
    val formatted = JdbcSupport.sqlError(prepared.objectDef.sourceFile, error, "postgres")
    applyLog.recordFailed(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started), formatted)
      .transact(transactor)
      .attempt
      .flatMap {
        case Right(()) =>
          IO.raiseError(MigratorError.Apply(formatted, error))
        case Left(logError) =>
          IO(error.addSuppressed(logError)) *> IO.raiseError(MigratorError.Apply(formatted, error))
      }

  private final case class SqlExecutionFailure(error: SQLException) extends RuntimeException(error)

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
