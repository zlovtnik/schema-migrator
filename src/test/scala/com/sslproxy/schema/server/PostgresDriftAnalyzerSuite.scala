package com.sslproxy.schema.server

import com.sslproxy.schema.engine.SchemaObject
import munit.FunSuite

class PostgresDriftAnalyzerSuite extends FunSuite:
  import PostgresDriftAnalyzer.*

  private val now = "2026-07-02T12:00:00Z"

  test("extracts schema-qualified Postgres function names with signatures") {
    val routines = routineDefinitions(
      """create or replace function coordinator.ensure_cursor(
        |  p_stream_name text,
        |  p_default_cursor text default '0'
        |)
        |returns text
        |language sql
        |as $$ select p_default_cursor $$;
        |""".stripMargin
    )

    assertEquals(routines.map(_.key), List(ObjectKey("coordinator", "ensure_cursor(text, text)", "function")))
  }

  test("extracts unqualified Postgres functions as public schema objects") {
    val routines = routineDefinitions(
      """create or replace function sync_stable_uuid(value text)
        |returns uuid
        |language sql
        |as $$ select gen_random_uuid() $$;
        |""".stripMargin
    )

    assertEquals(routines.map(_.key), List(ObjectKey("public", "sync_stable_uuid(text)", "function")))
  }

  test("normalizes routine signature aliases and type case") {
    val routines = routineDefinitions(
      """create or replace function search_purge_expired_queries(p_now timestamptz default now())
        |returns bigint
        |language plpgsql
        |as $$ begin return 0; end; $$;
        |
        |create or replace function vec_reembed_changed_jobs(p_limit INTEGER default 100)
        |returns integer
        |language plpgsql
        |as $$ begin return p_limit; end; $$;
        |""".stripMargin
    )

    assertEquals(
      routines.map(_.key),
      List(
        ObjectKey("public", "search_purge_expired_queries(timestamp with time zone)", "function"),
        ObjectKey("public", "vec_reembed_changed_jobs(integer)", "function")
      )
    )
  }

  test("extracts multiple routines from grouped files and ignores quoted bodies") {
    val sql =
      """-- create function ignored_comment()
        |create or replace function coordinator.safe_int(p_value text)
        |returns integer
        |language plpgsql
        |as $$
        |begin
        |  execute 'create function ignored_string() returns int language sql as ''select 1''';
        |  return p_value::integer;
        |end;
        |$$;
        |
        |create or replace function coordinator.safe_bool(p_value text)
        |returns boolean
        |language sql
        |as $$ select p_value::boolean $$;
        |""".stripMargin

    assertEquals(
      routineDefinitions(sql).map(_.key),
      List(ObjectKey("coordinator", "safe_int(text)", "function"), ObjectKey("coordinator", "safe_bool(text)", "function"))
    )
  }

  test("extracts procedures") {
    val routines = routineDefinitions(
      """create or replace procedure coordinator.refresh_rollup()
        |language plpgsql
        |as $$
        |begin
        |  perform 1;
        |end;
        |$$;
        |""".stripMargin
    )

    assertEquals(routines.map(_.key), List(ObjectKey("coordinator", "refresh_rollup()", "procedure")))
  }

  test("manifest extraction uses grouped routine names instead of descriptive headers") {
    val expected = expectedFromManifest(
      schemaObject(
        kind = "function",
        objectName = "coordinator.safe helpers",
        sourceFile = "functions/002_coordinator_safe_helpers.sql",
        rawSql =
          """-- object: coordinator.safe helpers
            |create or replace function coordinator.safe_int(p_value text)
            |returns integer language sql as $$ select p_value::integer $$;
            |
            |create or replace function coordinator.safe_bool(p_value text)
            |returns boolean language sql as $$ select p_value::boolean $$;
            |""".stripMargin
      )
    )

    assertEquals(
      expected.map(_.key),
      List(ObjectKey("coordinator", "safe_int(text)", "function"), ObjectKey("coordinator", "safe_bool(text)", "function"))
    )
  }

  test("manifest extraction uses concrete index names from grouped index files") {
    val expected = expectedFromManifest(
      schemaObject(
        kind = "index",
        objectName = "sync plane indexes",
        sourceFile = "indexes/001_sync_events_indexes.sql",
        rawSql =
          """-- object: sync plane indexes
            |create index if not exists sync_events_status_idx on sync_events (status, observed_at);
            |create unique index if not exists wireless_authorized_networks_match_idx
            |  on wireless_authorized_networks (coalesce(lower(ssid), ''));
            |""".stripMargin
      )
    )

    assertEquals(
      expected.map(_.key),
      List(
        ObjectKey("public", "sync_events_status_idx", "index"),
        ObjectKey("public", "wireless_authorized_networks_match_idx", "index")
      )
    )
  }

  test("manifest extraction uses concrete extension names and ignores dynamic optional extension blocks") {
    val expected = expectedFromManifest(
      schemaObject(
        kind = "extension",
        objectName = "extensions",
        sourceFile = "extensions/001_extensions.sql",
        rawSql =
          """-- object: extensions
            |create extension if not exists pg_trgm;
            |create extension if not exists vector;
            |
            |do $$
            |begin
            |  execute 'create extension if not exists pg_cron';
            |exception when others then
            |  raise notice 'pg_cron unavailable';
            |end $$;
            |""".stripMargin
      )
    )

    assertEquals(
      expected.map(_.key),
      List(ObjectKey("public", "pg_trgm", "extension"), ObjectKey("public", "vector", "extension"))
    )
  }

  test("manifest extraction uses view DDL names instead of override headers") {
    val expected = expectedFromManifest(
      schemaObject(
        kind = "view",
        objectName = "v_wireless_session_timeline override",
        sourceFile = "views/999_v_wireless_session_timeline_override.sql",
        rawSql =
          """-- object: v_wireless_session_timeline override
            |create or replace view v_wireless_session_timeline as
            |select 1 as id;
            |""".stripMargin
      )
    )

    assertEquals(expected.map(_.key), List(ObjectKey("public", "v_wireless_session_timeline", "view")))
  }

  test("manifest extraction covers schema table materialized view and trigger DDL") {
    val expected = expectedFromManifest(
      schemaObject(
        kind = "sql_file",
        objectName = "mixed ddl",
        sourceFile = "mixed/001_catalog_objects.sql",
        rawSql =
          """create schema if not exists coordinator;
            |create table if not exists public.sync_events (id bigint primary key);
            |create materialized view mv_ap_risk_score as select 1 as score;
            |create trigger vec_alert_reembed after insert on vec_alerts
            |  for each row execute function vec_reembed_on_alert();
            |""".stripMargin
      )
    )

    assertEquals(
      expected.map(_.key),
      List(
        ObjectKey("coordinator", "coordinator", "schema"),
        ObjectKey("public", "sync_events", "table"),
        ObjectKey("public", "mv_ap_risk_score", "materialized_view"),
        ObjectKey("public", "vec_alerts.vec_alert_reembed", "trigger")
      )
    )
  }

  test("alter-only hardening files do not invent missing catalog objects") {
    val sql =
      """-- object: device_graph_workmap_hardening_columns
        |alter table sync_backlog
        |  add column if not exists max_attempts integer not null default 5;
        |""".stripMargin
    val expected = expectedFromManifest(
      schemaObject("table", "device_graph_workmap_hardening_columns", "tables/031_device_graph_workmap_hardening.sql", sql)
    )
    val control = controlSnapshot(
      List(controlRow("table", "device_graph_workmap_hardening_columns", "tables/031_device_graph_workmap_hardening.sql", "skipped", Some(sql)))
    )

    assertEquals(expected, Nil)
    assertEquals(control.objects, Nil)
    assertEquals(driftItems(now, expected, Nil, control), Nil)
    assertEquals(control.summary.map(_.skipped_count), Some(1L))
  }

  test("matching grouped index control rows do not produce paired missing and untracked drift") {
    val sql =
      """create index if not exists sync_events_status_idx on sync_events (status, observed_at);
        |create index if not exists sync_events_stream_idx on sync_events (stream_name, observed_at);
        |""".stripMargin
    val expectedObjects = expectedFromManifest(
      schemaObject("index", "sync plane indexes", "indexes/001_sync_events_indexes.sql", sql)
    )
    val actualObjects = expectedObjects.map(item => LiveObject(item.key, item.expectedDdl))
    val control = controlSnapshot(
      List(controlRow("index", "sync plane indexes", "indexes/001_sync_events_indexes.sql", "skipped", Some(sql)))
    )

    assertEquals(driftItems(now, expectedObjects, actualObjects, control), Nil)
  }

  test("grouped source-file fallback does not reuse sibling control state") {
    val sourceFile = "views/001_grouped_views.sql"
    val staleSql = "create or replace view stale_view as select 1 as id;"
    val currentSql = "create or replace view current_view as select 1 as id;"
    val siblingSql = "create or replace view sibling_view as select 2 as id;"
    val staleKey = ObjectKey("public", "stale_view", "view")
    val currentKey = ObjectKey("public", "current_view", "view")
    val siblingKey = ObjectKey("public", "sibling_view", "view")
    val expectedObjects = List(
      expected(staleKey, sourceFile = sourceFile, expectedDdl = Some(staleSql)),
      expected(currentKey, sourceFile = sourceFile, expectedDdl = Some(currentSql))
    )
    val control = ControlSnapshot(
      List(
        ControlObject(staleKey, "applied", sourceFile, "sha-stale", Some(staleSql), "stale_view"),
        ControlObject(siblingKey, "failed", sourceFile, "sha-sibling", Some(siblingSql), "sibling_view")
      ),
      None,
      Nil
    )
    val actualObjects = List(LiveObject(currentKey, Some(currentSql)))

    val catalog = mergeCatalog(now, expectedObjects, actualObjects, control)
    val current = catalog.find(_.name == "current_view").get
    val drift = driftItems(now, expectedObjects, actualObjects, control)

    assertEquals(current.apply_status, None)
    assertEquals(current.expected_ddl, Some(currentSql))
    assertEquals(current.status, "in_sync")
    assert(!drift.exists(_.name == "current_view"))
  }

  test("duplicate view definitions use the later manifest definition") {
    val oldSql = "create or replace view v_wireless_session_timeline as select 1 as id;"
    val newSql = "create or replace view v_wireless_session_timeline as select 2 as id;"
    val key = ObjectKey("public", "v_wireless_session_timeline", "view")
    val items = driftItems(
      now,
      List(
        expected(key, sourceFile = "views/004_v_wireless_session_timeline.sql", expectedDdl = Some(oldSql)),
        expected(key, sourceFile = "views/999_v_wireless_session_timeline_override.sql", expectedDdl = Some(newSql))
      ),
      List(LiveObject(key, Some(newSql))),
      ControlSnapshot(Nil, None, Nil)
    )

    assertEquals(items, Nil)
  }

  test("duplicate routine definitions use the later manifest definition") {
    val oldSql = "create or replace function public.demo() returns int language sql as $$ select 1 $$;"
    val newSql = "create or replace function public.demo() returns int language sql as $$ select 2 $$;"
    val items = driftItems(
      now,
      List(
        expected(ObjectKey("public", "demo()", "function"), expectedDdl = Some(oldSql)),
        expected(ObjectKey("public", "demo()", "function"), expectedDdl = Some(newSql))
      ),
      List(LiveObject(ObjectKey("public", "demo()", "function"), Some(newSql))),
      ControlSnapshot(Nil, None, Nil)
    )

    assertEquals(items, Nil)
  }

  test("function comparison ignores Postgres formatting case schema qualification and dollar tag names") {
    val expectedSql =
      """create or replace function sync_stable_uuid(value text)
        |returns uuid
        |language sql
        |immutable
        |as $$
        |  select (
        |    substr(md5(value), 1, 8) || '-' ||
        |    substr(md5(value), 9, 4) || '-' ||
        |    substr(md5(value), 13, 4) || '-' ||
        |    substr(md5(value), 17, 4) || '-' ||
        |    substr(md5(value), 21, 12)
        |  )::uuid
        |$$;
        |""".stripMargin
    val actualSql =
      """CREATE OR REPLACE FUNCTION public.sync_stable_uuid(value text)
        | RETURNS uuid
        | LANGUAGE sql
        | IMMUTABLE
        |AS $function$
        |  select (
        |    substr(md5(value), 1, 8) || '-' ||
        |    substr(md5(value), 9, 4) || '-' ||
        |    substr(md5(value), 13, 4) || '-' ||
        |    substr(md5(value), 17, 4) || '-' ||
        |    substr(md5(value), 21, 12)
        |  )::uuid
        |$function$
        |""".stripMargin
    val key = ObjectKey("public", "sync_stable_uuid(text)", "function")

    assertEquals(
      driftItems(now, List(expected(key, expectedDdl = Some(expectedSql))), List(LiveObject(key, Some(actualSql))), ControlSnapshot(Nil, None, Nil)),
      Nil
    )
  }

  test("function comparison ignores Postgres wrapper formatting for plpgsql bodies") {
    val expectedSql =
      """create or replace function coordinator.list_pending_backlog()
        |returns jsonb
        |language plpgsql
        |as $$
        |declare
        |  v_result jsonb;
        |begin
        |  perform coordinator.fail_exhausted_backlog();
        |
        |  select coalesce(jsonb_agg(
        |    jsonb_build_object(
        |      'dedupe_key', dedupe_key,
        |      'stream_name', stream_name,
        |      'payload', payload,
        |      'failure_stage', failure_stage,
        |      'attempt_count', attempt_count,
        |      'max_attempts', max_attempts,
        |      'created_at', created_at
        |    ) order by created_at asc
        |  ), '[]'::jsonb)
        |    into v_result
        |  from (
        |    select dedupe_key, stream_name, payload, failure_stage, attempt_count, max_attempts, created_at
        |    from sync_backlog
        |    where status = 'pending'
        |      and attempt_count < max_attempts
        |    order by created_at asc
        |    limit 100
        |  ) pending;
        |
        |  return v_result;
        |end;
        |$$;
        |""".stripMargin
    val actualSql =
      """CREATE OR REPLACE FUNCTION coordinator.list_pending_backlog()
        | RETURNS jsonb
        | LANGUAGE plpgsql
        |AS $function$
        |declare
        |  v_result jsonb;
        |begin
        |  perform coordinator.fail_exhausted_backlog();
        |
        |  select coalesce(jsonb_agg(
        |    jsonb_build_object(
        |      'dedupe_key', dedupe_key,
        |      'stream_name', stream_name,
        |      'payload', payload,
        |      'failure_stage', failure_stage,
        |      'attempt_count', attempt_count,
        |      'max_attempts', max_attempts,
        |      'created_at', created_at
        |    ) order by created_at asc
        |  ), '[]'::jsonb)
        |    into v_result
        |  from (
        |    select dedupe_key, stream_name, payload, failure_stage, attempt_count, max_attempts, created_at
        |    from sync_backlog
        |    where status = 'pending'
        |      and attempt_count < max_attempts
        |    order by created_at asc
        |    limit 100
        |  ) pending;
        |
        |  return v_result;
        |end;
        |$function$
        |""".stripMargin
    val key = ObjectKey("coordinator", "list_pending_backlog()", "function")

    assertEquals(
      driftItems(now, List(expected(key, expectedDdl = Some(expectedSql))), List(LiveObject(key, Some(actualSql))), ControlSnapshot(Nil, None, Nil)),
      Nil
    )
  }

  test("matching routines do not produce paired missing and untracked drift") {
    val sql =
      """create or replace function coordinator.ensure_cursor(p_stream_name text)
        |returns text language sql as $$ select p_stream_name $$;
        |""".stripMargin
    val expectedObjects = expectedFromManifest(
      schemaObject("function", "coordinator.ensure_cursor", "functions/020_coordinator_ensure_cursor.sql", sql)
    )
    val actualSql = expectedObjects.head.expectedDdl.getOrElse(sql)
    val control = controlSnapshot(
      List(controlRow("function", "coordinator.ensure_cursor", "functions/020_coordinator_ensure_cursor.sql", "applied", Some(sql)))
    )

    assertEquals(
      driftItems(now, expectedObjects, List(LiveObject(ObjectKey("coordinator", "ensure_cursor(text)", "function"), Some(actualSql))), control),
      Nil
    )
  }

  test("routine aliases do not produce paired missing and untracked drift") {
    val sql =
      """create or replace function vec_build_baseline_profiles(
        |  p_from timestamptz default now(),
        |  p_to timestamptz default now(),
        |  p_window interval default interval '15 minutes'
        |)
        |returns integer
        |language plpgsql
        |as $$
        |begin
        |  return 1;
        |end;
        |$$;
        |""".stripMargin
    val actualSql =
      """CREATE OR REPLACE FUNCTION public.vec_build_baseline_profiles(
        |  p_from timestamp with time zone DEFAULT now(),
        |  p_to timestamp with time zone DEFAULT now(),
        |  p_window interval DEFAULT interval '15 minutes'
        |)
        | RETURNS integer
        | LANGUAGE plpgsql
        |AS $function$
        |begin
        |  return 1;
        |end;
        |$function$
        |""".stripMargin
    val expectedObjects = expectedFromManifest(
      schemaObject("function", "vec_build_baseline_profiles", "functions/009_vec_build_baseline_profiles.sql", sql)
    )
    val actualObjects = List(
      LiveObject(
        ObjectKey("public", "vec_build_baseline_profiles(timestamp with time zone, timestamp with time zone, interval)", "function"),
        Some(actualSql)
      )
    )

    assertEquals(driftItems(now, expectedObjects, actualObjects, ControlSnapshot(Nil, None, Nil)), Nil)
  }

  test("long routine signatures keep the full key instead of truncating at 63 characters") {
    val sql =
      """create or replace function coordinator.process_ingest_ledger(
        |  p_stream_names text[],
        |  p_oracle_stream_names text[],
        |  p_max_attempts integer,
        |  p_backoff_secs integer,
        |  p_batch_size integer default 200
        |)
        |returns integer
        |language plpgsql
        |as $$ begin return p_batch_size; end; $$;
        |""".stripMargin
    val expectedObjects = expectedFromManifest(
      schemaObject("function", "coordinator.process_ingest_ledger", "functions/023_coordinator_process_ingest_ledger.sql", sql)
    )
    val key = ObjectKey("coordinator", "process_ingest_ledger(text[], text[], integer, integer, integer)", "function")

    assert(key.name.length > 63)
    assertEquals(
      driftItems(now, expectedObjects, List(LiveObject(key, Some(sql))), ControlSnapshot(Nil, None, Nil)),
      Nil
    )
  }

  test("routine comparison ignores comments spacing case public qualification and type aliases") {
    val expectedSql =
      """create or replace function demo_compare(p_value int)
        |returns int
        |language plpgsql
        |as $$
        |begin
        |  -- local comment should not affect comparison
        |  return (p_value);
        |end;
        |$$;
        |""".stripMargin
    val actualSql =
      """CREATE OR REPLACE FUNCTION public.demo_compare(p_value integer)
        | RETURNS integer
        | LANGUAGE plpgsql
        |AS $function$
        |BEGIN
        |  return (p_value);
        |END;
        |$function$
        |""".stripMargin
    val key = ObjectKey("public", "demo_compare(integer)", "function")

    assertEquals(
      driftItems(now, List(expected(key, expectedDdl = Some(expectedSql))), List(LiveObject(key, Some(actualSql))), ControlSnapshot(Nil, None, Nil)),
      Nil
    )
  }

  test("routine comparison ignores default argument casts and omitted default volatility") {
    val ensureExpectedSql =
      """create or replace function coordinator.ensure_cursor(
        |  p_stream_name text,
        |  p_default_cursor text default '0'
        |)
        |returns text
        |language plpgsql
        |as $$
        |begin
        |  return p_default_cursor;
        |end;
        |$$;
        |""".stripMargin
    val ensureActualSql =
      """CREATE OR REPLACE FUNCTION coordinator.ensure_cursor(p_stream_name text, p_default_cursor text DEFAULT '0'::text)
        | RETURNS text
        | LANGUAGE plpgsql
        |AS $function$
        |begin
        |  return p_default_cursor;
        |end;
        |$function$
        |""".stripMargin
    val archiveExpectedSql =
      """create or replace function coordinator.list_wireless_payload_archive_candidates(
        |  p_hot_days integer default 7,
        |  p_limit integer default 100
        |)
        |returns table (
        |  dedupe_key text,
        |  observed_at timestamptz
        |)
        |language sql
        |volatile
        |as $$
        |  select event.dedupe_key, event.observed_at
        |  from sync_events event
        |  limit greatest(coalesce(p_limit, 100), 1)
        |$$;
        |""".stripMargin
    val archiveActualSql =
      """CREATE OR REPLACE FUNCTION coordinator.list_wireless_payload_archive_candidates(p_hot_days integer DEFAULT 7, p_limit integer DEFAULT 100)
        | RETURNS TABLE(dedupe_key text, observed_at timestamp with time zone)
        | LANGUAGE sql
        |AS $function$
        |  select event.dedupe_key, event.observed_at
        |  from sync_events event
        |  limit greatest(coalesce(p_limit, 100), 1)
        |$function$
        |""".stripMargin
    val expectedObjects = List(
      expected(ObjectKey("coordinator", "ensure_cursor(text, text)", "function"), expectedDdl = Some(ensureExpectedSql)),
      expected(
        ObjectKey("coordinator", "list_wireless_payload_archive_candidates(integer, integer)", "function"),
        expectedDdl = Some(archiveExpectedSql)
      )
    )
    val actualObjects = List(
      LiveObject(ObjectKey("coordinator", "ensure_cursor(text, text)", "function"), Some(ensureActualSql)),
      LiveObject(
        ObjectKey("coordinator", "list_wireless_payload_archive_candidates(integer, integer)", "function"),
        Some(archiveActualSql)
      )
    )

    assertEquals(driftItems(now, expectedObjects, actualObjects, ControlSnapshot(Nil, None, Nil)), Nil)
  }

  test("schema control canonical SQL tracks routines when manifest is absent") {
    val sql =
      """create or replace function coordinator.ensure_cursor(p_stream_name text)
        |returns text language sql as $$ select p_stream_name $$;
        |""".stripMargin
    val control = controlSnapshot(
      List(controlRow("function", "coordinator.ensure_cursor", "functions/020_coordinator_ensure_cursor.sql", "applied", Some(sql)))
    )
    val actualSql = control.objects.head.expectedDdl.getOrElse(sql)

    val drift = driftItems(
      now,
      Nil,
      List(LiveObject(ObjectKey("coordinator", "ensure_cursor(text)", "function"), Some(actualSql))),
      control
    )
    val catalog = mergeCatalog(
      now,
      Nil,
      List(LiveObject(ObjectKey("coordinator", "ensure_cursor(text)", "function"), Some(actualSql))),
      control
    )

    assertEquals(drift, Nil)
    assertEquals(catalog.head.apply_status, Some("applied"))
    assertEquals(catalog.head.status, "in_sync")
  }

  test("view comparison ignores Postgres deparser casing public qualification and redundant expression parentheses") {
    val expectedSql =
      """create view sync_events_expanded as
        |select
        |  (archive.dedupe_key is not null and e.payload is null) as payload_archived,
        |  e.status
        |from sync_events e
        |left join sync_event_payload_archives archive on archive.dedupe_key = e.dedupe_key;
        |""".stripMargin
    val actualSql =
      """create view public.sync_events_expanded as
        |SELECT archive.dedupe_key IS NOT NULL AND e.payload IS NULL AS payload_archived,
        |       e.status
        |FROM sync_events e
        |LEFT JOIN sync_event_payload_archives archive ON archive.dedupe_key = e.dedupe_key
        |""".stripMargin
    val key = ObjectKey("public", "sync_events_expanded", "view")

    assertEquals(
      driftItems(now, List(expected(key, sourceFile = "views/001_sync_events_expanded.sql", expectedDdl = Some(expectedSql))), List(LiveObject(key, Some(actualSql))), ControlSnapshot(Nil, None, Nil)),
      Nil
    )
  }

  test("materialized view comparison accepts Postgres expansion of select star source views") {
    val expectedSql =
      """create materialized view mv_ap_risk_score as
        |select * from v_ap_risk_score;
        |""".stripMargin
    val actualSql =
      """create materialized view public.mv_ap_risk_score as
        |SELECT bssid, deauth_score, composite_risk
        |FROM v_ap_risk_score
        |""".stripMargin
    val key = ObjectKey("public", "mv_ap_risk_score", "materialized_view")

    assertEquals(
      driftItems(now, List(expected(key, sourceFile = "materialized_views/001_mv_ap_risk_score.sql", expectedDdl = Some(expectedSql))), List(LiveObject(key, Some(actualSql))), ControlSnapshot(Nil, None, Nil)),
      Nil
    )
  }

  test("view materialized view and index definition changes are reported as drift") {
    val viewKey = ObjectKey("public", "sync_events_expanded", "view")
    val materializedViewKey = ObjectKey("public", "mv_ap_risk_score", "materialized_view")
    val indexKey = ObjectKey("public", "sync_events_status_idx", "index")
    val expectedObjects = List(
      expected(viewKey, sourceFile = "views/001_sync_events_expanded.sql", expectedDdl = Some("create view sync_events_expanded as select 1 as id;")),
      expected(materializedViewKey, sourceFile = "materialized_views/001_mv_ap_risk_score.sql", expectedDdl = Some("create materialized view mv_ap_risk_score as select 1 as score;")),
      expected(indexKey, sourceFile = "indexes/001_sync_events_indexes.sql", expectedDdl = Some("create index if not exists sync_events_status_idx on sync_events (status);"))
    )
    val actualObjects = List(
      LiveObject(viewKey, Some("create view public.sync_events_expanded as select 2 as id;")),
      LiveObject(materializedViewKey, Some("create materialized view public.mv_ap_risk_score as select 2 as score;")),
      LiveObject(indexKey, Some("CREATE INDEX sync_events_status_idx ON public.sync_events USING btree (observed_at)"))
    )

    val drift = driftItems(now, expectedObjects, actualObjects, ControlSnapshot(Nil, None, Nil))
    val catalog = mergeCatalog(now, expectedObjects, actualObjects, ControlSnapshot(Nil, None, Nil))

    assertEquals(drift.map(item => (item.object_type, item.drift_type)), List(("index", "definition_changed"), ("materialized_view", "definition_changed"), ("view", "definition_changed")))
    assertEquals(catalog.map(item => (item.object_type, item.status)), List(("index", "drift_detected"), ("materialized_view", "drift_detected"), ("view", "drift_detected")))
  }

  test("matching idempotent btree index definitions do not report definition drift") {
    val indexKey = ObjectKey("public", "sync_events_status_idx", "index")
    val expectedObjects = List(
      expected(indexKey, sourceFile = "indexes/001_sync_events_indexes.sql", expectedDdl = Some("create index if not exists sync_events_status_idx on sync_events (status);"))
    )
    val actualObjects = List(
      LiveObject(indexKey, Some("CREATE INDEX sync_events_status_idx ON public.sync_events USING btree (status)"))
    )

    val drift = driftItems(now, expectedObjects, actualObjects, ControlSnapshot(Nil, None, Nil))
    val catalog = mergeCatalog(now, expectedObjects, actualObjects, ControlSnapshot(Nil, None, Nil))

    assertEquals(drift, Nil)
    assertEquals(catalog.map(item => (item.object_type, item.status)), List(("index", "in_sync")))
  }

  test("known built-in untracked catalog objects are hidden while user objects remain visible") {
    val actualObjects = List(
      LiveObject(ObjectKey("public", "public", "schema"), Some("create schema public")),
      LiveObject(ObjectKey("public", "pg_stat_statements", "extension"), Some("create extension if not exists pg_stat_statements")),
      LiveObject(ObjectKey("cron", "job.cron_job_cache_invalidate", "trigger"), Some("CREATE TRIGGER cron_job_cache_invalidate")),
      LiveObject(ObjectKey("public", "custom_live_only", "view"), Some("create view custom_live_only as select 1 as id"))
    )

    val items = driftItems(now, Nil, actualObjects, ControlSnapshot(Nil, None, Nil))

    assertEquals(items.map(item => (item.schema, item.object_type, item.name, item.drift_type)), List(("public", "view", "custom_live_only", "untracked_actual")))
  }

  test("pending and failed control rows take precedence over definition drift") {
    val expectedSql = "create or replace function coordinator.ensure_cursor(p_stream_name text) returns text language sql as $$ select 'expected' $$;"
    val actualSql = "create or replace function coordinator.ensure_cursor(p_stream_name text) returns text language sql as $$ select 'actual' $$;"
    val key = ObjectKey("coordinator", "ensure_cursor(text)", "function")
    val control = controlSnapshot(
      List(controlRow("function", "coordinator.ensure_cursor", "functions/020_coordinator_ensure_cursor.sql", "pending", Some(expectedSql)))
    )

    val items = driftItems(
      now,
      List(expected(key, sourceFile = "functions/020_coordinator_ensure_cursor.sql", expectedDdl = Some(expectedSql))),
      List(LiveObject(key, Some(actualSql))),
      control
    )

    assertEquals(items.map(_.drift_type), List("pending_or_failed_control"))
    assertEquals(items.head.apply_status, Some("pending"))
  }

  test("applied control rows do not suppress live definition drift") {
    val expectedSql = "create or replace function demo_fn() returns integer language sql as $$ select 1 $$;"
    val actualSql = "create or replace function public.demo_fn() returns integer language sql as $$ select 2 $$;"
    val key = ObjectKey("public", "demo_fn()", "function")
    val sourceFile = "functions/001_demo_fn.sql"
    val expectedObjects = List(expected(key, sourceFile = sourceFile, expectedDdl = Some(expectedSql)))
    val actualObjects = List(LiveObject(key, Some(actualSql)))
    val control = controlSnapshot(
      List(controlRow("function", "demo_fn", sourceFile, "skipped", Some(expectedSql)))
    )

    val items = driftItems(now, expectedObjects, actualObjects, control)
    val catalog = mergeCatalog(now, expectedObjects, actualObjects, control)

    assertEquals(items.map(_.drift_type), List("definition_changed"))
    assertEquals(items.head.apply_status, Some("skipped"))
    assertEquals(catalog.head.status, "drift_detected")
  }

  test("control summary counts applied skipped pending and failed rows") {
    val snapshot = controlSnapshot(
      List(
        controlRow("table", "public.devices", "tables/001_devices.sql", "applied"),
        controlRow("index", "public.devices_idx", "indexes/001_devices_idx.sql", "skipped"),
        controlRow("function", "public.pending_fn", "functions/001_pending.sql", "pending"),
        controlRow("view", "public.failed_view", "views/001_failed.sql", "failed")
      )
    )

    assertEquals(snapshot.summary.map(_.total_count), Some(4L))
    assertEquals(snapshot.summary.map(_.applied_count), Some(1L))
    assertEquals(snapshot.summary.map(_.skipped_count), Some(1L))
    assertEquals(snapshot.summary.map(_.pending_count), Some(1L))
    assertEquals(snapshot.summary.map(_.failed_count), Some(1L))
    assertEquals(snapshot.summary.map(_.ready), Some(false))
    assertEquals(snapshot.summary.map(_.failed_objects), Some(List("view:public.failed_view")))
    assertEquals(
      snapshot.rows.map(_.source_file),
      List("tables/001_devices.sql", "indexes/001_devices_idx.sql", "functions/001_pending.sql", "views/001_failed.sql")
    )
    assertEquals(snapshot.rows.map(_.apply_status), List("applied", "skipped", "pending", "failed"))
    assertEquals(snapshot.rows.head.checksum, "sha-tables/001_devices.sql")
    assertEquals(snapshot.rows.head.applied_at, Some("2026-07-02T12:00:00Z"))
    assertEquals(snapshot.rows.head.updated_at, Some("2026-07-02T12:05:00Z"))
  }

  private def schemaObject(kind: String, objectName: String, sourceFile: String, rawSql: String): SchemaObject =
    SchemaObject(
      kind = kind,
      objectName = objectName,
      sourceFile = sourceFile,
      dependsOn = Nil,
      rollbackFile = None,
      transactional = true,
      rawSql = rawSql,
      canonicalSql = rawSql,
      sha256 = s"sha-$sourceFile"
    )

  private def expected(
    key: ObjectKey,
    sourceFile: String = "functions/001_demo.sql",
    expectedDdl: Option[String] = None,
    applyStatus: Option[String] = None
  ): ExpectedObject =
    ExpectedObject(
      key = key,
      sourceFile = sourceFile,
      checksum = s"sha-$sourceFile",
      expectedDdl = expectedDdl,
      applyStatus = applyStatus
    )

  private def controlRow(
    kind: String,
    objectName: String,
    sourceFile: String,
    applyStatus: String,
    expectedDdl: Option[String] = None
  ): ControlRow =
    ControlRow(
      kind = kind,
      objectName = objectName,
      sourceFile = sourceFile,
      applyStatus = applyStatus,
      checksum = s"sha-$sourceFile",
      expectedDdl = expectedDdl,
      appliedAt = Option.when(applyStatus == "applied")("2026-07-02T12:00:00Z"),
      updatedAt = Some("2026-07-02T12:05:00Z")
    )
