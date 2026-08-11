package com.sslproxy.schema.engine

import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.DbKind
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.{DiscoveryResult, DiscoveryService, SqlFile}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.store.ValidationStore
import munit.FunSuite

import java.nio.file.Path

class MigrationPlanSuite extends FunSuite:
  test("orders valid objects and includes discovery warnings once") {
    val discovery = DiscoveryResult(
      List(sqlFile("child", "parent"), sqlFile("parent")),
      List("discovery warning")
    )

    val plan = MigrationPlan.inspect(DbKind.Postgres, SqlDialect.Postgres, discovery).unsafeRunSync()

    assertEquals(plan.objects.map(_.objectName), List("parent", "child"))
    assertEquals(plan.validation.warnings.count(_ == "discovery warning"), 1)
    assertEquals(plan.warnings, plan.validation.warnings)
  }

  test("returns duplicate, missing dependency, and cycle failures as validation errors") {
    val discovery = DiscoveryResult(
      List(
        sqlFile("duplicate"),
        sqlFile("duplicate"),
        sqlFile("missing", "unknown"),
        sqlFile("cycle_a", "cycle_b"),
        sqlFile("cycle_b", "cycle_a")
      ),
      Nil
    )

    val plan = MigrationPlan.inspect(DbKind.Postgres, SqlDialect.Postgres, discovery).unsafeRunSync()

    assert(plan.validation.errors.exists(_.contains("duplicate object identity 'function:duplicate'")))
    assert(plan.validation.errors.exists(_.contains("depends on missing object 'unknown'")))
    assert(plan.validation.errors.exists(_.contains("dependency cycle detected among")))
  }

  test("allows explicitly external dependencies") {
    val discovery = DiscoveryResult(List(sqlFile("consumer", "ext:managed_elsewhere")), Nil)

    val plan = MigrationPlan.inspect(DbKind.Postgres, SqlDialect.Postgres, discovery).unsafeRunSync()

    assertEquals(plan.validation.errors, Nil)
  }

  test("keeps same-named kinds distinct and resolves kind-qualified dependencies") {
    val discovery = DiscoveryResult(
      List(sqlFile("consumer", "table:shared"), sqlFile("shared"), tableSqlFile("shared")),
      Nil
    )

    val plan = MigrationPlan.inspect(DbKind.Postgres, SqlDialect.Postgres, discovery).unsafeRunSync()
    val tableIndex = plan.objects.indexWhere(objectDef => objectDef.identity.render == "table:shared")
    val consumerIndex = plan.objects.indexWhere(objectDef => objectDef.identity.render == "function:consumer")

    assertEquals(plan.validation.errors, Nil)
    assert(tableIndex >= 0 && tableIndex < consumerIndex)
  }

  test("requires kind qualification when a dependency name is ambiguous") {
    val discovery = DiscoveryResult(
      List(sqlFile("consumer", "shared"), sqlFile("shared"), tableSqlFile("shared")),
      Nil
    )

    val plan = MigrationPlan.inspect(DbKind.Postgres, SqlDialect.Postgres, discovery).unsafeRunSync()

    assert(plan.validation.errors.exists(_.contains("ambiguous dependency 'shared'")))
  }

  test("prepareFiles rejects invalid plans before apply") {
    val error = intercept[MigratorError.Validation] {
      MigrationPlan
        .prepareFiles(
          DbKind.Postgres,
          List(sqlFile("consumer", "missing")),
          SqlDialect.Postgres,
          DiscoveryService()
        )
        .unsafeRunSync()
    }

    assert(error.getMessage.contains("depends on missing object 'missing'"))
  }

  test("inspection returns header failures instead of raising apply errors") {
    val invalid = SqlFile(
      folder = "tables",
      path = Path.of("001_invalid.sql"),
      name = "001_invalid.sql",
      relativePath = "tables/001_invalid.sql",
      content = Some("select 1;")
    )

    val plan = MigrationPlan
      .inspect(DbKind.Postgres, SqlDialect.Postgres, DiscoveryResult(List(invalid), Nil))
      .unsafeRunSync()

    assert(plan.validation.errors.exists(_.contains("missing required header comments")))
    assertEquals(plan.objects, Nil)
  }

  test("inspection validates uncategorized uploads that discovery cannot execute") {
    val invalid = SqlFile(
      folder = "uncategorized",
      path = Path.of("001_invalid.sql"),
      name = "001_invalid.sql",
      relativePath = "001_invalid.sql",
      content = Some("select 1;")
    )

    val plan = MigrationPlan
      .inspectFiles(DbKind.Postgres, List(invalid), SqlDialect.Postgres)
      .unsafeRunSync()

    assert(plan.validation.errors.exists(_.contains("missing required header comments")))
    assertEquals(plan.objects, Nil)
  }

  test("blocks only malformed ordered SQL while reporting ignored table and view files as warnings") {
    val orderedView = SqlFile(
      folder = "views",
      path = Path.of("views/001_ready_view.sql"),
      name = "001_ready_view.sql",
      relativePath = "views/001_ready_view.sql",
      content = Some(
        """-- object: ready_view
          |-- folder: views
          |-- depends_on: -
          |CREATE OR REPLACE VIEW ready_view AS SELECT 1 AS id;
          |""".stripMargin
      )
    )
    val ignoredView = SqlFile(
      folder = "scratch",
      path = Path.of("scratch/002_ignored_view.sql"),
      name = "002_ignored_view.sql",
      relativePath = "scratch/002_ignored_view.sql",
      content = Some(
        """-- object: ignored_view
          |-- folder: scratch
          |-- depends_on: -
          |CREATE VIEW ignored_view AS SELECT 1 AS id;
          |""".stripMargin
      )
    )
    val malformedTable = SqlFile(
      folder = "tables",
      path = Path.of("tables/003_malformed.sql"),
      name = "003_malformed.sql",
      relativePath = "tables/003_malformed.sql",
      content = Some(
        """-- object: malformed_table
          |-- folder: tables
          |-- depends_on: -
          |CREATE TABLE IF NOT EXISTS malformed_table (id TEXT DEFAULT 'unterminated);
          |""".stripMargin
      )
    )

    val warningOnly = MigrationPlan
      .inspectFiles(DbKind.Postgres, List(orderedView, ignoredView), SqlDialect.Postgres)
      .unsafeRunSync()
    val (_, warningFindings, warningStatus) = ValidationStore.findingsAndStatus(warningOnly.validation)
    assertEquals(warningStatus, "warnings")
    assertEquals(warningOnly.validation.errors, Nil)
    assert(warningFindings.exists(_.name == "scratch/002_ignored_view.sql"))

    val malformed = MigrationPlan
      .inspectFiles(DbKind.Postgres, List(orderedView, ignoredView, malformedTable), SqlDialect.Postgres)
      .unsafeRunSync()
    val (blockingFindings, _, malformedStatus) = ValidationStore.findingsAndStatus(malformed.validation)
    assertEquals(malformedStatus, "errors")
    assert(blockingFindings.exists(_.name == "tables/003_malformed.sql"))
  }

  test("builds retired objects outside the active graph") {
    val retired = sqlFile("old_definition")
    val plan = MigrationPlan
      .inspect(
        DbKind.Postgres,
        SqlDialect.Postgres,
        DiscoveryResult(List(sqlFile("current_definition")), Nil, List(retired))
      )
      .unsafeRunSync()

    assertEquals(plan.objects.map(_.objectName), List("current_definition"))
    assertEquals(plan.retiredObjects.map(_.objectName), List("old_definition"))
  }

  private def sqlFile(name: String, dependencies: String*): SqlFile =
    val dependencyHeader = if dependencies.isEmpty then "-" else dependencies.mkString(", ")
    SqlFile(
      folder = "functions",
      path = Path.of(s"$name.sql"),
      name = s"$name.sql",
      relativePath = s"functions/$name.sql",
      content = Some(
        s"""-- object: $name
           |-- folder: functions
           |-- depends_on: $dependencyHeader
           |create or replace function $name() returns integer language sql as 'select 1';
           |""".stripMargin
      )
    )

  private def tableSqlFile(name: String): SqlFile =
    SqlFile(
      folder = "tables",
      path = Path.of(s"$name.sql"),
      name = s"$name.sql",
      relativePath = s"tables/$name.sql",
      content = Some(
        s"""-- object: $name
           |-- folder: tables
           |-- depends_on: -
           |create table if not exists $name(id integer);
           |""".stripMargin
      )
    )
