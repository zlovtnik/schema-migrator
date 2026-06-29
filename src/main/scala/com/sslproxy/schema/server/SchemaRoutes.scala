package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.JdbcSupport
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.DiscoveryService
import com.sslproxy.schema.engine.ManifestBuilder
import com.sslproxy.schema.parser.Canonicalizer
import com.sslproxy.schema.store.{DriftItem, DriftResponse, Models, SchemaCatalogObject, SchemaCatalogResponse, StoredTarget, TargetStore}
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

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val log = LoggerFactory[IO].getLogger

  def routes(config: MigratorConfig, targetStore: TargetStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ GET -> Root / "schema" =>
        targetId(request).fold(RouteJson.badRequest("target_id is required")) { id =>
          targetStore.getStored(id).flatMap {
            case None => RouteJson.notFound(s"target '$id' was not found")
            case Some(target) => catalog(config, target).flatMap(response => RouteJson.ok(response.asJson))
          }
        }

      case request @ GET -> Root / "drift" =>
        targetId(request).fold(RouteJson.badRequest("target_id is required")) { id =>
          targetStore.getStored(id).flatMap {
            case None => RouteJson.notFound(s"target '$id' was not found")
            case Some(target) => drift(config, target).flatMap(response => RouteJson.ok(response.asJson))
          }
        }
    }

  private def catalog(config: MigratorConfig, target: StoredTarget): IO[SchemaCatalogResponse] =
    for
      now <- nowString
      kind = dbKindFor(target.target.jdbc_url)
      expected <- expectedObjects(config, kind, now)
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
                warnings = expected.warnings ++ snapshot.warnings
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

  private def drift(config: MigratorConfig, target: StoredTarget): IO[DriftResponse] =
    for
      now <- nowString
      kind = dbKindFor(target.target.jdbc_url)
      expected <- expectedObjects(config, kind, now)
      response <- kind match
        case DbKind.Oracle =>
          IO.pure(
            DriftResponse(
              target_id = target.target.id,
              db_kind = "oracle",
              supported = false,
              checked_at = now,
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
                items = driftItems(now, expected.objects, snapshot.objects, snapshot.control),
                warnings = expected.warnings ++ snapshot.warnings
              )
            case Left(error) =>
              DriftResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                items = Nil,
                warnings = expected.warnings :+ s"live Postgres drift introspection failed: ${error.getMessage}"
              )
          }
      _ <- log.info(Json.obj("event" -> Json.fromString("schema_drift"), "target_id" -> Json.fromString(target.target.id), "db_kind" -> Json.fromString(response.db_kind), "drift_item_count" -> Json.fromInt(response.items.size), "supported" -> Json.fromBoolean(response.supported)).noSpaces)
    yield response

  private def targetId(request: org.http4s.Request[IO]): Option[String] =
    request.uri.query.params.get("target_id").map(_.trim).filter(_.nonEmpty)

  private def expectedObjects(config: MigratorConfig, kind: DbKind, now: String): IO[ExpectedSnapshot] =
    if kind != DbKind.Postgres then IO.pure(ExpectedSnapshot(Nil, Nil))
    else if Files.notExists(config.sqlDir) || !Files.isDirectory(config.sqlDir) then
      IO.pure(ExpectedSnapshot(Nil, List(s"sql directory '${config.sqlDir}' is unavailable; returning live catalog only")))
    else
      (for
        discovery <- DiscoveryService[IO]().discover(config.sqlDir, DbKind.Postgres)
        objects <- ManifestBuilder[IO](SqlDialect.Postgres).build(discovery.files)
        expected = objects.map { item =>
          val objectType = objectTypeForKind(item.kind)
          val (schema, name) = schemaAndName(objectType, item.objectName)
          ExpectedObject(
            key = ObjectKey(schema, name, objectType),
            sourceFile = item.sourceFile,
            checksum = item.sha256,
            expectedDdl = item.rawSql,
            applyStatus = None
          )
        }
      yield ExpectedSnapshot(expected, discovery.warnings)).handleError { case NonFatal(error) =>
        ExpectedSnapshot(Nil, List(s"SQL manifest could not be loaded: ${error.getMessage}"))
      }

  private def postgresSnapshot(target: StoredTarget): IO[PostgresSnapshot] =
    IO.blocking {
      Class.forName("org.postgresql.Driver")
      val connection = connect(target)
      try
        val objects = readPostgresObjects(connection)
        val control = readControlObjects(connection)
        PostgresSnapshot(objects, control.objects, control.warnings)
      finally connection.close()
    }

  private def connect(target: StoredTarget): Connection =
    DriverManager.getConnection(
      target.target.jdbc_url,
      JdbcConnectionProperties.withTimeouts(target.target.jdbc_url, target.password, 5.seconds)
    )

  private def readPostgresObjects(connection: Connection): List[LiveObject] =
    JdbcSupport.queryList(connection, postgresCatalogSql)(readLiveObject)

  private def readControlObjects(connection: Connection): ControlSnapshot =
    try
      val objects = JdbcSupport
        .queryList(connection, controlSql) { rs =>
          val objectType = objectTypeForKind(rs.getString("kind"))
          val (schema, name) = schemaAndName(objectType, rs.getString("object_name"))
          ObjectKey(schema, name, objectType) -> ControlObject(
            applyStatus = rs.getString("apply_status"),
            sourceFile = rs.getString("source_file"),
            checksum = rs.getString("content_sha256"),
            expectedDdl = Option(rs.getString("canonical_sql")).filter(_.nonEmpty)
          )
        }
        .toMap
      ControlSnapshot(objects, Nil)
    catch
      case NonFatal(error) =>
        ControlSnapshot(Map.empty, List(s"schema_control status unavailable: ${error.getMessage}"))

  private def readLiveObject(rs: ResultSet): LiveObject =
    LiveObject(
      key = ObjectKey(rs.getString("schema_name"), rs.getString("object_name"), rs.getString("object_type")),
      actualDdl = Option(rs.getString("actual_ddl")).filter(_.nonEmpty)
    )

  private def mergeCatalog(
    now: String,
    expected: List[ExpectedObject],
    actual: List[LiveObject],
    control: Map[ObjectKey, ControlObject]
  ): List[SchemaCatalogObject] =
    val expectedByKey = expected.map(item => item.key -> item).toMap
    val actualByKey = actual.map(item => item.key -> item).toMap
    val keys = (expectedByKey.keySet ++ actualByKey.keySet ++ control.keySet).toList.sorted
    keys.map { key =>
      val expectedItem = expectedByKey.get(key)
      val actualItem = actualByKey.get(key)
      val controlItem = control.get(key)
      val applyStatus = controlItem.map(_.applyStatus).orElse(expectedItem.flatMap(_.applyStatus))
      val status =
        applyStatus match
          case Some("pending" | "failed") => "pending_migration"
          case _
              if definitionChanged(
                key,
                expectedItem.map(_.expectedDdl).orElse(controlItem.flatMap(_.expectedDdl)),
                actualItem.flatMap(_.actualDdl)
              ) =>
            "drift_detected"
          case _ if expectedItem.nonEmpty && actualItem.nonEmpty => "in_sync"
          case _ if expectedItem.nonEmpty => "drift_detected"
          case _ => "unknown"
      SchemaCatalogObject(
        schema = key.schema,
        name = key.name,
        object_type = key.objectType,
        status = status,
        source_file = controlItem.map(_.sourceFile).orElse(expectedItem.map(_.sourceFile)),
        checksum = controlItem.map(_.checksum).orElse(expectedItem.map(_.checksum)),
        apply_status = applyStatus,
        actual_ddl = actualItem.flatMap(_.actualDdl),
        expected_ddl = expectedItem.map(_.expectedDdl).orElse(controlItem.flatMap(_.expectedDdl)),
        last_checked = now
      )
    }

  private def driftItems(
    now: String,
    expected: List[ExpectedObject],
    actual: List[LiveObject],
    control: Map[ObjectKey, ControlObject]
  ): List[DriftItem] =
    val expectedByKey = expected.map(item => item.key -> item).toMap
    val actualByKey = actual.map(item => item.key -> item).toMap
    val missingActual =
      expectedByKey.values.toList
        .filterNot(item => actualByKey.contains(item.key))
        .map(item =>
          DriftItem(
            schema = item.key.schema,
            name = item.key.name,
            object_type = item.key.objectType,
            drift_type = "missing_actual",
            expected = item.sourceFile,
            actual = "not present in live Postgres catalog",
            source_file = Some(item.sourceFile),
            checksum = Some(item.checksum),
            detected_at = now
          )
        )
    val untrackedActual =
      actualByKey.values.toList
        .filterNot(item => expectedByKey.contains(item.key))
        .filterNot(item => item.key.schema == "schema_control")
        .map(item =>
          DriftItem(
            schema = item.key.schema,
            name = item.key.name,
            object_type = item.key.objectType,
            drift_type = "untracked_actual",
            expected = "not defined in SQL manifest",
            actual = item.actualDdl.getOrElse("present in live Postgres catalog"),
            source_file = None,
            checksum = None,
            detected_at = now
          )
        )
    val pendingOrFailed =
      control.toList.collect {
        case (key, value) if value.applyStatus == "pending" || value.applyStatus == "failed" =>
          DriftItem(
            schema = key.schema,
            name = key.name,
            object_type = key.objectType,
            drift_type = "pending_or_failed_control",
            expected = value.sourceFile,
            actual = s"schema_control apply_status=${value.applyStatus}",
            source_file = Some(value.sourceFile),
            checksum = Some(value.checksum),
            detected_at = now
          )
      }
    val changedDefinitions =
      expectedByKey.values.toList.flatMap { expectedItem =>
        val controlItem = control.get(expectedItem.key)
        if controlItem.exists(item => item.applyStatus == "pending" || item.applyStatus == "failed") then None
        else
          actualByKey.get(expectedItem.key).flatMap { actualItem =>
            actualItem.actualDdl
              .filter(actualDdl => definitionChanged(expectedItem.key, Some(expectedItem.expectedDdl), Some(actualDdl)))
              .map { actualDdl =>
                DriftItem(
                  schema = expectedItem.key.schema,
                  name = expectedItem.key.name,
                  object_type = expectedItem.key.objectType,
                  drift_type = "definition_changed",
                  expected = expectedItem.expectedDdl,
                  actual = actualDdl,
                  source_file = Some(expectedItem.sourceFile),
                  checksum = Some(expectedItem.checksum),
                  detected_at = now
                )
              }
          }
      }
    (missingActual ++ untrackedActual ++ changedDefinitions ++ pendingOrFailed).sortBy(item =>
      (item.schema, item.object_type, item.name, item.drift_type)
    )

  private def dbKindFor(jdbcUrl: String): DbKind =
    if jdbcUrl.trim.startsWith("jdbc:oracle:thin:") then DbKind.Oracle else DbKind.Postgres

  private def objectTypeForKind(kind: String): String =
    kind match
      case "schema" => "schema"
      case "type" => "type"
      case "table" => "table"
      case "index" => "index"
      case "view" => "view"
      case "materialized_view" => "materialized_view"
      case "function" => "function"
      case "procedure" => "procedure"
      case "trigger" => "trigger"
      case "extension" => "extension"
      case other => other

  private def schemaAndName(objectType: String, objectName: String): (String, String) =
    val trimmed = objectName.trim
    if objectType == "schema" then trimmed -> trimmed
    else
      val dot = trimmed.indexOf('.')
      if dot > 0 && dot < trimmed.length - 1 then trimmed.take(dot) -> trimmed.drop(dot + 1)
      else "public" -> trimmed

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)

  private def definitionChanged(key: ObjectKey, expectedDdl: Option[String], actualDdl: Option[String]): Boolean =
    comparableDefinitionTypes.contains(key.objectType) &&
      expectedDdl.exists(expected => actualDdl.exists(actual => canonicalDdl(expected) != canonicalDdl(actual)))

  private def canonicalDdl(value: String): String =
    Canonicalizer
      .canonicalize(value, SqlDialect.Postgres)
      .stripSuffix(";")
      .trim

  private val comparableDefinitionTypes: Set[String] =
    Set("function", "procedure", "view", "materialized_view", "trigger")

  private final case class ObjectKey(schema: String, name: String, objectType: String) extends Ordered[ObjectKey]:
    override def compare(that: ObjectKey): Int =
      Ordering.Tuple3[String, String, String].compare((schema, objectType, name), (that.schema, that.objectType, that.name))

  private final case class ExpectedObject(
    key: ObjectKey,
    sourceFile: String,
    checksum: String,
    expectedDdl: String,
    applyStatus: Option[String]
  ):
    def toCatalog(now: String, actual: Option[LiveObject], status: String): SchemaCatalogObject =
      SchemaCatalogObject(
        schema = key.schema,
        name = key.name,
        object_type = key.objectType,
        status = status,
        source_file = Some(sourceFile),
        checksum = Some(checksum),
        apply_status = applyStatus,
        actual_ddl = actual.flatMap(_.actualDdl),
        expected_ddl = Some(expectedDdl),
        last_checked = now
      )

  private final case class LiveObject(key: ObjectKey, actualDdl: Option[String])
  private final case class ControlObject(applyStatus: String, sourceFile: String, checksum: String, expectedDdl: Option[String])
  private final case class ExpectedSnapshot(objects: List[ExpectedObject], warnings: List[String])
  private final case class PostgresSnapshot(objects: List[LiveObject], control: Map[ObjectKey, ControlObject], warnings: List[String])
  private final case class ControlSnapshot(objects: Map[ObjectKey, ControlObject], warnings: List[String])

  private val excludedSchemas: String =
    "n.nspname <> 'information_schema' and n.nspname not like 'pg_%'"

  private val postgresCatalogSql: String =
    s"""
    select n.nspname as schema_name, n.nspname as object_name, 'schema' as object_type,
           format('create schema %I', n.nspname) as actual_ddl
      from pg_namespace n
     where $excludedSchemas
    union all
    select n.nspname, c.relname,
           case when c.relkind = 'S' then 'sequence' else 'table' end,
           null::text
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas and c.relkind in ('r', 'p', 'S')
    union all
    select n.nspname, c.relname,
           case when c.relkind = 'm' then 'materialized_view' else 'view' end,
           case
             when c.relkind = 'm' then format('create materialized view %I.%I as %s', n.nspname, c.relname, pg_get_viewdef(c.oid, true))
             else format('create view %I.%I as %s', n.nspname, c.relname, pg_get_viewdef(c.oid, true))
           end
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas and c.relkind in ('v', 'm')
    union all
    select schemaname, indexname, 'index', indexdef
      from pg_indexes
     where schemaname <> 'information_schema' and schemaname not like 'pg_%'
    union all
    select n.nspname, e.extname, 'extension',
           format('create extension if not exists %I with schema %I', e.extname, n.nspname)
      from pg_extension e
      join pg_namespace n on n.oid = e.extnamespace
     where $excludedSchemas
    union all
    select n.nspname,
           p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
           case when p.prokind = 'p' then 'procedure' else 'function' end,
           pg_get_functiondef(p.oid)
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where $excludedSchemas and p.prokind in ('f', 'p')
    union all
    select n.nspname, c.relname || '.' || t.tgname, 'trigger', pg_get_triggerdef(t.oid, true)
      from pg_trigger t
      join pg_class c on c.oid = t.tgrelid
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas and not t.tgisinternal
    union all
    select n.nspname, typ.typname, 'type', null::text
      from pg_type typ
      join pg_namespace n on n.oid = typ.typnamespace
      left join pg_class c on c.oid = typ.typrelid
     where $excludedSchemas
       and typ.typtype in ('e', 'd', 'r', 'c')
       and (typ.typrelid = 0 or c.relkind = 'c')
     order by 1, 3, 2
    """

  private val controlSql: String =
    """
    select kind, object_name, source_file, apply_status, content_sha256, canonical_sql
      from schema_control.schema_objects
    """
