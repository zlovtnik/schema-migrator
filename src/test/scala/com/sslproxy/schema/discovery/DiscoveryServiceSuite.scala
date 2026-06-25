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
        discovered.files.map(_.relativePath),
        List(
          "cron/000_unschedule.sql",
          "materialized_views/001_view.sql",
          "cron/001_schedule.sql"
        )
      )
    }
  }

  private def withTempDir[A](run: Path => A): A =
    val dir = Files.createTempDirectory("schema-migrator-discovery")
    try run(dir)
    finally deleteRecursively(dir)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      scala.util.Using.resource(Files.walk(path)) { stream =>
        stream.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
      }

