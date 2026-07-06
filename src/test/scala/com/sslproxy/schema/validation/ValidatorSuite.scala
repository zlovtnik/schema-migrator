package com.sslproxy.schema.validation

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.DbKind
import com.sslproxy.schema.discovery.DiscoveryService
import com.sslproxy.schema.discovery.SqlFile
import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class ValidatorSuite extends FunSuite:
  test("flags non-idempotent postgres table as warning") {
    withTempDir { dir =>
      val path = dir.resolve("001_table.sql")
      Files.writeString(path, "-- object: sample\n-- folder: tables\n-- depends_on: -\ncreate table sample(id int);\n")

      val report = Validator[IO](DbKind.Postgres)
        .validate(List(SqlFile("tables", path, "001_table.sql", "001_table.sql")))
        .unsafeRunSync()
      assert(report.warnings.exists(_.contains("CREATE TABLE IF NOT EXISTS")))
      assert(!report.hasErrors)
    }
  }

  test("reports missing required headers") {
    withTempDir { dir =>
      val path = dir.resolve("001_table.sql")
      Files.writeString(path, "create table if not exists sample(id int);\n")

      val report = Validator[IO](DbKind.Postgres)
        .validate(List(SqlFile("tables", path, "001_table.sql", "001_table.sql")))
        .unsafeRunSync()
      assert(report.errors.exists(_.contains("missing required header")))
    }
  }

  test("reports same-file duplicate postgres table column patches") {
    withTempDir { dir =>
      val path = dir.resolve("001_table.sql")
      Files.writeString(
        path,
        """-- object: sample
          |-- folder: tables
          |-- depends_on: -
          |create table if not exists sample (
          |  id bigint primary key,
          |  status text
          |);
          |
          |alter table sample add column if not exists status text;
          |""".stripMargin
      )

      val report = Validator[IO](DbKind.Postgres)
        .validate(List(SqlFile("tables", path, "001_table.sql", "001_table.sql")))
        .unsafeRunSync()

      assert(report.errors.exists(_.contains("duplicates CREATE TABLE column")))
    }
  }

  test("does not collapse schema-qualified table names when checking duplicate columns") {
    withTempDir { dir =>
      val path = dir.resolve("001_table.sql")
      Files.writeString(
        path,
        """-- object: events
          |-- folder: tables
          |-- depends_on: -
          |create table if not exists public.events (
          |  id bigint primary key,
          |  status text
          |);
          |
          |alter table audit.events add column if not exists status text;
          |""".stripMargin
      )

      val report = Validator[IO](DbKind.Postgres)
        .validate(List(SqlFile("tables", path, "001_table.sql", "001_table.sql")))
        .unsafeRunSync()

      assert(!report.errors.exists(_.contains("duplicates CREATE TABLE column")))
    }
  }

  test("reports duplicate postgres view bodies that only differ by header comments") {
    withTempDir { dir =>
      val first = dir.resolve("001_demo_view.sql")
      val second = dir.resolve("002_demo_view_override.sql")
      Files.writeString(
        first,
        """-- object: demo_view
          |-- folder: views
          |-- depends_on: -
          |create or replace view demo_view as
          |select 1 as id;
          |""".stripMargin
      )
      Files.writeString(
        second,
        """-- object: demo_view override
          |-- folder: views
          |-- depends_on: demo_view
          |create or replace view demo_view as
          |select 1 as id;
          |""".stripMargin
      )

      val report = Validator[IO](DbKind.Postgres)
        .validate(
          List(
            SqlFile("views", first, "001_demo_view.sql", "core/06_views/001_demo_view.sql"),
            SqlFile("views", second, "002_demo_view_override.sql", "core/06_views/002_demo_view_override.sql")
          )
        )
        .unsafeRunSync()

      assert(report.errors.exists(_.contains("duplicate view body for demo_view")))
    }
  }

  test("reports duplicate postgres view definitions after the first definition in a grouped file") {
    withTempDir { dir =>
      val grouped = dir.resolve("001_grouped_views.sql")
      val duplicate = dir.resolve("002_second_view.sql")
      Files.writeString(
        grouped,
        """-- object: grouped views
          |-- folder: views
          |-- depends_on: -
          |create or replace view first_view as
          |select 1 as id;
          |
          |create or replace view second_view as
          |select 2 as id;
          |""".stripMargin
      )
      Files.writeString(
        duplicate,
        """-- object: second_view copy
          |-- folder: views
          |-- depends_on: second_view
          |create or replace view first_view as
          |select 1 as id;
          |
          |create or replace view second_view as
          |select 2 as id;
          |""".stripMargin
      )

      val report = Validator[IO](DbKind.Postgres)
        .validate(
          List(
            SqlFile("views", grouped, "001_grouped_views.sql", "core/06_views/001_grouped_views.sql"),
            SqlFile("views", duplicate, "002_second_view.sql", "core/06_views/002_second_view.sql")
          )
        )
        .unsafeRunSync()

      assert(report.errors.exists(_.contains("duplicate view body for second_view")))
    }
  }

  test("repository postgres SQL has no duplicate table or object body patches") {
    val discovered = DiscoveryService[IO]().discover(Path.of("sql/postgres"), DbKind.Postgres).unsafeRunSync()
    val report = Validator[IO](DbKind.Postgres).validate(discovered.files).unsafeRunSync()
    val duplicateErrors = report.errors.filter(error =>
      error.contains("duplicates CREATE TABLE column") || error.contains("ignoring header comments")
    )

    assertEquals(duplicateErrors, Nil)
  }

  test("accepts multiline create or replace procedure in functions folder") {
    withTempDir { dir =>
      val path = dir.resolve("001_proc.sql")
      Files.writeString(
        path,
        """-- object: demo_proc
          |-- folder: functions
          |-- depends_on: -
          |CREATE OR REPLACE
          |PROCEDURE demo_proc AS
          |BEGIN
          |  NULL;
          |END;
          |/
          |""".stripMargin
      )

      val report = Validator[IO](DbKind.Oracle)
        .validate(List(SqlFile("functions", path, "001_proc.sql", "001_proc.sql")))
        .unsafeRunSync()

      assert(!report.warnings.exists(_.contains("CREATE OR REPLACE FUNCTION")))
      assert(!report.hasErrors)
    }
  }

  test("accepts Oracle editionable procedures in routine folders") {
    List("EDITIONABLE", "NONEDITIONABLE").foreach { editionability =>
      withTempDir { dir =>
        val path = dir.resolve(s"001_${editionability.toLowerCase}_proc.sql")
        Files.writeString(
          path,
          s"""-- object: demo_proc
             |-- folder: procedures
             |-- depends_on: -
             |CREATE OR REPLACE $editionability PROCEDURE demo_proc AS
             |BEGIN
             |  NULL;
             |END;
             |/
             |""".stripMargin
        )

        val report = Validator[IO](DbKind.Oracle)
          .validate(List(SqlFile("procedures", path, path.getFileName.toString, path.getFileName.toString)))
          .unsafeRunSync()

        assert(!report.warnings.exists(_.contains("CREATE OR REPLACE PROCEDURE")))
        assert(!report.hasErrors)
      }
    }
  }

  private def withTempDir[A](run: Path => A): A =
    val dir = Files.createTempDirectory("schema-migrator-validator")
    try run(dir)
    finally deleteRecursively(dir)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then Files.walk(path).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
