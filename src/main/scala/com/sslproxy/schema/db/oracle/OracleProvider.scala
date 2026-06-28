package com.sslproxy.schema.db.oracle

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.db.{
  ApplyLog,
  DbProvider,
  DbSession,
  JdbcConnectionConfig,
  JdbcSupport,
  LockManager,
  SchemaControlStore
}
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.engine.*
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.validation.RollbackValidator

import java.nio.file.{Files, Path}
import java.sql.{Connection, SQLException}

final class OracleProvider(config: JdbcConnectionConfig) extends DbProvider:
  override val dialect: SqlDialect = SqlDialect.Oracle

  override def session: Resource[IO, DbSession] =
    JdbcSupport.connection(config).map(OracleSession(_))

final class OracleSession(connection: Connection) extends DbSession:
  import JdbcSupport.*

  private val lockManager = LockManager.oracle(connection)
  private val store = SchemaControlStore.oracle(connection)
  private val applyLog = ApplyLog.oracle(connection, appliedBy)

  override def checkConnection: IO[Unit] =
    IO.blocking(queryOne(connection, "select 1 from dual")(_.getInt(1))).void

  override def bootstrap: IO[Unit] =
    IO.blocking {
      OracleStatements.bootstrapBlocks.foreach(OracleDdlHelper.executeSql(connection, _))
    }.adaptError { case error: SQLException =>
      MigratorError.Apply(s"Oracle schema_control bootstrap failed: ${error.getMessage}", error)
    }

  override def acquireLock: IO[Unit] = lockManager.acquire
  override def releaseLock: IO[Unit] = lockManager.release
  override def prepare(objects: List[SchemaObject]): IO[List[PreparedObject]] = store.prepare(objects)
  override def fetchStatus: IO[List[ObjectStatus]] = store.fetchStatus
  override def fetchReady: IO[SchemaReadyStatus] = store.fetchReady
  override def checkReady: IO[Boolean] = fetchReady.map(_.ready)

  override def recordSkipped(prepared: PreparedObject): IO[Unit] =
    applyLog.recordSkipped(prepared.objectDef, prepared.oldSha256)

  override def executeObject(prepared: PreparedObject): IO[Unit] =
    if prepared.objectDef.transactional then executeTransactional(prepared)
    else executeNonTransactional(prepared)

  private def executeTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      withAutoCommit(false) {
        IO.blocking(OracleDdlHelper.executeSql(connection, prepared.objectDef.rawSql))
          .attempt
          .flatMap {
            case Right(()) =>
              (recordApplied(prepared, started) *> IO.blocking(connection.commit()))
                .handleErrorWith(rollbackThenRaise)
            case Left(error: SQLException) =>
              val formatted = JdbcSupport.sqlError(prepared.objectDef.sourceFile, error, "oracle")
              rollbackSuppressing(error) *>
                recordFailedAndCommit(prepared, started, formatted, error) *>
                IO.raiseError(MigratorError.Apply(formatted, error))
            case Left(other) =>
              rollbackThenRaise(other)
          }
      }
    }

  private def executeNonTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      withAutoCommit(true) {
        IO.blocking(OracleDdlHelper.executeSql(connection, prepared.objectDef.rawSql))
          .attempt
          .flatMap {
            case Right(()) =>
              recordApplied(prepared, started)
            case Left(error: SQLException) =>
              val formatted = JdbcSupport.sqlError(prepared.objectDef.sourceFile, error, "oracle")
              applyLog
                .recordFailed(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started), formatted)
                .handleErrorWith(logError => IO(error.addSuppressed(logError))) *>
                IO.raiseError(MigratorError.Apply(formatted, error))
            case Left(other) =>
              IO.raiseError(other)
          }
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
        throw MigratorError
          .Apply(s"${target.objectName} declares rollback file '${target.rollbackFile}' but it was not found")
      }
      val rollbackSql = IO.blocking(Files.readString(rollbackPath))
      rollbackSql.flatMap { sql =>
        if sql.trim.isEmpty then IO.raiseError(MigratorError.Apply(s"$rollbackPath: rollback SQL file is empty"))
        else
          IO(System.nanoTime()).flatMap { started =>
            withAutoCommit(false) {
              IO.blocking(OracleDdlHelper.executeSql(connection, sql))
                .attempt
                .flatMap {
                  case Right(()) =>
                    (recordRolledBack(target, rollbackPath, started) *> IO.blocking(connection.commit()))
                      .handleErrorWith(rollbackThenRaise)
                  case Left(error: SQLException) =>
                    IO.blocking(connection.rollback()) *>
                      IO.raiseError(
                        MigratorError.Apply(JdbcSupport.sqlError(rollbackPath.toString, error, "oracle"), error)
                      )
                  case Left(other) =>
                    rollbackThenRaise(other)
                }
            }
          }
      }
    }

  private def withAutoCommit[A](autoCommit: Boolean)(fa: IO[A]): IO[A] =
    IO.blocking {
      val saved = connection.getAutoCommit
      if saved != autoCommit then connection.setAutoCommit(autoCommit)
      saved
    }.flatMap { saved =>
      fa.guarantee(IO.blocking {
        if connection.getAutoCommit != saved then connection.setAutoCommit(saved)
      })
    }

  private def rollbackThenRaise[A](failure: Throwable): IO[A] =
    IO.blocking(connection.rollback()).attempt.flatMap {
      case Right(()) => IO.raiseError(failure)
      case Left(rollbackErr: SQLException) =>
        IO(failure.addSuppressed(rollbackErr)) *> IO.raiseError(failure)
      case Left(rollbackErr) =>
        val wrapped = RuntimeException("rollback failed", rollbackErr)
        IO(wrapped.addSuppressed(failure)) *> IO.raiseError(wrapped)
    }

  private def recordApplied(prepared: PreparedObject, started: Long): IO[Unit] =
    applyLog
      .recordApplied(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started))
      .adaptError { case error: SQLException =>
        MigratorError.NonRetryableApply(
          s"${prepared.objectDef.sourceFile}: applied Oracle SQL but failed to record migration state: ${error.getMessage}",
          error
        )
      }

  private def recordFailedAndCommit(
    prepared: PreparedObject,
    started: Long,
    formatted: String,
    error: SQLException
  ): IO[Unit] =
    applyLog
      .recordFailed(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started), formatted)
      .attempt
      .flatMap {
        case Right(()) =>
          IO.blocking(connection.commit()).handleErrorWith { commitError =>
            IO(error.addSuppressed(commitError)) *>
              IO.raiseError(
                MigratorError.NonRetryableApply(
                  s"${prepared.objectDef.sourceFile}: failed to commit Oracle failure state: ${commitError.getMessage}",
                  error
                )
              )
          }
        case Left(logError) =>
          IO(error.addSuppressed(logError)) *>
            IO.blocking(connection.rollback())
              .handleErrorWith(rollbackError => IO(error.addSuppressed(rollbackError)))
              .void
      }

  private def rollbackSuppressing(original: SQLException): IO[Unit] =
    IO.blocking(connection.rollback())
      .handleErrorWith(rollbackError => IO(original.addSuppressed(rollbackError)))
      .void

  private def recordRolledBack(target: RollbackTarget, rollbackPath: Path, started: Long): IO[Unit] =
    applyLog
      .recordRolledBack(target, JdbcSupport.durationMs(started))
      .adaptError { case error: SQLException =>
        MigratorError.NonRetryableApply(
          s"${rollbackPath}: rolled back Oracle SQL but failed to record migration state: ${error.getMessage}",
          error
        )
      }

  private def appliedBy: String =
    JdbcSupport.appliedBy()

object OracleProvider:
  def fromConfig(config: MigratorConfig): IO[OracleProvider] =
    for
      password <- config.oraclePasswordFile.traverse(JdbcSupport.readPasswordFile)
      provider <- IO.fromEither {
        configureWallet(config)
        val jdbcUrl = config.databaseUrl
          .orElse(sys.env.get("ORACLE_JDBC_URL"))
          .orElse(config.oracleTnsAlias.map(alias => s"jdbc:oracle:thin:@$alias"))
          .toRight("ORACLE_JDBC_URL or --oracle-tns-alias is required for Oracle")

        jdbcUrl
          .map { url =>
            OracleProvider(
              JdbcConnectionConfig(
                driver = "oracle.jdbc.OracleDriver",
                url = url,
                user = config.oracleUser,
                password = password
              )
            )
          }
          .leftMap(MigratorError.Connection(_))
      }
    yield provider

  private def configureWallet(config: MigratorConfig): Unit =
    config.oracleWallet.foreach { path =>
      val wallet = path.toAbsolutePath.toString
      System.setProperty("oracle.net.tns_admin", wallet)
      System.setProperty("oracle.net.wallet_location", s"(SOURCE=(METHOD=file)(METHOD_DATA=(DIRECTORY=$wallet)))")
      System.setProperty("oracle.net.ssl_server_dn_match", "true")
    }
