package com.sslproxy.schema.db.tidb

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.MigratorConfig
import com.sslproxy.schema.db.*
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.db.tidb.R2dbcSupport.*
import com.sslproxy.schema.engine.*
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.validation.RollbackValidator
import io.r2dbc.spi.{ConnectionFactories, ConnectionFactory, ConnectionFactoryOptions}

import java.nio.file.{Files, Path}

final class TiDBProvider(cf: ConnectionFactory) extends DbProvider:
  override val dialect: SqlDialect = SqlDialect.TiDB

  override def session: Resource[IO, DbSession] =
    for
      conn <- Resource.make(publisherToIO(cf.create()))(c => publisherToIO(c.close()).attempt.void)
      session <- Resource.pure[IO, DbSession](TiDBSession(conn))
    yield session

final class TiDBSession(conn: io.r2dbc.spi.Connection) extends DbSession:
  private val lockManager = new TiDBLockManager(conn)
  private val applyLog = new TiDBApplyLog(conn, appliedBy)

  override def checkConnection: IO[Unit] =
    queryOne(conn, "select 1", Seq())(_.get(0, classOf[String])).void

  override def bootstrap: IO[Unit] =
    val blocks = TiDBStatements.bootstrapSql.split(";").map(_.trim).filter(_.nonEmpty).toList
    blocks.traverse_ { sql =>
      execUpdate(conn, sql, Seq()).void
    }.adaptError { case e: Exception =>
      MigratorError.Apply(s"TiDB schema_control bootstrap failed: ${e.getMessage}", e)
    }

  override def acquireLock: IO[Unit] = lockManager.acquire
  override def releaseLock: IO[Unit] = lockManager.release

  override def prepare(objects: List[SchemaObject]): IO[List[PreparedObject]] =
    objects.traverse(prepareOne)

  private def prepareOne(objectDef: SchemaObject): IO[PreparedObject] =
    for
      existing <- queryOne[(String, String)](
        conn, TiDBStatements.lookupExistingSql,
        Seq(objectDef.kind, objectDef.objectName)
      ) { row =>
        readString(row, "content_sha256") -> readString(row, "apply_status")
      }
      oldSha = existing.map(_._1)
      oldStatus = existing.map(_._2)
      needsApply = !oldSha.exists(_ == objectDef.sha256) || !oldStatus.exists(Set("applied", "skipped"))
      status = if needsApply then "pending" else "skipped"
      _ <- execUpdate(conn, TiDBStatements.prepareSql, Seq(
        objectDef.kind, objectDef.objectName, objectDef.sourceFile,
        objectDef.dependsOn.mkString(","), objectDef.rollbackFile.orNull,
        objectDef.canonicalSql, objectDef.sha256, status, status
      )).void
    yield PreparedObject(objectDef, oldSha, needsApply)
  .adaptError { case e: Exception =>
    MigratorError.Apply(
      s"failed to prepare TiDB schema control state for ${objectDef.kind}:${objectDef.objectName}: ${e.getMessage}", e
    )
  }

  override def retire(objects: List[SchemaObject]): IO[Unit] =
    objects.traverse_ { objectDef =>
      execUpdate(conn, TiDBStatements.retireSql, Seq(objectDef.sourceFile)).void
    }

  override def fetchStatus: IO[List[ObjectStatus]] =
    queryList(conn, TiDBStatements.fetchStatusSql, Seq()) { row =>
      ObjectStatus(
        kind = readString(row, "kind"),
        objectName = readString(row, "object_name"),
        sourceFile = readString(row, "source_file"),
        applyStatus = readString(row, "apply_status"),
        contentSha256 = readString(row, "content_sha256"),
        appliedAt = readStringOpt(row, "applied_at"),
        lastError = readStringOpt(row, "last_error")
      )
    }

  override def fetchReady: IO[SchemaReadyStatus] =
    queryOne(conn, TiDBStatements.fetchReadySql, Seq()) { row =>
      SchemaReadyStatus(
        totalCount = readLong(row, "total_count"),
        pendingCount = readLong(row, "pending_count"),
        failedCount = readLong(row, "failed_count"),
        appliedCount = readLong(row, "applied_count"),
        ready = readString(row, "ready") == "1",
        failedObjects = readStringOpt(row, "failed_objects").toList.flatMap(
          _.split(',').map(_.trim).filter(_.nonEmpty)
        ),
        lastUpdatedAt = readStringOpt(row, "last_updated_at"),
        lastAppliedAt = readStringOpt(row, "last_applied_at")
      )
    }.map(_.getOrElse(SchemaReadyStatus(0, 0, 0, 0, ready = false, Nil, None, None)))

  private def fetchRollbackTarget(objectName: String): IO[RollbackTarget] =
    queryList(conn, TiDBStatements.fetchRollbackTargetSql, Seq(objectName)) { row =>
      Some(RollbackTarget(
        kind = readString(row, "kind"),
        objectName = readString(row, "object_name"),
        sourceFile = readString(row, "source_file"),
        contentSha256 = readString(row, "content_sha256"),
        rollbackFile = readStringOpt(row, "rollback_file").getOrElse("")
      ))
    }.map(SchemaControlStore.buildRollbackTarget)

  override def recordSkipped(prepared: PreparedObject): IO[Unit] =
    execUpdate(conn, TiDBStatements.updateStatusSkippedSql,
      Seq(prepared.objectDef.kind, prepared.objectDef.objectName)).void

  override def executeObject(prepared: PreparedObject): IO[Unit] =
    if prepared.objectDef.transactional then executeTransactional(prepared)
    else executeNonTransactional(prepared)

  override def rollbackObject(sqlDir: Path, objectName: String): IO[Unit] =
    fetchRollbackTarget(objectName).flatMap { target =>
      val pseudoFile = com.sslproxy.schema.discovery.SqlFile(
        folder = target.kind, path = sqlDir.resolve(target.sourceFile),
        name = target.sourceFile, relativePath = target.sourceFile
      )
      val rollbackPath = RollbackValidator.resolveExistingRollbackPath(pseudoFile, target.rollbackFile).getOrElse {
        throw MigratorError.Apply(
          s"${target.objectName} declares rollback file '${target.rollbackFile}' but it was not found"
        )
      }
      IO.blocking(Files.readString(rollbackPath)).flatMap { sql =>
        if sql.trim.isEmpty then IO.raiseError(MigratorError.Apply(s"$rollbackPath: rollback SQL file is empty"))
        else IO(System.nanoTime()).flatMap { started =>
          inTransaction {
            execUpdate(conn, sql, Seq()).void *>
              recordRolledBack(target, JdbcSupport.durationMs(started))
          }.adaptError { case e: Exception =>
            MigratorError.Apply(s"$rollbackPath: tidb error ${e.getMessage}", e)
          }
        }
      }
    }

  private def executeTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      inTransaction {
        execUpdate(conn, prepared.objectDef.rawSql, Seq()).void *>
          recordApplied(prepared, started)
      }.adaptError { case e: Exception =>
        MigratorError.Apply(s"${prepared.objectDef.sourceFile}: tidb error ${e.getMessage}", e)
      }
    }

  private def executeNonTransactional(prepared: PreparedObject): IO[Unit] =
    IO(System.nanoTime()).flatMap { started =>
      execUpdate(conn, prepared.objectDef.rawSql, Seq()).void.attempt.flatMap {
        case Right(()) =>
          recordApplied(prepared, started)
        case Left(e: Exception) =>
          val formatted = s"${prepared.objectDef.sourceFile}: tidb error ${e.getMessage}"
          recordFailed(prepared, started, formatted).attempt *>
            IO.raiseError(MigratorError.Apply(formatted, e))
        case Left(other) => IO.raiseError(other)
      }
    }

  private def inTransaction[A](fa: IO[A]): IO[A] =
    for
      _ <- execUpdate(conn, "start transaction", Seq()).void
      result <- fa.attempt.flatMap {
        case Right(a) => execUpdate(conn, "commit", Seq()).void.as(a)
        case Left(e)  => execUpdate(conn, "rollback", Seq()).void *> IO.raiseError(e)
      }
    yield result

  private def recordApplied(prepared: PreparedObject, started: Long): IO[Unit] =
    applyLog.recordApplied(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started))

  private def recordFailed(prepared: PreparedObject, started: Long, error: String): IO[Unit] =
    applyLog.recordFailed(prepared.objectDef, prepared.oldSha256, JdbcSupport.durationMs(started), error)

  private def recordRolledBack(target: RollbackTarget, durationMs: Int): IO[Unit] =
    applyLog.recordRolledBack(target, durationMs)

  private def appliedBy: String = JdbcSupport.appliedBy()

object TiDBProvider:
  def fromConfig(config: MigratorConfig): IO[TiDBProvider] =
    for
      url <- IO.fromEither {
        config.databaseUrl
          .orElse(sys.env.get("DATABASE_URL"))
          .toRight(MigratorError.Connection("DATABASE_URL is required for TiDB"))
      }
      cf <- IO.delay {
        val r2dbcUrl = url
          .replace("jdbc:mysql://", "mysql://")
          .replace("mysql://", "r2dbc:mysql://")
        val opts = ConnectionFactoryOptions.parse(r2dbcUrl)
        val builder = ConnectionFactoryOptions.builder().from(opts)
        config.tidbUser.orElse(sys.env.get("TIDB_USER")).foreach(
          builder.option(ConnectionFactoryOptions.USER, _)
        )
        config.tidbPassword.orElse(sys.env.get("TIDB_PASSWORD")).foreach(
          builder.option(ConnectionFactoryOptions.PASSWORD, _)
        )
        ConnectionFactories.get(builder.build())
      }
    yield TiDBProvider(cf)
