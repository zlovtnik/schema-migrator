package com.sslproxy.schema.db

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresStatements
import com.sslproxy.schema.engine.*
import com.sslproxy.schema.error.MigratorError
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.sql.{Connection, ResultSet, SQLException, Types}

trait SchemaControlStore[F[_]]:
  def prepare(objects: List[SchemaObject]): F[List[PreparedObject]]
  def fetchStatus: F[List[ObjectStatus]]
  def fetchReady: F[SchemaReadyStatus]
  def fetchRollbackTarget(objectName: String): F[RollbackTarget]

object SchemaControlStore:
  def postgres: SchemaControlStore[ConnectionIO] =
    new PostgresSchemaControlStore

  def oracle(connection: Connection): SchemaControlStore[IO] =
    new OracleSchemaControlStore(connection)

  private[db] def readObjectStatus(row: ResultSet): ObjectStatus =
    ObjectStatus(
      kind = row.getString("kind"),
      objectName = row.getString("object_name"),
      sourceFile = row.getString("source_file"),
      applyStatus = row.getString("apply_status"),
      contentSha256 = row.getString("content_sha256"),
      appliedAt = Option(row.getString("applied_at")),
      lastError = Option(row.getString("last_error"))
    )

  private[db] def readReadyStatusOracle(row: ResultSet): SchemaReadyStatus =
    SchemaReadyStatus(
      totalCount = row.getLong("total_count"),
      pendingCount = row.getLong("pending_count"),
      failedCount = row.getLong("failed_count"),
      appliedCount = row.getLong("applied_count"),
      ready = row.getString("ready") == "1",
      failedObjects =
        Option(row.getString("failed_objects")).toList.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty)),
      lastUpdatedAt = Option(row.getString("last_updated_at")),
      lastAppliedAt = Option(row.getString("last_applied_at"))
    )

  private[db] def buildRollbackTarget(rows: List[Option[RollbackTarget]]): RollbackTarget =
    rows.flatten match
      case Nil => throw MigratorError.Apply("no tracked schema object found")
      case target :: Nil =>
        if target.rollbackFile.isEmpty then
          throw MigratorError.Apply(s"no rollback SQL tracked for ${target.objectName}")
        target
      case many =>
        val matches = many.map(t => s"${t.kind}:${t.objectName}").mkString(", ")
        throw MigratorError.Apply(s"object name is ambiguous; matches: $matches")

  private[db] val lookupExistingSql: String =
    """
    select content_sha256, apply_status
      from schema_control.schema_objects
     where kind = ? and object_name = ?
    """

  private[db] val fetchRollbackTargetSql: String =
    """
    select kind, object_name, source_file, content_sha256, rollback_file
      from schema_control.schema_objects
     where object_name = ?
     order by kind, object_name
    """

final class PostgresSchemaControlStore extends SchemaControlStore[ConnectionIO]:
  override def prepare(objects: List[SchemaObject]): ConnectionIO[List[PreparedObject]] =
    objects.traverse(prepareOne)

  private def prepareOne(objectDef: SchemaObject): ConnectionIO[PreparedObject] =
    val operation =
      for
        existing <-
          sql"""
          select content_sha256, apply_status
            from schema_control.schema_objects
           where kind = ${objectDef.kind} and object_name = ${objectDef.objectName}
          """.query[(String, String)].option
        oldSha = existing.map(_._1)
        oldStatus = existing.map(_._2)
        needsApply = !oldSha.exists(_ == objectDef.sha256) || !oldStatus.exists(Set("applied", "skipped"))
        status = if needsApply then "pending" else "skipped"
        _ <- Update[
          (String, String, String, List[String], Option[String], String, String, String, String)
        ](PostgresStatements.prepareSql)
          .run(
            (
              objectDef.kind,
              objectDef.objectName,
              objectDef.sourceFile,
              objectDef.dependsOn,
              objectDef.rollbackFile,
              objectDef.canonicalSql,
              objectDef.sha256,
              status,
              status
            )
          )
      yield PreparedObject(objectDef, oldSha, needsApply)

    operation.adaptError { case error: SQLException =>
      MigratorError.Apply(
        s"failed to prepare schema control state for ${objectDef.kind}:${objectDef.objectName}: ${error.getMessage}",
        error
      )
    }

  override def fetchStatus: ConnectionIO[List[ObjectStatus]] =
    Fragment
      .const(PostgresStatements.fetchStatusSql)
      .query[(String, String, String, String, String, Option[String], Option[String])]
      .to[List]
      .map(_.map { case (kind, objectName, sourceFile, applyStatus, contentSha256, appliedAt, lastError) =>
        ObjectStatus(kind, objectName, sourceFile, applyStatus, contentSha256, appliedAt, lastError)
      })

  override def fetchReady: ConnectionIO[SchemaReadyStatus] =
    Fragment
      .const(PostgresStatements.fetchReadySql)
      .query[(Long, Long, Long, Long, Boolean, List[String], Option[String], Option[String])]
      .option
      .map {
        case Some(
              (totalCount, pendingCount, failedCount, appliedCount, ready, failedObjects, lastUpdatedAt, lastAppliedAt)
            ) =>
          SchemaReadyStatus(
            totalCount,
            pendingCount,
            failedCount,
            appliedCount,
            ready,
            failedObjects,
            lastUpdatedAt,
            lastAppliedAt
          )
        case None =>
          SchemaReadyStatus(0, 0, 0, 0, ready = false, Nil, None, None)
      }

  override def fetchRollbackTarget(objectName: String): ConnectionIO[RollbackTarget] =
    sql"""
    select kind, object_name, source_file, content_sha256, rollback_file
      from schema_control.schema_objects
     where object_name = $objectName
     order by kind, object_name
    """
      .query[(String, String, String, String, Option[String])]
      .to[List]
      .map { rows =>
        SchemaControlStore.buildRollbackTarget(
          rows.map { case (kind, rowObjectName, sourceFile, contentSha256, rollbackFile) =>
            Some(
              RollbackTarget(
                kind = kind,
                objectName = rowObjectName,
                sourceFile = sourceFile,
                contentSha256 = contentSha256,
                rollbackFile = rollbackFile.getOrElse("")
              )
            )
          }
        )
      }

final class OracleSchemaControlStore(connection: Connection) extends SchemaControlStore[IO]:
  import JdbcSupport.*

  override def prepare(objects: List[SchemaObject]): IO[List[PreparedObject]] =
    objects.traverse(prepareOne)

  private def prepareOne(objectDef: SchemaObject): IO[PreparedObject] =
    IO.blocking {
      val existing = queryPrepared(connection, SchemaControlStore.lookupExistingSql) { statement =>
        statement.setString(1, objectDef.kind)
        statement.setString(2, objectDef.objectName)
      } { row =>
        row.getString("content_sha256") -> row.getString("apply_status")
      }.headOption

      val oldSha = existing.map(_._1)
      val oldStatus = existing.map(_._2)
      val needsApply = !oldSha.exists(_ == objectDef.sha256) || !oldStatus.exists(Set("applied", "skipped"))
      val status = if needsApply then "pending" else "skipped"

      executePrepared(connection, OracleStatements.prepareSql) { statement =>
        statement.setString(1, objectDef.kind)
        statement.setString(2, objectDef.objectName)
        statement.setString(3, objectDef.sourceFile)
        statement.setString(4, objectDef.dependsOn.mkString(","))
        objectDef.rollbackFile.fold(statement.setNull(5, Types.VARCHAR))(statement.setString(5, _))
        statement.setString(6, objectDef.canonicalSql)
        statement.setString(7, objectDef.sha256)
        statement.setString(8, status)
      }

      PreparedObject(objectDef, oldSha, needsApply)
    }.adaptError { case error: SQLException =>
      MigratorError.Apply(
        s"failed to prepare Oracle schema control state for ${objectDef.kind}:${objectDef.objectName}: ${error.getMessage}",
        error
      )
    }

  override def fetchStatus: IO[List[ObjectStatus]] =
    IO.blocking(
      queryList(connection, OracleStatements.fetchStatusSql)(SchemaControlStore.readObjectStatus)
    )

  override def fetchReady: IO[SchemaReadyStatus] =
    IO.blocking(
      queryOne(connection, OracleStatements.fetchReadySql)(SchemaControlStore.readReadyStatusOracle)
        .getOrElse(SchemaReadyStatus(0, 0, 0, 0, ready = false, Nil, None, None))
    )

  override def fetchRollbackTarget(objectName: String): IO[RollbackTarget] =
    IO.blocking {
      val allRows = queryPrepared(connection, SchemaControlStore.fetchRollbackTargetSql) { statement =>
        statement.setString(1, objectName)
      } { row =>
        Some(
          RollbackTarget(
            kind = row.getString("kind"),
            objectName = row.getString("object_name"),
            sourceFile = row.getString("source_file"),
            contentSha256 = row.getString("content_sha256"),
            rollbackFile = Option(row.getString("rollback_file")).getOrElse("")
          )
        )
      }
      SchemaControlStore.buildRollbackTarget(allRows)
    }
