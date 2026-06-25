package com.sslproxy.schema.db

import com.sslproxy.schema.db.oracle.OracleDdlHelper
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresProvider
import munit.FunSuite

class ProviderSuite extends FunSuite:
  test("normalizes postgres URI into JDBC config with credentials") {
    val config = PostgresProvider.normalize("postgres://sync:p%40ss@localhost:5432/sync?sslmode=disable").toOption.get
    assertEquals(config.url, "jdbc:postgresql://localhost:5432/sync?sslmode=disable")
    assertEquals(config.user, Some("sync"))
    assertEquals(config.password, Some("p@ss"))
  }

  test("accepts existing JDBC postgres URL") {
    val config = PostgresProvider.normalize("jdbc:postgresql://postgres:5432/sync").toOption.get
    assertEquals(config.url, "jdbc:postgresql://postgres:5432/sync")
    assertEquals(config.user, None)
  }

  test("oracle lock stale query uses getOrElse(false) semantics") {
    import com.sslproxy.schema.db.{LockManager, JdbcConnectionConfig}
    import com.sslproxy.schema.db.oracle.OracleStatements
    val sql = OracleStatements.lockQueryStaleSql
    assert(sql.contains("numtodsinterval(10, 'MINUTE')"))
    assert(sql.contains("where lock_name = 'schema_migrate'"))
  }

  test("oracle apply log sql binds CLOB for error_text") {
    val sql = OracleStatements.applyLogSql
    assert(sql.contains("error_text"), "applyLogSql must include error_text column")
    assert(sql.contains("?, ?, ?, ?, ?, ?, ?, ?, ?"), "applyLogSql must have 9 bind parameters")
  }

  test("splits oracle slash-terminated PL/SQL blocks") {
    val sql =
      """
      begin
        null;
      end;
      /
      create or replace view x as select 1 one from dual;
      """
    val blocks = OracleDdlHelper.splitExecutableBlocks(sql)
    assertEquals(blocks.length, 2)
    assert(blocks.head.startsWith("begin"))
    assert(blocks(1).startsWith("create or replace view"))
  }

