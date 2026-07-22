package com.sslproxy.schema.db

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresStatements
import com.sslproxy.schema.engine.*
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.server.PostgresDriftAnalyzer
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.sql.{Connection, ResultSet, SQLException, Types}

trait SchemaControlStore[F[_]]:
  def prepare(objects: List[SchemaObject]): F[List[PreparedObject]]
  def retire(objects: List[SchemaObject]): F[Unit]
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

final class PostgresSchemaControlStore extends SchemaControlStore[ConnectionIO]:
  import PostgresDriftAnalyzer.*

  override def prepare(objects: List[SchemaObject]): ConnectionIO[List[PreparedObject]] =
    objects.traverse(prepareOne)

  override def retire(objects: List[SchemaObject]): ConnectionIO[Unit] =
    objects.traverse_(objectDef => Update[String](PostgresStatements.retireSql).run(objectDef.sourceFile).void)

  private def prepareOne(objectDef: SchemaObject): ConnectionIO[PreparedObject] =
    val operation =
      for
        existing <- Query[(String, String), (String, String)](PostgresStatements.lookupExistingSql)
          .option((objectDef.kind, objectDef.objectName))
        oldSha = existing.map(_._1)
        oldStatus = existing.map(_._2)
        controlCurrent = oldSha.exists(_ == objectDef.sha256) && oldStatus.exists(Set("applied", "skipped"))
        liveCurrent <- if controlCurrent then liveCatalogCurrent(objectDef) else true.pure[ConnectionIO]
        needsApply = !controlCurrent || !liveCurrent
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

  private def liveCatalogCurrent(objectDef: SchemaObject): ConnectionIO[Boolean] =
    val expectedDefinitions = catalogDefinitions(objectDef.rawSql)
    if expectedDefinitions.isEmpty then true.pure[ConnectionIO]
    else
      expectedDefinitions
        .traverse { definition =>
          liveDefinition(definition.key).map {
            case None => false
            case Some(None) => true
            case Some(Some(actualDdl)) =>
              definitionHash(definition.key, definition.ddl) == definitionHash(definition.key, actualDdl)
          }
        }
        .map(_.forall(identity))

  private def liveDefinition(key: ObjectKey): ConnectionIO[Option[Option[String]]] =
    key.objectType match
      case "schema" =>
        sql"""
        select format('create schema %I', n.nspname)::text
          from pg_namespace n
         where n.nspname = ${key.schema}
        """.query[String].option.map(_.map(Some(_)))
      case "extension" =>
        sql"""
        select format('create extension if not exists %I with schema %I', e.extname, n.nspname)::text
          from pg_extension e
          join pg_namespace n on n.oid = e.extnamespace
         where n.nspname = ${key.schema} and e.extname = ${key.name}
        """.query[String].option.map(_.map(Some(_)))
      case "table" =>
        relationExists(key, Set("r", "p")).map(exists => Option.when(exists)(None))
      case "sequence" =>
        relationExists(key, Set("S")).map(exists => Option.when(exists)(None))
      case "view" =>
        relationDefinition(key, Set("v"), "create view")
      case "materialized_view" =>
        relationDefinition(key, Set("m"), "create materialized view")
      case "index" =>
        sql"""
        select pg_get_indexdef(c.oid)
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = ${key.schema}
           and c.relname = ${key.name}
           and c.relkind in ('i', 'I')
        """.query[String].option.map(_.map(Some(_)))
      case "function" | "procedure" =>
        routineDefinition(key)
      case "type" =>
        sql"""
        select null::text
          from pg_type typ
          join pg_namespace n on n.oid = typ.typnamespace
          left join pg_class c on c.oid = typ.typrelid
         where n.nspname = ${key.schema}
           and typ.typname = ${key.name}
           and typ.typtype in ('e', 'd', 'r', 'c')
           and (typ.typrelid = 0 or c.relkind = 'c')
        """.query[Option[String]].option
      case "trigger" =>
        val dot = key.name.indexOf('.')
        if dot < 0 then none[Option[String]].pure[ConnectionIO]
        else
          val tableName = key.name.substring(0, dot)
          val triggerName = key.name.substring(dot + 1)
          sql"""
          select pg_get_triggerdef(t.oid, true)
            from pg_trigger t
            join pg_class c on c.oid = t.tgrelid
            join pg_namespace n on n.oid = c.relnamespace
           where n.nspname = ${key.schema}
             and c.relname = $tableName
             and t.tgname = $triggerName
             and not t.tgisinternal
          """.query[String].option.map(_.map(Some(_)))
      case _ =>
        Some(None).pure[ConnectionIO]

  private def relationExists(key: ObjectKey, relKinds: Set[String]): ConnectionIO[Boolean] =
    (fr"""
    select exists (
      select 1
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = ${key.schema}
         and c.relname = ${key.name}
         and
    """ ++ Fragments.in(fr"c.relkind", NonEmptyList.fromListUnsafe(relKinds.toList)) ++ fr")")
      .query[Boolean]
      .unique

  private def relationDefinition(
    key: ObjectKey,
    relKinds: Set[String],
    createPrefix: String
  ): ConnectionIO[Option[Option[String]]] =
    (fr"""
    select format($createPrefix || ' %I.%I as %s', n.nspname, c.relname, pg_get_viewdef(c.oid, true))::text
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = ${key.schema}
       and c.relname = ${key.name}
       and
    """ ++ Fragments.in(fr"c.relkind", NonEmptyList.fromListUnsafe(relKinds.toList)))
      .query[String]
      .option
      .map(_.map(Some(_)))

  private def routineDefinition(key: ObjectKey): ConnectionIO[Option[Option[String]]] =
    val routineName = key.name.takeWhile(_ != '(')
    val proKind = if key.objectType == "procedure" then "p" else "f"
    sql"""
    select n.nspname,
           concat(p.proname::text, '(', oidvectortypes(p.proargtypes)::text, ')') as routine_name,
           pg_get_functiondef(p.oid)
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = ${key.schema}
       and p.proname = $routineName
       and p.prokind = $proKind
    """.query[(String, String, String)].to[List].map { rows =>
      rows.collectFirst {
        case (schema, name, ddl)
            if normalizeObjectKey(ObjectKey(schema, name, key.objectType)) == normalizeObjectKey(key) =>
          Some(ddl)
      }
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
    Query[String, (String, String, String, String, Option[String])](PostgresStatements.fetchRollbackTargetSql)
      .to[List](objectName)
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

  override def retire(objects: List[SchemaObject]): IO[Unit] =
    IO.blocking {
      objects.foreach { objectDef =>
        executePrepared(connection, OracleStatements.retireSql)(_.setString(1, objectDef.sourceFile))
      }
    }

  private def prepareOne(objectDef: SchemaObject): IO[PreparedObject] =
    IO.blocking {
      val existing = queryPrepared(connection, OracleStatements.lookupExistingSql) { statement =>
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
      val allRows = queryPrepared(connection, OracleStatements.fetchRollbackTargetSql) { statement =>
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
