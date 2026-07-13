package com.sslproxy.schema.server

import cats.effect.IO
import com.sslproxy.schema.db.{JdbcConnectionConfig, JdbcSupport}

import java.sql.{Connection, ResultSet}
import scala.util.control.NonFatal

private[schema] object PostgresCatalogReader:
  import PostgresDriftAnalyzer.*
  import PostgresDriftModel.ControlObject

  final case class Snapshot(
    objects: List[LiveObject],
    control: ControlSnapshot,
    expected: List[ExpectedObject] = Nil
  )

  def snapshot(config: JdbcConnectionConfig, expected: List[ExpectedObject] = Nil): IO[Snapshot] =
    JdbcSupport.connection(config).use(connection => IO.blocking(readSnapshot(connection, expected)))

  def readSnapshot(connection: Connection): Snapshot =
    Snapshot(readPostgresObjects(connection), readControlObjects(connection))

  def readSnapshot(connection: Connection, expected: List[ExpectedObject]): Snapshot =
    val snapshot = readSnapshot(connection)
    val (canonicalExpected, expectedWarnings) = canonicalizeExpectedViews(connection, expected)
    val (canonicalControlObjects, controlWarnings) = canonicalizeControlViews(connection, snapshot.control.objects)
    snapshot.copy(
      control = snapshot.control.copy(
        objects = canonicalControlObjects,
        warnings = snapshot.control.warnings ++ expectedWarnings ++ controlWarnings
      ),
      expected = canonicalExpected
    )

  private def canonicalizeExpectedViews(
    connection: Connection,
    expected: List[ExpectedObject]
  ): (List[ExpectedObject], List[String]) =
    canonicalizeViews(
      connection,
      expected,
      _.key,
      _.expectedDdl,
      (item, canonical) => item.copy(expectedDdl = Some(canonical)),
      "expected"
    )

  private def canonicalizeControlViews(
    connection: Connection,
    control: List[ControlObject]
  ): (List[ControlObject], List[String]) =
    canonicalizeViews(
      connection,
      control,
      _.key,
      _.expectedDdl,
      (item, canonical) => item.copy(expectedDdl = Some(canonical)),
      "control"
    )

  private def canonicalizeViews[A](
    connection: Connection,
    items: List[A],
    itemKey: A => ObjectKey,
    itemDdl: A => Option[String],
    updateDdl: (A, String) => A,
    probePrefix: String
  ): (List[A], List[String]) =
    val results = items.zipWithIndex.map { case (item, index) =>
      val key = itemKey(item)
      itemDdl(item) match
        case Some(ddl) if isViewType(key.objectType) =>
          canonicalViewDdl(connection, key, ddl, s"${probePrefix}_$index") match
            case Right(canonical) => updateDdl(item, canonical) -> None
            case Left(message) => item -> Some(message)
        case _ => item -> None
    }
    results.map(_._1) -> results.flatMap(_._2).distinct

  private def canonicalViewDdl(
    connection: Connection,
    key: ObjectKey,
    ddl: String,
    probeSuffix: String
  ): Either[String, String] =
    PostgresDriftDdlParser
      .viewQuery(ddl)
      .toRight(s"could not isolate ${key.objectType} query for ${key.name}")
      .flatMap { query =>
        val probeName = s"bedrock_drift_$probeSuffix"
        val create = connection.createStatement()
        try
          create.execute(s"create temporary view $probeName as $query")
          val definition = readCanonicalView(connection, probeName)
          definition
            .toRight(s"Postgres did not return a canonical definition for ${key.objectType} ${key.name}")
            .map(value => s"create view $probeName as $value")
        catch
          case NonFatal(error) =>
            Left(s"could not canonicalize ${key.objectType} ${key.name} with Postgres: ${error.getMessage}")
        finally
          create.close()
          dropTemporaryView(connection, probeName)
      }

  private def readCanonicalView(connection: Connection, probeName: String): Option[String] =
    val statement = connection.prepareStatement("select pg_get_viewdef(?::regclass, true)")
    try
      statement.setString(1, s"pg_temp.$probeName")
      val resultSet = statement.executeQuery()
      try Option.when(resultSet.next())(resultSet.getString(1)).flatMap(Option(_))
      finally resultSet.close()
    finally statement.close()

  private def dropTemporaryView(connection: Connection, probeName: String): Unit =
    val statement = connection.createStatement()
    try statement.execute(s"drop view if exists pg_temp.$probeName")
    catch case NonFatal(_) => ()
    finally statement.close()

  private def isViewType(objectType: String): Boolean =
    objectType == "view" || objectType == "materialized_view"

  private def readPostgresObjects(connection: Connection): List[LiveObject] =
    JdbcSupport.queryList(connection, postgresCatalogSql)(readLiveObject)

  private def readControlObjects(connection: Connection): ControlSnapshot =
    try controlSnapshot(JdbcSupport.queryList(connection, controlSql)(readControlRow))
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
    select n.nspname as schema_name, n.nspname::text as object_name, 'schema' as object_type,
           format('create schema %I', n.nspname) as actual_ddl
      from pg_namespace n
     where $excludedSchemas
       and ${notExtensionOwned("n.oid", "pg_namespace")}
    union all
    select n.nspname, c.relname::text,
           case when c.relkind = 'S' then 'sequence' else 'table' end,
           null::text
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas
       and c.relkind in ('r', 'p', 'S')
       and ${notExtensionOwned("c.oid", "pg_class")}
       and (c.relkind <> 'S' or ${notAutoDependencyOwned("c.oid", "pg_class")})
    union all
    select n.nspname, c.relname::text,
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
    select n.nspname, c.relname::text, 'index', pg_get_indexdef(c.oid)
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas
       and c.relkind in ('i', 'I')
       and ${notExtensionOwned("c.oid", "pg_class")}
       and ${notConstraintBackedIndex("c.oid")}
    union all
    select n.nspname, e.extname::text, 'extension',
           format('create extension if not exists %I with schema %I', e.extname, n.nspname)
      from pg_extension e
      join pg_namespace n on n.oid = e.extnamespace
     where $excludedSchemas
    union all
    select n.nspname,
           concat(p.proname::text, '(', oidvectortypes(p.proargtypes)::text, ')'),
           case when p.prokind = 'p' then 'procedure' else 'function' end,
           pg_get_functiondef(p.oid)
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where $excludedSchemas
       and p.prokind in ('f', 'p')
       and ${notExtensionOwned("p.oid", "pg_proc")}
    union all
    select n.nspname, concat(c.relname::text, '.', t.tgname::text), 'trigger', pg_get_triggerdef(t.oid, true)
      from pg_trigger t
      join pg_class c on c.oid = t.tgrelid
      join pg_namespace n on n.oid = c.relnamespace
     where $excludedSchemas and not t.tgisinternal
       and ${notExtensionOwned("t.oid", "pg_trigger")}
    union all
    select n.nspname, typ.typname::text, 'type', null::text
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
