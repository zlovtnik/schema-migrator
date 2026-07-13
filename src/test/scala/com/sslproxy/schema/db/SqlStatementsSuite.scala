package com.sslproxy.schema.db

import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresStatements
import munit.FunSuite

class SqlStatementsSuite extends FunSuite:
  test("Postgres and Oracle expose every common schema-control statement") {
    val expected = DialectStatementKey.values.toSet
    val postgres = PostgresStatements.commonStatements
    val oracle = OracleStatements.commonStatements

    assertEquals(postgres.keySet, expected)
    assertEquals(oracle.keySet, expected)
    assertEquals(postgres.keySet, oracle.keySet)
    assert(postgres.values.forall(_.trim.nonEmpty))
    assert(oracle.values.forall(_.trim.nonEmpty))
  }
