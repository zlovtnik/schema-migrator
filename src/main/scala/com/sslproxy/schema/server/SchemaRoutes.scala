package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.JdbcSupport
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.DiscoveryService
import com.sslproxy.schema.engine.ManifestBuilder
import com.sslproxy.schema.store.{DriftResponse, Models, SchemaCatalogResponse, SqlFileStore, StoredTarget, TargetStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.nio.file.Files
import java.sql.{Connection, DriverManager, ResultSet}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

object SchemaRoutes:
  import Models.given
  import PostgresDriftAnalyzer.*

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val log = LoggerFactory[IO].getLogger

  def routes(config: MigratorConfig, targetStore: TargetStore, sqlFileStore: SqlFileStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ GET -> Root / "schema" =>
        targetId(request).fold(RouteJson.badRequest("target_id is required")) { id =>
          targetStore.getStored(id).flatMap {
            case None => RouteJson.notFound(s"target '$id' was not found")
            case Some(target) =>
              TargetRoutes.withDbAccessAllowed(
                config.server,
                target.target.jdbc_url,
                "database schema access is not allowed for this target"
              ) {
                catalog(config, target, sqlFileStore).flatMap(response => RouteJson.ok(response.asJson))
              }
          }
        }

      case request @ GET -> Root / "drift" =>
        targetId(request).fold(RouteJson.badRequest("target_id is required")) { id =>
          targetStore.getStored(id).flatMap {
            case None => RouteJson.notFound(s"target '$id' was not found")
            case Some(target) =>
              TargetRoutes.withDbAccessAllowed(
                config.server,
                target.target.jdbc_url,
                "database drift access is not allowed for this target"
              ) {
                drift(config, target, sqlFileStore).flatMap(response => RouteJson.ok(response.asJson))
              }
          }
        }
    }

  private def catalog(config: MigratorConfig, target: StoredTarget, sqlFileStore: SqlFileStore): IO[SchemaCatalogResponse] =
    for
      now <- nowString
      kind = dbKindFor(target.target.jdbc_url)
      expected <- expectedObjects(config, kind, now, sqlFileStore)
      response <- kind match
        case DbKind.Oracle =>
          IO.pure(
            SchemaCatalogResponse(
              target_id = target.target.id,
              db_kind = "oracle",
              supported = false,
              checked_at = now,
              objects = Nil,
              warnings = expected.warnings :+
                "Oracle schema catalog introspection is not implemented; use target connection tests for Oracle validation."
            )
          )
        case DbKind.Postgres =>
          postgresSnapshot(target).attempt.map {
            case Right(snapshot) =>
              val objects = mergeCatalog(now, expected.objects, snapshot.objects, snapshot.control)
              SchemaCatalogResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                objects = objects,
                warnings = expected.warnings ++ snapshot.control.warnings
              )
            case Left(error) =>
              val objects = expected.objects.map(_.toCatalog(now, actual = None, status = "defined"))
              SchemaCatalogResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                objects = objects,
                warnings = expected.warnings :+ s"live Postgres introspection failed: ${error.getMessage}"
              )
          }
      _ <- log.info(Json.obj("event" -> Json.fromString("schema_catalog"), "target_id" -> Json.fromString(target.target.id), "db_kind" -> Json.fromString(response.db_kind), "object_count" -> Json.fromInt(response.objects.size), "supported" -> Json.fromBoolean(response.supported)).noSpaces)
    yield response

  private def drift(config: MigratorConfig, target: StoredTarget, sqlFileStore: SqlFileStore): IO[DriftResponse] =
    for
      now <- nowString
      kind = dbKindFor(target.target.jdbc_url)
      expected <- expectedObjects(config, kind, now, sqlFileStore)
      response <- kind match
        case DbKind.Oracle =>
          IO.pure(
            DriftResponse(
              target_id = target.target.id,
              db_kind = "oracle",
              supported = false,
              checked_at = now,
              control_summary = None,
              items = Nil,
              warnings = expected.warnings :+
                "Oracle drift introspection is not implemented; use target connection tests for Oracle validation."
            )
          )
        case DbKind.Postgres =>
          postgresSnapshot(target).attempt.map {
            case Right(snapshot) =>
              DriftResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                control_summary = snapshot.control.summary,
                items = driftItems(now, expected.objects, snapshot.objects, snapshot.control),
                warnings = expected.warnings ++ snapshot.control.warnings
              )
            case Left(error) =>
              DriftResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                control_summary = None,
                items = Nil,
                warnings = expected.warnings :+ s"live Postgres drift introspection failed: ${error.getMessage}"
              )
          }
      _ <- log.info(Json.obj("event" -> Json.fromString("schema_drift"), "target_id" -> Json.fromString(target.target.id), "db_kind" -> Json.fromString(response.db_kind), "drift_item_count" -> Json.fromInt(response.items.size), "supported" -> Json.fromBoolean(response.supported)).noSpaces)
    yield response

  private def targetId(request: org.http4s.Request[IO]): Option[String] =
    request.uri.query.params.get("target_id").map(_.trim).filter(_.nonEmpty)

  private def expectedObjects(config: MigratorConfig, kind: DbKind, now: String, sqlFileStore: SqlFileStore): IO[ExpectedSnapshot] =
    if kind != DbKind.Postgres then IO.pure(ExpectedSnapshot(Nil, Nil))
    else
      // Try store-based discovery first, fall back to filesystem
      sqlFileStore.isEmpty.flatMap { empty =>
        if !empty then
          (for
            files <- sqlFileStore.toSqlFiles
            discovery = DiscoveryService[IO]().discoverFromFiles(files, DbKind.Postgres)
            objects <- ManifestBuilder[IO](SqlDialect.Postgres).build(discovery.files)
            expected = objects.flatMap(expectedFromManifest)
          yield ExpectedSnapshot(expected, discovery.warnings)).handleError { case NonFatal(error) =>
            ExpectedSnapshot(Nil, List(s"SQL manifest from store could not be loaded: ${error.getMessage}"))
          }
        else if Files.notExists(config.sqlDir) || !Files.isDirectory(config.sqlDir) then
          IO.pure(ExpectedSnapshot(Nil, List(s"sql directory '${config.sqlDir}' is unavailable; no files in store either")))
        else
          (for
            discovery <- DiscoveryService[IO]().discover(config.sqlDir, DbKind.Postgres)
            objects <- ManifestBuilder[IO](SqlDialect.Postgres).build(discovery.files)
            expected = objects.flatMap(expectedFromManifest)
          yield ExpectedSnapshot(expected, discovery.warnings)).handleError { case NonFatal(error) =>
            ExpectedSnapshot(Nil, List(s"SQL manifest could not be loaded: ${error.getMessage}"))
          }
      }

  private def postgresSnapshot(target: StoredTarget): IO[PostgresSnapshot] =
    IO.blocking {
      Class.forName("org.postgresql.Driver")
      val connection = connect(target)
      try
        val objects = readPostgresObjects(connection)
        val control = readControlObjects(connection)
        PostgresSnapshot(objects, control)
      finally connection.close()
    }

  private def connect(target: StoredTarget): Connection =
    val settings = JdbcConnectionProperties.normalize(target.target.jdbc_url, target.password)
    DriverManager.getConnection(
      settings.jdbcUrl,
      JdbcConnectionProperties.withTimeouts(settings.jdbcUrl, settings.password, 5.seconds, user = settings.user)
    )

  private def readPostgresObjects(connection: Connection): List[LiveObject] =
    JdbcSupport.queryList(connection, postgresCatalogSql)(readLiveObject)

  private def readControlObjects(connection: Connection): ControlSnapshot =
    try
      controlSnapshot(JdbcSupport.queryList(connection, controlSql)(readControlRow))
    catch
      case NonFatal(error) =>
        unavailableControlSnapshot(s"schema_control status unavailable: ${error.getMessage}")

  private def readControlRow(rs: ResultSet): ControlRow =
    ControlRow(
      kind = rs.getString("kind"),
      objectName = rs.getString("object_name"),
      sourceFile = rs.getString("source_file"),
      applyStatus = rs.getString("apply_status"),
      checksum = rs.getString("content_sha256"),
      expectedDdl = Option(rs.getString("canonical_sql")).filter(_.nonEmpty),
      appliedAt = timestampString(rs, "applied_at"),
      updatedAt = timestampString(rs, "updated_at")
    )

  private def timestampString(rs: ResultSet, column: String): Option[String] =
    Option(rs.getTimestamp(column)).map(_.toInstant.toString)

  private def readLiveObject(rs: ResultSet): LiveObject =
    LiveObject(
      key = ObjectKey(rs.getString("schema_name"), rs.getString("object_name"), rs.getString("object_type")),
      actualDdl = Option(rs.getString("actual_ddl")).filter(_.nonEmpty)
    )

  private def dbKindFor(jdbcUrl: String): DbKind =
    if jdbcUrl.trim.startsWith("jdbc:oracle:thin:") then DbKind.Oracle else DbKind.Postgres

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)

  private final case class PostgresSnapshot(objects: List[LiveObject], control: ControlSnapshot)

  private val excludedSchemas: String =
    "n.nspname <> 'information_schema' and n.nspname <> 'schema_control' and n.nspname not like 'pg_%'"

  private def notExtensionOwned(objId: String, catalog: String): String =
    s"""
    not exists (
      select 1
        from pg_depend dep
        join pg_extension ext on ext.oid = dep.refobjid
       where dep.classid = '$catalog'::regclass
         and dep.objid = $objId
         and dep.refclassid = 'pg_extension'::regclass
         and dep.deptype = 'e'
    )
    """

  private def notAutoDependencyOwned(objId: String, catalog: String): String =
    s"""
    not exists (
      select 1
        from pg_depend dep
       where dep.classid = '$catalog'::regclass
         and dep.objid = $objId
         and dep.deptype in ('a', 'i')
    )
    """

  private def notConstraintBackedIndex(indexId: String): String =
    s"""
    not exists (
      select 1
        from pg_constraint con
       where con.conindid = $indexId
    )
    """

  private val postgresCatalogSql: String =
    s"""
    select n.nspname as schema_name, n.nspname as object_name, 'schema' as object_type,
           format('create schema %I', n.nspname) as actual_ddl
      from pg_namespace n
     where $excludedSchemas
       and ${notExtensionOwned("n.oid", "pg_namespace")}
    union all
    select n.nspname, c.relname,
           case when c.relkind = 'S' then 'sequence' else 'table' end,
           null::text
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas
       and c.relkind in ('r', 'p', 'S')
       and ${notExtensionOwned("c.oid", "pg_class")}
       and (c.relkind <> 'S' or ${notAutoDependencyOwned("c.oid", "pg_class")})
    union all
    select n.nspname, c.relname,
           case when c.relkind = 'm' then 'materialized_view' else 'view' end,
           case
             when c.relkind = 'm' then format('create materialized view %I.%I as %s', n.nspname, c.relname, pg_get_viewdef(c.oid, true))
             else format('create view %I.%I as %s', n.nspname, c.relname, pg_get_viewdef(c.oid, true))
           end
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas
       and c.relkind in ('v', 'm')
       and ${notExtensionOwned("c.oid", "pg_class")}
    union all
    select n.nspname, c.relname, 'index', pg_get_indexdef(c.oid)
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas
       and c.relkind in ('i', 'I')
       and ${notExtensionOwned("c.oid", "pg_class")}
       and ${notConstraintBackedIndex("c.oid")}
    union all
    select n.nspname, e.extname, 'extension',
           format('create extension if not exists %I with schema %I', e.extname, n.nspname)
      from pg_extension e
      join pg_namespace n on n.oid = e.extnamespace
     where $excludedSchemas
    union all
    select n.nspname,
           p.proname,
           case when p.prokind = 'p' then 'procedure' else 'function' end,
           pg_get_functiondef(p.oid)
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where $excludedSchemas
       and p.prokind in ('f', 'p')
       and ${notExtensionOwned("p.oid", "pg_proc")}
    union all
    select n.nspname, c.relname || '.' || t.tgname, 'trigger', pg_get_triggerdef(t.oid, true)
      from pg_trigger t
      join pg_class c on c.oid = t.tgrelid
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas and not t.tgisinternal
       and ${notExtensionOwned("t.oid", "pg_trigger")}
    union all
    select n.nspname, typ.typname, 'type', null::text
      from pg_type typ
      join pg_namespace n on n.oid = typ.typnamespace
      left join pg_class c on c.oid = typ.typrelid
     where $excludedSchemas
       and typ.typtype in ('e', 'd', 'r', 'c')
       and (typ.typrelid = 0 or c.relkind = 'c')
       and ${notExtensionOwned("typ.oid", "pg_type")}
     order by 1, 3, 2
    """

  private val controlSql: String =
    """
    select kind, object_name, source_file, apply_status, content_sha256, canonical_sql, applied_at, updated_at
      from schema_control.schema_objects
     order by kind, object_name, source_file
    """
