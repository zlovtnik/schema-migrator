package com.sslproxy.schema.parser

import com.sslproxy.schema.db.syntax.SqlDialect
import munit.FunSuite

class ParserSuite extends FunSuite:
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

