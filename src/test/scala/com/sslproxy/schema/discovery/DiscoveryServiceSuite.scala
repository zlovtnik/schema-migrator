package com.sslproxy.schema.discovery

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.DbKind
import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class DiscoveryServiceSuite extends FunSuite:
  test("sorts files by name inside a folder") {
    withTempDir { dir =>
      val tables = Files.createDirectories(dir.resolve("tables"))
      Files.writeString(tables.resolve("002_b.sql"), "select 1;")
      Files.writeString(tables.resolve("001_a.sql"), "select 1;")

      val discovered = DiscoveryService[IO]().discover(dir, DbKind.Postgres).unsafeRunSync()
      val tableNames = discovered.files.filter(_.folder == "tables").map(_.name)

      assertEquals(tableNames, List("001_a.sql", "002_b.sql"))
    }
  }

  test("places cron pre-apply hooks before materialized views") {
    withTempDir { dir =>
      val cron = Files.createDirectories(dir.resolve("cron"))
      val materializedViews = Files.createDirectories(dir.resolve("materialized_views"))
      Files.writeString(cron.resolve("000_unschedule.sql"), "select 1;")
      Files.writeString(cron.resolve("001_schedule.sql"), "select 1;")
      Files.writeString(materializedViews.resolve("001_view.sql"), "select 1;")

      val discovered = DiscoveryService[IO]().discover(dir, DbKind.Postgres).unsafeRunSync()
      assertEquals(
        normalizePaths(discovered.files.map(_.relativePath)),
        List(
          "cron/000_unschedule.sql",
          "materialized_views/001_view.sql",
          "cron/001_schedule.sql"
        )
      )
    }
  }

  test("normalizes stored sql root paths and ignores Oracle folders for Postgres") {
    val files = List(
      SqlFile("sql/tables", Path.of("sql/tables/001_table.sql"), "001_table.sql", "sql/tables/001_table.sql"),
      SqlFile(
        "sql/oracle/functions",
        Path.of("sql/oracle/functions/001_function.sql"),
        "001_function.sql",
        "sql/oracle/functions/001_function.sql"
      ),
      SqlFile("sql", Path.of("sql/000_baseline.sql"), "000_baseline.sql", "sql/000_baseline.sql")
    )

    val discovered = DiscoveryService[IO]().discoverFromFiles(files, DbKind.Postgres)

    assertEquals(discovered.files.map(_.folder), List("tables"))
    assertEquals(discovered.files.map(_.relativePath), List("tables/001_table.sql"))
    assertEquals(discovered.warnings, Nil)
  }

  test("discovers split Oracle baseline folders in deterministic order") {
    withTempDir { dir =>
      FolderOrder.oracle.foreach(folder => Files.createDirectories(dir.resolve(folder)))
      Files.writeString(dir.resolve("views").resolve("001_view.sql"), "select 1;")
      Files.writeString(dir.resolve("session").resolve("000_session.sql"), "select 1;")
      Files.writeString(dir.resolve("tables").resolve("001_table.sql"), "select 1;")
      Files.writeString(dir.resolve("seed_data").resolve("001_seed.sql"), "select 1;")
      Files.writeString(dir.resolve("scheduler").resolve("001_job.sql"), "select 1;")

      val discovered = DiscoveryService[IO]().discover(dir, DbKind.Oracle).unsafeRunSync()

      assertEquals(
        normalizePaths(discovered.files.map(_.relativePath)),
        List(
          "session/000_session.sql",
          "tables/001_table.sql",
          "seed_data/001_seed.sql",
          "views/001_view.sql",
          "scheduler/001_job.sql"
        )
      )
    }
  }

  private def withTempDir[A](run: Path => A): A =
    val dir = Files.createTempDirectory("schema-migrator-discovery")
    try run(dir)
    finally deleteRecursively(dir)

  private def normalizePaths(paths: List[String]): List[String] =
    paths.map(_.replace(java.io.File.separatorChar, '/'))

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      scala.util.Using.resource(Files.walk(path)) { stream =>
        stream.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
      }
