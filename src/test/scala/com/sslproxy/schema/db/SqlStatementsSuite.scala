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

  test("retired objects do not block schema readiness") {
    val postgresBootstrap = PostgresStatements.bootstrapSql.toLowerCase
    val oracleReady = OracleStatements.viewSchemaReady.toLowerCase

    assert(PostgresStatements.retireSql.toLowerCase.contains("apply_status = 'retired'"))
    assert(OracleStatements.retireSql.toLowerCase.contains("apply_status = 'retired'"))
    assert(postgresBootstrap.contains("apply_status <> 'retired'"))
    assert(postgresBootstrap.contains("'applied', 'skipped', 'retired'"))
    assert(oracleReady.contains("apply_status <> 'retired'"))
    assert(oracleReady.contains("'applied', 'skipped', 'retired'"))
  }
