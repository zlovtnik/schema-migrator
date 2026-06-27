package com.sslproxy.schema.db

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresStatements
import com.sslproxy.schema.engine.*
import doobie.*
import doobie.implicits.*

import java.sql.{Connection, Timestamp, Types}
import scala.util.control.NonFatal

trait ApplyLog[F[_]]:
  def recordApplied(obj: SchemaObject, oldSha: Option[String], durationMs: Int): F[Unit]
  def recordFailed(obj: SchemaObject, oldSha: Option[String], durationMs: Int, error: String): F[Unit]
  def recordSkipped(obj: SchemaObject, oldSha: Option[String]): F[Unit]
  def recordRolledBack(target: RollbackTarget, durationMs: Int): F[Unit]

object ApplyLog:
  def postgres(appliedBy: String): PostgresApplyLog =
    new PostgresApplyLog(appliedBy)

  def oracle(connection: Connection, appliedBy: String): ApplyLog[IO] =
    new OracleApplyLog(connection, appliedBy)

final class PostgresApplyLog(
    appliedBy: String
):
  def recordApplied(obj: SchemaObject, oldSha: Option[String], durationMs: Int): ConnectionIO[Unit] =
    setStatusApplied(obj, oldSha) *>
      insert(obj, "applied", oldSha, Some(durationMs), None)

  def recordFailed(obj: SchemaObject, oldSha: Option[String], durationMs: Int, error: String): ConnectionIO[Unit] =
    setStatusFailed(obj, oldSha, error) *>
      insert(obj, "failed", oldSha, Some(durationMs), Some(error))

  def recordSkipped(obj: SchemaObject, oldSha: Option[String]): ConnectionIO[Unit] =
    setStatusSkipped(obj, oldSha) *>
      insert(obj, "skipped", oldSha, None, None)

  def recordRolledBack(target: RollbackTarget, durationMs: Int): ConnectionIO[Unit] =
    setRollbackStatus(target) *> insertRollback(target, durationMs)

  private def setStatusApplied(obj: SchemaObject, oldSha: Option[String]): ConnectionIO[Unit] =
    setStatus("applied", null, obj, oldSha)

  private def setStatusFailed(obj: SchemaObject, oldSha: Option[String], error: String): ConnectionIO[Unit] =
    setStatus("failed", error, obj, oldSha)

  private def setStatusSkipped(obj: SchemaObject, oldSha: Option[String]): ConnectionIO[Unit] =
    setStatus("skipped", null, obj, oldSha)

  private def setStatus(status: String, error: String, obj: SchemaObject, oldSha: Option[String]): ConnectionIO[Unit] =
    Update[(String, Option[Timestamp], Option[String], String, String)](PostgresStatements.updateStatusSql)
      .run((
        status,
        Option.when(status == "applied")(Timestamp.from(java.time.Instant.now())),
        Option(error),
        obj.kind,
        obj.objectName
      ))
      .void

  private def setRollbackStatus(target: RollbackTarget): ConnectionIO[Unit] =
    Update[(String, String)](PostgresStatements.rollbackStatusSql)
      .run((target.kind, target.objectName))
      .void

  private def insert(
      obj: SchemaObject,
      action: String,
      oldSha: Option[String],
      durationMs: Option[Int],
      errorText: Option[String]
  ): ConnectionIO[Unit] =
    Update[(String, String, String, String, Option[String], String, Option[Int], Option[String], String)](
      PostgresStatements.applyLogSql
    ).run((
      obj.kind,
      obj.objectName,
      obj.sourceFile,
      action,
      oldSha,
      obj.sha256,
      durationMs,
      errorText,
      appliedBy
    )).void

  private def insertRollback(target: RollbackTarget, durationMs: Int): ConnectionIO[Unit] =
    Update[(String, String, String, String, String, String, Option[Int], Option[String], String)](
      PostgresStatements.applyLogSql
    ).run((
      target.kind,
      target.objectName,
      target.sourceFile,
      "rolled_back",
      target.contentSha256,
      target.contentSha256,
      Some(durationMs),
      None,
      appliedBy
    )).void

final class OracleApplyLog(
    connection: Connection,
    appliedBy: String
) extends ApplyLog[IO]:
  import JdbcSupport.executePrepared

  override def recordApplied(obj: SchemaObject, oldSha: Option[String], durationMs: Int): IO[Unit] =
    inApplyLogTransaction {
      setStatusApplied(obj, oldSha)
      insert(obj, "applied", oldSha, Some(durationMs), None)
    }

  override def recordFailed(obj: SchemaObject, oldSha: Option[String], durationMs: Int, error: String): IO[Unit] =
    inApplyLogTransaction {
      setStatusFailed(obj, oldSha, error)
      insert(obj, "failed", oldSha, Some(durationMs), Some(error))
    }

  override def recordSkipped(obj: SchemaObject, oldSha: Option[String]): IO[Unit] =
    inApplyLogTransaction {
      setStatusSkipped(obj, oldSha)
      insert(obj, "skipped", oldSha, None, None)
    }

  override def recordRolledBack(target: RollbackTarget, durationMs: Int): IO[Unit] =
    inApplyLogTransaction {
      setRollbackStatus(target)
      insertRollback(target, durationMs)
    }

  private def inApplyLogTransaction(body: => Unit): IO[Unit] =
    IO.blocking {
      val savedAutoCommit = connection.getAutoCommit
      if !savedAutoCommit then body
      else
        connection.setAutoCommit(false)
        try
          body
          connection.commit()
        catch
          case NonFatal(error) =>
            try connection.rollback()
            catch
              case NonFatal(rollbackError) => error.addSuppressed(rollbackError)
            throw error
        finally connection.setAutoCommit(true)
    }

  private def setStatusApplied(obj: SchemaObject, oldSha: Option[String]): Unit =
    setStatus("applied", null, obj, oldSha)

  private def setStatusFailed(obj: SchemaObject, oldSha: Option[String], error: String): Unit =
    setStatus("failed", error, obj, oldSha)

  private def setStatusSkipped(obj: SchemaObject, oldSha: Option[String]): Unit =
    setStatus("skipped", null, obj, oldSha)

  private def setStatus(status: String, error: String, obj: SchemaObject, oldSha: Option[String]): Unit =
    executePrepared(connection, OracleStatements.updateStatusSql) { statement =>
      statement.setString(1, status)
      statement.setString(2, status)
      Option(error).fold(statement.setNull(3, Types.CLOB))(statement.setString(3, _))
      statement.setString(4, obj.kind)
      statement.setString(5, obj.objectName)
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
