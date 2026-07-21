package com.sslproxy.schema.db.tidb

import cats.effect.IO
import com.sslproxy.schema.db.ApplyLog
import com.sslproxy.schema.db.tidb.R2dbcSupport.*
import com.sslproxy.schema.engine.*
import io.r2dbc.spi.Connection

final class TiDBApplyLog(conn: Connection, appliedBy: String) extends ApplyLog[IO]:
  override def recordApplied(obj: SchemaObject, oldSha: Option[String], durationMs: Int): IO[Unit] =
    execUpdate(conn, TiDBStatements.updateStatusSql,
      Seq("applied", "applied", null, obj.kind, obj.objectName)).void *>
      insertLog(obj, "applied", oldSha, Some(durationMs), None)

  override def recordFailed(obj: SchemaObject, oldSha: Option[String], durationMs: Int, error: String): IO[Unit] =
    execUpdate(conn, TiDBStatements.updateStatusSql,
      Seq("failed", "failed", error, obj.kind, obj.objectName)).void *>
      insertLog(obj, "failed", oldSha, Some(durationMs), Some(error))

  override def recordSkipped(obj: SchemaObject, oldSha: Option[String]): IO[Unit] =
    execUpdate(conn, TiDBStatements.updateStatusSkippedSql,
      Seq(obj.kind, obj.objectName)).void *>
      insertLog(obj, "skipped", oldSha, None, None)

  override def recordRolledBack(target: RollbackTarget, durationMs: Int): IO[Unit] =
    execUpdate(conn, TiDBStatements.rollbackStatusSql,
      Seq(target.kind, target.objectName)).void *>
      execUpdate(conn, TiDBStatements.applyLogSql, Seq(
        target.kind, target.objectName, target.sourceFile, "rolled_back",
        target.contentSha256, target.contentSha256,
        Int.box(durationMs), null, appliedBy
      )).void

  private def insertLog(
    obj: SchemaObject, action: String, oldSha: Option[String],
    durationMs: Option[Int], errorText: Option[String]
  ): IO[Unit] =
    execUpdate(conn, TiDBStatements.applyLogSql, Seq(
      obj.kind, obj.objectName, obj.sourceFile, action,
      oldSha.orNull, obj.sha256,
      durationMs.map(Int.box).orNull, errorText.orNull, appliedBy
    )).void
