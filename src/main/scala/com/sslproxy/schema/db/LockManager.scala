package com.sslproxy.schema.db

import cats.effect.IO
import cats.effect.kernel.Sync
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresStatements
import com.sslproxy.schema.error.MigratorError

import java.sql.{Connection, SQLException}
import scala.concurrent.duration.*

trait LockManager[F[_]]:
  def acquire: F[Unit]
  def release: F[Unit]

object LockManager:
  def postgres(connection: Connection, lockKey: Long, lockNamespace: String): LockManager[IO] =
    new PostgresLockManager(connection, lockKey, lockNamespace)

  def oracle(connection: Connection): LockManager[IO] =
    new OracleLockManager(connection)

final class PostgresLockManager(
    connection: Connection,
    lockKey: Long,
    lockNamespace: String
) extends LockManager[IO]:
  import JdbcSupport.*

  override def acquire: IO[Unit] =
    IO.blocking {
      val rows = queryPrepared(connection, PostgresStatements.advisoryLockTry) { statement =>
        statement.setLong(1, lockKey)
      }(_.getBoolean(1))
      if !rows.headOption.contains(true) then
        throw MigratorError.Apply(
          s"schema apply lock $lockKey ($lockNamespace) is already held"
        )
    }

  override def release: IO[Unit] =
    IO.blocking {
      val rows = queryPrepared(connection, PostgresStatements.advisoryLockRelease) { statement =>
        statement.setLong(1, lockKey)
      }(_.getBoolean(1))
      if !rows.headOption.contains(true) then
        throw MigratorError.LockNotHeld(
          s"schema apply lock $lockKey ($lockNamespace) was not held"
        )
    }

final class OracleLockManager(connection: Connection) extends LockManager[IO]:
  import JdbcSupport.*

  private val lockName = "schema_migrate"

  override def acquire: IO[Unit] =
    IO.blocking {
      try
        executePrepared(connection, OracleStatements.lockAcquireSql)(_.setString(1, appliedBy(connection)))
      catch
        case error: SQLException if error.getErrorCode == 1 =>
          val stale = queryLockStale()
          if stale then
            releaseOwned()
            executePrepared(connection, OracleStatements.lockAcquireSql)(_.setString(1, appliedBy(connection)))
          else
            val info = queryLockInfo().map(info => s" held by ${info._1} since ${info._2}").getOrElse("")
            throw MigratorError.Apply(s"Oracle schema apply lock $lockName is already held$info", error)
    }

  override def release: IO[Unit] =
    IO.blocking {
      val affected = releaseOwned()
      if affected == 0 then
        throw MigratorError.LockNotHeld(
          s"Oracle schema apply lock $lockName was not held or owned by another process"
        )
    }

  /** Delete only the lock row owned by this process. Returns number of rows deleted. */
  private def releaseOwned(): Int =
    executePrepared(connection, s"${OracleStatements.lockDeleteSql} and locked_by = ?") { statement =>
      statement.setString(1, appliedBy(connection))
    }

  private def queryLockStale(): Boolean =
    queryOne(connection, OracleStatements.lockQueryStaleSql) { row => row.getInt(1) == 1 }.getOrElse(false)

  private def queryLockInfo(): Option[(String, String)] =
    queryOne(connection, OracleStatements.lockQueryInfoSql) { row =>
      row.getString("locked_by") -> row.getString("locked_at_char")
    }.headOption

  private def appliedBy(connection: Connection): String =
    s"${System.getenv().getOrDefault("HOSTNAME", "unknown-host")}:${ProcessHandle.current().pid()}"
