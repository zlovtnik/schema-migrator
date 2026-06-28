package com.sslproxy.schema.db

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.db.oracle.OracleDdlHelper
import com.sslproxy.schema.db.oracle.OracleStatements
import com.sslproxy.schema.db.postgres.PostgresProvider
import com.sslproxy.schema.engine.SchemaObject
import doobie.*
import doobie.free.FC
import doobie.hi.{HC, HS}
import doobie.implicits.*
import doobie.util.transactor.{Strategy, Transactor}
import munit.FunSuite

import java.util.UUID

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

  test("postgres Doobie wiring keeps non-transactional strategy on same transactor kernel") {
    val config = JdbcConnectionConfig("org.h2.Driver", h2Url())
    val tx = DoobieSupport.postgresDriverManagerTransactor(config)
    val noTx = DoobieSupport.withoutTransaction(tx)

    assertEquals(noTx.kernel: Any, tx.kernel: Any)
    assertEquals(noTx.strategy.before, FC.setAutoCommit(true))
    assertEquals(noTx.strategy.after, FC.unit)
    assertEquals(noTx.strategy.oops, FC.unit)
    assertEquals(noTx.strategy.always, FC.unit)
  }

  test("postgres apply log runs through Doobie transactor in H2 sanity cycle") {
    val tx = Transactor.fromDriverManager[IO]("org.h2.Driver", h2Url(), "", "", None)
    val obj = SchemaObject(
      kind = "tables",
      objectName = "accounts",
      sourceFile = "tables/001_accounts.sql",
      dependsOn = Nil,
      rollbackFile = None,
      transactional = true,
      rawSql = "create table accounts(id bigint primary key)",
      canonicalSql = "create table accounts(id bigint primary key)",
      sha256 = "new-sha"
    )

    val program =
      for
        _ <- HC.createStatement(HS.executeUpdate("create schema if not exists schema_control"))
        _ <- HC.createStatement(
          HS.executeUpdate(
            """
          create table schema_control.schema_objects (
            kind varchar not null,
            object_name varchar not null,
            source_file varchar not null,
            content_sha256 varchar not null,
            apply_status varchar not null,
            applied_at timestamp,
            last_error clob,
            updated_at timestamp default now(),
            primary key (kind, object_name)
          )
          """
          )
        )
        _ <- HC.createStatement(
          HS.executeUpdate(
            """
          create table schema_control.schema_apply_log (
            kind varchar not null,
            object_name varchar not null,
            source_file varchar not null,
            action varchar not null,
            old_sha256 varchar,
            new_sha256 varchar,
            duration_ms integer,
            error_text clob,
            applied_by varchar,
            applied_at timestamp default now()
          )
          """
          )
        )
        _ <-
          sql"""
          insert into schema_control.schema_objects (
            kind, object_name, source_file, content_sha256, apply_status
          ) values (${obj.kind}, ${obj.objectName}, ${obj.sourceFile}, 'old-sha', 'pending')
          """.update.run
        _ <- ApplyLog.postgres("provider-suite").recordApplied(obj, Some("old-sha"), 12)
        status <-
          sql"""
          select apply_status
            from schema_control.schema_objects
           where kind = ${obj.kind} and object_name = ${obj.objectName}
          """.query[String].unique
        logRow <-
          sql"""
          select action, old_sha256, new_sha256, duration_ms, applied_by
            from schema_control.schema_apply_log
           where kind = ${obj.kind} and object_name = ${obj.objectName}
          """.query[(String, Option[String], Option[String], Option[Int], Option[String])].unique
      yield (status, logRow)

    val (status, logRow) = program.transact(tx).unsafeRunSync()

    assertEquals(status, "applied")
    assertEquals(logRow, ("applied", Some("old-sha"), Some("new-sha"), Some(12), Some("provider-suite")))
  }

  test("oracle lock conflict query reports current holder without age-based cleanup") {
    val sql = OracleStatements.lockQueryInfoSql
    assert(sql.contains("locked_by"))
    assert(sql.contains("locked_at_char"))
    assert(sql.contains("where lock_name = 'schema_migrate'"))
  }

  test("oracle apply log sql binds CLOB for error_text") {
    val sql = OracleStatements.applyLogSql
    assert(sql.contains("error_text"), "applyLogSql must include error_text column")
    assert(sql.contains("?, ?, ?, ?, ?, ?, ?, ?, ?"), "applyLogSql must have 9 bind parameters")
  }

  test("oracle bootstrap blocks are idempotent schema_control DDL") {
    val blocks = OracleStatements.bootstrapBlocks
    val joined = blocks.mkString("\n").toLowerCase

    assert(blocks.length >= 6)
    assert(joined.contains("pragma exception_init(expected_error, -955)"))
    assert(joined.contains("pragma exception_init(expected_error, -1430)"))
    assert(joined.contains("create table schema_control.schema_objects"))
    assert(joined.contains("create table schema_control.schema_apply_log"))
    assert(joined.contains("create table schema_control.migration_locks"))
    assert(joined.contains("constraint migration_locks_pk primary key"))
    assert(joined.contains("create index schema_apply_log_object_idx"))
    assert(joined.contains("create or replace view schema_control.schema_ready"))
  }

  test("oracle lock release only deletes the named migration lock") {
    val sql = OracleStatements.lockDeleteSql.toLowerCase
    assert(sql.contains("delete from schema_control.migration_locks"))
    assert(sql.contains("where lock_name = 'schema_migrate'"))
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

  private def h2Url(): String =
    s"jdbc:h2:mem:${UUID.randomUUID().toString};MODE=PostgreSQL;DATABASE_TO_UPPER=false"
