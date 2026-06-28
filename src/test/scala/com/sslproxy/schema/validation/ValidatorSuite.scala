package com.sslproxy.schema.validation

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.DbKind
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

  private def withTempDir[A](run: Path => A): A =
    val dir = Files.createTempDirectory("schema-migrator-validator")
    try run(dir)
    finally deleteRecursively(dir)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then Files.walk(path).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
