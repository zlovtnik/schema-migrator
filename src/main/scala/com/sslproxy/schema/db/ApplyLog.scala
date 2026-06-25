package com.sslproxy.schema.db

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresStatements
import com.sslproxy.schema.effect.Transaction
import com.sslproxy.schema.engine.*

import java.sql.{Connection, Timestamp, Types}

trait ApplyLog[F[_]]:
  def recordApplied(obj: SchemaObject, oldSha: Option[String], durationMs: Int): F[Unit]
  def recordFailed(obj: SchemaObject, oldSha: Option[String], durationMs: Int, error: String): F[Unit]
  def recordSkipped(obj: SchemaObject, oldSha: Option[String]): F[Unit]
  def recordRolledBack(target: RollbackTarget, durationMs: Int): F[Unit]

object ApplyLog:
  def postgres(connection: Connection, appliedBy: String): ApplyLog[IO] =
    new PostgresApplyLog(connection, appliedBy)

  def oracle(connection: Connection, appliedBy: String): ApplyLog[IO] =
    new OracleApplyLog(connection, appliedBy)

final class PostgresApplyLog(
    connection: Connection,
    appliedBy: String
) extends ApplyLog[IO]:
  import JdbcSupport.executePrepared

  override def recordApplied(obj: SchemaObject, oldSha: Option[String], durationMs: Int): IO[Unit] =
    IO.blocking {
      setStatusApplied(obj, oldSha)
      insert(obj, "applied", oldSha, Some(durationMs), None)
    }

  override def recordFailed(obj: SchemaObject, oldSha: Option[String], durationMs: Int, error: String): IO[Unit] =
    IO.blocking {
      setStatusFailed(obj, oldSha, error)
      insert(obj, "failed", oldSha, Some(durationMs), Some(error))
    }

  override def recordSkipped(obj: SchemaObject, oldSha: Option[String]): IO[Unit] =
    IO.blocking {
      setStatusSkipped(obj, oldSha)
      insert(obj, "skipped", oldSha, None, None)
    }

  override def recordRolledBack(target: RollbackTarget, durationMs: Int): IO[Unit] =
    IO.blocking {
      setRollbackStatus(target)
      insertRollback(target, durationMs)
    }

  private def setStatusApplied(obj: SchemaObject, oldSha: Option[String]): Unit =
    setStatus("applied", null, obj, oldSha)

  private def setStatusFailed(obj: SchemaObject, oldSha: Option[String], error: String): Unit =
    setStatus("failed", error, obj, oldSha)

  private def setStatusSkipped(obj: SchemaObject, oldSha: Option[String]): Unit =
    setStatus("skipped", null, obj, oldSha)

  private def setStatus(status: String, error: String, obj: SchemaObject, oldSha: Option[String]): Unit =
    executePrepared(connection, PostgresStatements.updateStatusSql) { statement =>
      statement.setString(1, status)
      if status == "applied" then statement.setNull(2, Types.TIMESTAMP)
      else statement.setTimestamp(2, Timestamp.from(java.time.Instant.now()))
      Option(error).fold(statement.setNull(3, Types.VARCHAR))(statement.setString(3, _))
      statement.setString(4, obj.kind)
      statement.setString(5, obj.objectName)
    }

  private def setRollbackStatus(target: RollbackTarget): Unit =
    executePrepared(connection, PostgresStatements.rollbackStatusSql) { statement =>
      statement.setString(1, target.kind)
      statement.setString(2, target.objectName)
    }

  private def insert(
      obj: SchemaObject,
      action: String,
      oldSha: Option[String],
      durationMs: Option[Int],
      errorText: Option[String]
  ): Unit =
    executePrepared(connection, PostgresStatements.applyLogSql) { statement =>
      statement.setString(1, obj.kind)
      statement.setString(2, obj.objectName)
      statement.setString(3, obj.sourceFile)
      statement.setString(4, action)
      oldSha.fold(statement.setNull(5, Types.VARCHAR))(statement.setString(5, _))
      statement.setString(6, obj.sha256)
      durationMs.fold(statement.setNull(7, Types.INTEGER))(statement.setInt(7, _))
      errorText.fold(statement.setNull(8, Types.VARCHAR))(statement.setString(8, _))
      statement.setString(9, appliedBy)
    }

  private def insertRollback(target: RollbackTarget, durationMs: Int): Unit =
    executePrepared(connection, PostgresStatements.applyLogSql) { statement =>
      statement.setString(1, target.kind)
      statement.setString(2, target.objectName)
      statement.setString(3, target.sourceFile)
      statement.setString(4, "rolled_back")
      statement.setString(5, target.contentSha256)
      statement.setString(6, target.contentSha256)
      statement.setInt(7, durationMs)
      statement.setNull(8, Types.VARCHAR)
      statement.setString(9, appliedBy)
    }

final class OracleApplyLog(
    connection: Connection,
    appliedBy: String
) extends ApplyLog[IO]:
  import JdbcSupport.executePrepared

  private val tx = Transaction.fromConnection[IO](connection)

  override def recordApplied(obj: SchemaObject, oldSha: Option[String], durationMs: Int): IO[Unit] =
    tx(IO.blocking {
      setStatusApplied(obj, oldSha)
      insert(obj, "applied", oldSha, Some(durationMs), None)
      connection.commit()
    })

  override def recordFailed(obj: SchemaObject, oldSha: Option[String], durationMs: Int, error: String): IO[Unit] =
    tx(IO.blocking {
      setStatusFailed(obj, oldSha, error)
      insert(obj, "failed", oldSha, Some(durationMs), Some(error))
      connection.commit()
    })

  override def recordSkipped(obj: SchemaObject, oldSha: Option[String]): IO[Unit] =
    tx(IO.blocking {
      setStatusSkipped(obj, oldSha)
      insert(obj, "skipped", oldSha, None, None)
      connection.commit()
    })

  override def recordRolledBack(target: RollbackTarget, durationMs: Int): IO[Unit] =
    tx(IO.blocking {
      setRollbackStatus(target)
      insertRollback(target, durationMs)
      connection.commit()
    })

  private def setStatusApplied(obj: SchemaObject, oldSha: Option[String]): Unit =
    setStatus("applied", null, obj, oldSha)

  private def setStatusFailed(obj: SchemaObject, oldSha: Option[String], error: String): Unit =
    setStatus("failed", error, obj, oldSha)

  private def setStatusSkipped(obj: SchemaObject, oldSha: Option[String]): Unit =
    setStatus("skipped", null, obj, oldSha)

  private def setStatus(status: String, error: String, obj: SchemaObject, oldSha: Option[String]): Unit =
    executePrepared(connection, OracleStatements.updateStatusSql) { statement =>
      statement.setString(1, status)
      Option(error).fold(statement.setNull(2, Types.CLOB))(statement.setString(2, _))
      statement.setString(3, obj.kind)
      statement.setString(4, obj.objectName)
    }

  private def setRollbackStatus(target: RollbackTarget): Unit =
    executePrepared(connection, OracleStatements.rollbackStatusSql) { statement =>
      statement.setString(1, target.kind)
      statement.setString(2, target.objectName)
    }

  private def insert(
      obj: SchemaObject,
      action: String,
      oldSha: Option[String],
      durationMs: Option[Int],
      errorText: Option[String]
  ): Unit =
    executePrepared(connection, OracleStatements.applyLogSql) { statement =>
      statement.setString(1, obj.kind)
      statement.setString(2, obj.objectName)
      statement.setString(3, obj.sourceFile)
      statement.setString(4, action)
      oldSha.fold(statement.setNull(5, Types.VARCHAR))(statement.setString(5, _))
      statement.setString(6, obj.sha256)
      durationMs.fold(statement.setNull(7, Types.INTEGER))(statement.setInt(7, _))
      errorText.fold(statement.setNull(8, Types.CLOB))(statement.setString(8, _))
      statement.setString(9, appliedBy)
    }

  private def insertRollback(target: RollbackTarget, durationMs: Int): Unit =
    executePrepared(connection, OracleStatements.applyLogSql) { statement =>
      statement.setString(1, target.kind)
      statement.setString(2, target.objectName)
      statement.setString(3, target.sourceFile)
      statement.setString(4, "rolled_back")
      statement.setString(5, target.contentSha256)
      statement.setString(6, target.contentSha256)
      statement.setInt(7, durationMs)
      statement.setNull(8, Types.CLOB)
      statement.setString(9, appliedBy)
    }