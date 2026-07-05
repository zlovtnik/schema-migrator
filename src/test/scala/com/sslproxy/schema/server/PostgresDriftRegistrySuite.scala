package com.sslproxy.schema.server

import com.sslproxy.schema.store.{DriftItem, SchemaCatalogObject}
import munit.FunSuite

class PostgresDriftRegistrySuite extends FunSuite:
  import PostgresDriftAnalyzer.*

  private val now = "2026-07-04T12:00:00Z"
  private val key = ObjectKey("public", "sync_stable_uuid(text)", "function")

  test("registry rows store normalized deterministic hashes for equivalent DDL") {
    val expectedSql =
      """create or replace function sync_stable_uuid(value text)
        |returns uuid
        |language sql
        |immutable
        |as $$
        |  select md5(value)::uuid
        |$$;
        |""".stripMargin
    val actualSql =
      """CREATE OR REPLACE FUNCTION public.sync_stable_uuid(value text)
        | RETURNS uuid
        | LANGUAGE sql
        | IMMUTABLE
        |AS $function$
        |  select md5(value)::uuid
        |$function$
        |""".stripMargin

    val rows = PostgresDriftRegistry.registryRows(
      "fixture",
      List(catalogItem("in_sync", expectedSql, actualSql)),
      Nil
    )

    assertEquals(rows.map(_.customer), List("fixture"))
    assertEquals(rows.map(_.status), List("in_sync"))
    assertEquals(rows.flatMap(_.coreHash), rows.flatMap(_.liveHash))
    assertEquals(rows.flatMap(_.coreHash), List(definitionHash(key, expectedSql)))
  }

  test("registry rows attribute definition drift as customized") {
    val expectedSql =
      """create or replace function sync_stable_uuid(value text)
        |returns uuid
        |language sql
        |immutable
        |as $$ select md5(value)::uuid $$;
        |""".stripMargin
    val actualSql =
      """create or replace function public.sync_stable_uuid(value text)
        |returns uuid
        |language sql
        |immutable
        |as $$ select gen_random_uuid() $$;
        |""".stripMargin
    val drift = DriftItem(
      schema = "public",
      name = "sync_stable_uuid(text)",
      object_type = "function",
      drift_type = "definition_changed",
      expected = expectedSql,
      actual = actualSql,
      source_file = Some("functions/001_sync_stable_uuid.sql"),
      checksum = Some("sha-functions/001_sync_stable_uuid.sql"),
      apply_status = Some("applied"),
      detected_at = now
    )

    val rows = PostgresDriftRegistry.registryRows(
      "fixture",
      List(catalogItem("drift_detected", expectedSql, actualSql)),
      List(drift)
    )

    assertEquals(rows.map(_.status), List("drift_detected"))
    assertEquals(rows.flatMap(_.driftType), List("definition_changed"))
    assertEquals(rows.flatMap(_.applyStatus), List("applied"))
    assert(rows.exists(row => row.coreHash != row.liveHash))
  }

  private def catalogItem(status: String, expectedSql: String, actualSql: String): SchemaCatalogObject =
    SchemaCatalogObject(
      schema = key.schema,
      name = key.name,
      object_type = key.objectType,
      status = status,
      source_file = Some("functions/001_sync_stable_uuid.sql"),
      checksum = Some("sha-functions/001_sync_stable_uuid.sql"),
      apply_status = Some("applied"),
      actual_ddl = Some(actualSql),
      expected_ddl = Some(expectedSql),
      last_checked = now
    )
