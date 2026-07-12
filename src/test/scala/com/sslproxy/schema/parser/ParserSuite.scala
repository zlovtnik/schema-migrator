package com.sslproxy.schema.parser

import com.sslproxy.schema.db.syntax.SqlDialect
import munit.FunSuite

class ParserSuite extends FunSuite:
  test("sha256 hex output is unsigned and exactly 64 characters") {
    val hash = Canonicalizer.sha256Hex("abc")
    assertEquals(hash, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assertEquals(hash.length, 64)
  }

  test("canonical hash preserves dollar-quoted body verbatim") {
    val left =
      """
      -- object: sample
      create or replace function sample()
      returns void
      language plpgsql
      as $fn$
      begin
        -- implementation note
        perform 1;
      end;
      $fn$;
      """
    val right =
      """
      create or replace function sample() returns void language plpgsql as $$
      begin
        perform 1;
      end;
      $$;
      """

    assertNotEquals(
      Canonicalizer.canonicalize(left, SqlDialect.Postgres),
      Canonicalizer.canonicalize(right, SqlDialect.Postgres)
    )
  }

  test("canonical hash preserves string literal whitespace") {
    val left = Canonicalizer.canonicalize("select 'a  b';", SqlDialect.Postgres)
    val right = Canonicalizer.canonicalize("select 'a b';", SqlDialect.Postgres)
    assertNotEquals(left, right)
  }

  test("canonical SQL preserves grouped dollar-quoted function bodies") {
    val sql =
      """-- object: coordinator.safe helpers
        |-- folder: functions
        |-- depends_on: coordinator
        |create or replace function coordinator.safe_int(p_value text)
        |returns integer
        |language plpgsql
        |immutable
        |as $$
        |begin
        |  if p_value is null or p_value !~ '^-?[0-9]+$' then
        |    return null;
        |  end if;
        |
        |  begin
        |    return p_value::integer;
        |  exception
        |    when others then
        |      return null;
        |  end;
        |end;
        |$$;
        |
        |create or replace function coordinator.safe_bigint(p_value text)
        |returns bigint
        |language sql
        |immutable
        |as $$
        |  select case when p_value ~ '^-?[0-9]+$' then p_value::bigint end
        |$$;
        |""".stripMargin

    val canonical = Canonicalizer.canonicalize(sql, SqlDialect.Postgres)

    assert(canonical.contains("as $$\nbegin"))
    assert(canonical.contains("select case when p_value ~ '^-?[0-9]+$' then p_value::bigint end"))
    assert(!canonical.contains("as $$(-- object: coordinator.safe helpers"))
  }

  test("oracle canonicalizer preserves optimizer hints and strips slash terminators") {
    val sql =
      """
      create or replace view x as
      select /*+ index(t idx) */ *
      from t;
      /
      """

    val canonical = Canonicalizer.canonicalize(sql, SqlDialect.Oracle)
    assert(canonical.contains("/*+ index(t idx) */"))
    assert(!canonical.endsWith("/"))
  }

  test("balance checker reports unterminated dollar quote") {
    assertEquals(
      BalanceChecker.check("select $fn$ unterminated").left.toOption,
      Some("unterminated dollar-quoted block with tag $fn$")
    )
  }
