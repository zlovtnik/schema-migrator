package com.sslproxy.schema.db

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresStatements
import com.sslproxy.schema.engine.*
import com.sslproxy.schema.error.MigratorError

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Types}
import scala.jdk.CollectionConverters.*

trait SchemaControlStore[F[_]]:
  def prepare(objects: List[SchemaObject]): F[List[PreparedObject]]
  def fetchStatus: F[List[ObjectStatus]]
  def fetchReady: F[SchemaReadyStatus]
  def fetchRollbackTarget(objectName: String): F[RollbackTarget]

object SchemaControlStore:
  def postgres(connection: Connection): SchemaControlStore[IO] =
    new PostgresSchemaControlStore(connection)

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

  private[db] def readReadyStatusPostgres(row: ResultSet): SchemaReadyStatus =
    val rawArray = Option(row.getArray("failed_objects"))
    val failedObjects: List[String] = rawArray match
      case Some(arr) =>
        try arr.getArray.asInstanceOf[Array[String]].toList
        finally arr.free()
      case None => Nil
    SchemaReadyStatus(
      totalCount = row.getLong("total_count"),
      pendingCount = row.getLong("pending_count"),
      failedCount = row.getLong("failed_count"),
      appliedCount = row.getLong("applied_count"),
      ready = row.getBoolean("ready"),
      failedObjects = failedObjects,
      lastUpdatedAt = Option(row.getString("last_updated_at")),
      lastAppliedAt = Option(row.getString("last_applied_at"))
    )

  private[db] def readReadyStatusOracle(row: ResultSet): SchemaReadyStatus =
    SchemaReadyStatus(
      totalCount = row.getLong("total_count"),
      pendingCount = row.getLong("pending_count"),
      failedCount = row.getLong("failed_count"),
      appliedCount = row.getLong("applied_count"),
      ready = row.getString("ready") == "1",
      failedObjects = Option(row.getString("failed_objects")).toList.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty)),
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

final class PostgresSchemaControlStore(connection: Connection) extends SchemaControlStore[IO]:
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

      val statement = connection.prepareStatement(PostgresStatements.prepareSql)
      try
        statement.setString(1, objectDef.kind)
        statement.setString(2, objectDef.objectName)
        statement.setString(3, objectDef.sourceFile)
        statement.setArray(4, connection.createArrayOf("text", objectDef.dependsOn.toArray[AnyRef]))
        objectDef.rollbackFile.fold(statement.setNull(5, Types.VARCHAR))(statement.setString(5, _))
        statement.setString(6, objectDef.canonicalSql)
        statement.setString(7, objectDef.sha256)
        statement.setString(8, status)
        statement.setString(9, status)
        statement.executeUpdate()
      finally statement.close()

      PreparedObject(objectDef, oldSha, needsApply)
    }.adaptError { case error: SQLException =>
      MigratorError.Apply(
        s"failed to prepare schema control state for ${objectDef.kind}:${objectDef.objectName}: ${error.getMessage}",
        error
      )
    }

  override def fetchStatus: IO[List[ObjectStatus]] =
    IO.blocking(
      queryList(connection, PostgresStatements.fetchStatusSql)(SchemaControlStore.readObjectStatus)
    )

  override def fetchReady: IO[SchemaReadyStatus] =
    IO.blocking(
      queryOne(connection, PostgresStatements.fetchReadySql)(SchemaControlStore.readReadyStatusPostgres)
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
