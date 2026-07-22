package com.sslproxy.schema.db

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.error.MigratorError
import doobie.*
import doobie.implicits.*

import java.sql.{Connection, SQLException}

trait LockManager[F[_]]:
  def acquire: F[Unit]
  def release: F[Unit]

object LockManager:
  def postgres(lockKey: Long, lockNamespace: String): PostgresLockManager =
    new PostgresLockManager(lockKey, lockNamespace)

  def oracle(connection: Connection): LockManager[IO] =
    new OracleLockManager(connection)

final class PostgresLockManager(
  lockKey: Long,
  lockNamespace: String
):
  def acquire: ConnectionIO[Unit] =
    sql"select pg_try_advisory_lock($lockKey)"
      .query[Boolean]
      .unique
      .flatMap { acquired =>
        if acquired then ().pure[ConnectionIO]
        else
          MigratorError
            .Apply(
              s"schema apply lock $lockKey ($lockNamespace) is already held"
            )
            .raiseError[ConnectionIO, Unit]
      }

  def release: ConnectionIO[Unit] =
    sql"select pg_advisory_unlock($lockKey)"
      .query[Boolean]
      .unique
      .flatMap { released =>
        if released then ().pure[ConnectionIO]
        else
          MigratorError
            .LockNotHeld(
              s"schema apply lock $lockKey ($lockNamespace) was not held"
            )
            .raiseError[ConnectionIO, Unit]
      }

final class OracleLockManager(connection: Connection) extends LockManager[IO]:
  import JdbcSupport.*

  private val lockName = "schema_migrate"

  override def acquire: IO[Unit] =
    IO.blocking {
      try
        executePrepared(connection, OracleStatements.lockAcquireSql)(_.setString(1, appliedBy))
        ()
      catch
        case error: SQLException if error.getErrorCode == 1 =>
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
      statement.setString(1, appliedBy)
    }

  private def queryLockInfo(): Option[(String, String)] =
    queryOne(connection, OracleStatements.lockQueryInfoSql) { row =>
      row.getString("locked_by") -> row.getString("locked_at_char")
    }

  private def appliedBy: String =
    s"${System.getenv().getOrDefault("HOSTNAME", "unknown-host")}:${ProcessHandle.current().pid()}"
