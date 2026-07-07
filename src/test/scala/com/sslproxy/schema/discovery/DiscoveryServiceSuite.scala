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

  test("uses manifest layout from repository sql root with selected customer overlay") {
    withTempDir { dir =>
      val root = dir.resolve("sql")
      val core = root.resolve("postgres").resolve("core")
      val contracts = root.resolve("postgres").resolve("contracts")
      val customer = root.resolve("postgres").resolve("customers").resolve("acme")
      Files.createDirectories(core.resolve("03_tables"))
      Files.createDirectories(contracts.resolve("views"))
      Files.createDirectories(customer.resolve("extensions"))
      Files.writeString(core.resolve("03_tables").resolve("001_core.sql"), sql("core_table", "tables"))
      Files.writeString(contracts.resolve("views").resolve("001_contract.sql"), sql("contract_view", "views"))
      Files.writeString(customer.resolve("extensions").resolve("001_overlay.sql"), sql("acme_overlay", "extensions"))
      Files.writeString(
        core.resolve("manifest.yaml"),
        """engine: postgres
          |layer: core
          |apply_order:
          |  - 03_tables/001_core.sql
          |""".stripMargin
      )
      Files.writeString(
        contracts.resolve("manifest.yaml"),
        """engine: postgres
          |layer: contracts
          |apply_order:
          |  - views/001_contract.sql
          |""".stripMargin
      )
      Files.writeString(
        customer.resolve("manifest.yaml"),
        """engine: postgres
          |layer: customer
          |customer: acme
          |apply_order:
          |  - extensions/001_overlay.sql
          |""".stripMargin
      )

      val discovered = DiscoveryService[IO]().discover(root, DbKind.Postgres, Some("acme")).unsafeRunSync()

      assertEquals(
        normalizePaths(discovered.files.map(_.relativePath)),
        List(
          "postgres/core/03_tables/001_core.sql",
          "postgres/contracts/views/001_contract.sql",
          "postgres/customers/acme/extensions/001_overlay.sql"
        )
      )
      assertEquals(discovered.files.map(_.folder), List("tables", "views", "extensions"))
      assertEquals(discovered.warnings, Nil)
    }
  }

  test("manifest layout leaves customer overlays out unless selected") {
    withTempDir { dir =>
      val engineRoot = dir.resolve("sql").resolve("postgres")
      val core = engineRoot.resolve("core")
      val customer = engineRoot.resolve("customers").resolve("acme")
      Files.createDirectories(core.resolve("03_tables"))
      Files.createDirectories(engineRoot.resolve("contracts"))
      Files.createDirectories(customer.resolve("extensions"))
      Files.writeString(core.resolve("03_tables").resolve("001_core.sql"), sql("core_table", "tables"))
      Files.writeString(customer.resolve("extensions").resolve("001_overlay.sql"), sql("acme_overlay", "extensions"))
      Files.writeString(
        core.resolve("manifest.yaml"),
        """engine: postgres
          |layer: core
          |apply_order:
          |  - 03_tables/001_core.sql
          |""".stripMargin
      )
      Files.writeString(
        engineRoot.resolve("contracts").resolve("manifest.yaml"),
        """engine: postgres
          |layer: contracts
          |apply_order: []
          |""".stripMargin
      )
      Files.writeString(
        customer.resolve("manifest.yaml"),
        """engine: postgres
          |layer: customer
          |customer: acme
          |apply_order:
          |  - extensions/001_overlay.sql
          |""".stripMargin
      )

      val discovered = DiscoveryService[IO]().discover(engineRoot, DbKind.Postgres).unsafeRunSync()

      assertEquals(normalizePaths(discovered.files.map(_.relativePath)), List("core/03_tables/001_core.sql"))
      assertEquals(discovered.warnings, Nil)
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

  test("normalizes stored engine layout paths to canonical folders") {
    val files = List(
      SqlFile(
        "sql/postgres/core/03_tables",
        Path.of("sql/postgres/core/03_tables/001_table.sql"),
        "001_table.sql",
        "sql/postgres/core/03_tables/001_table.sql"
      ),
      SqlFile(
        "sql/oracle/core/03_tables",
        Path.of("sql/oracle/core/03_tables/001_oracle.sql"),
        "001_oracle.sql",
        "sql/oracle/core/03_tables/001_oracle.sql"
      )
    )

    val discovered = DiscoveryService[IO]().discoverFromFiles(files, DbKind.Postgres)

    assertEquals(discovered.files.map(_.folder), List("tables"))
    assertEquals(discovered.files.map(_.relativePath), List("postgres/core/03_tables/001_table.sql"))
    assertEquals(discovered.warnings, Nil)
  }

  test("ignores auxiliary registry and teardown folders from stored SQL files") {
    val files = List(
      SqlFile(
        "sql/registry",
        Path.of("sql/registry/drift_report.sql"),
        "drift_report.sql",
        "sql/registry/drift_report.sql"
      ),
      SqlFile(
        "sql/teardown",
        Path.of("sql/teardown/oracle_nuclear_drop.sql"),
        "oracle_nuclear_drop.sql",
        "sql/teardown/oracle_nuclear_drop.sql"
      ),
      SqlFile(
        "sql/postgres/core/03_tables",
        Path.of("sql/postgres/core/03_tables/001_table.sql"),
        "001_table.sql",
        "sql/postgres/core/03_tables/001_table.sql"
      )
    )

    val discovered = DiscoveryService[IO]().discoverFromFiles(files, DbKind.Postgres)

    assertEquals(discovered.files.map(_.relativePath), List("postgres/core/03_tables/001_table.sql"))
    assertEquals(discovered.warnings, Nil)
  }

  test("folder discovery ignores auxiliary folders but still warns on unknown SQL folders") {
    withTempDir { dir =>
      Files.createDirectories(dir.resolve("tables"))
      Files.createDirectories(dir.resolve("registry"))
      Files.createDirectories(dir.resolve("teardown"))
      Files.createDirectories(dir.resolve("scratch"))
      Files.writeString(dir.resolve("tables").resolve("001_table.sql"), "select 1;")
      Files.writeString(dir.resolve("registry").resolve("drift_report.sql"), "select 1;")
      Files.writeString(dir.resolve("teardown").resolve("oracle_nuclear_drop.sql"), "select 1;")
      Files.writeString(dir.resolve("scratch").resolve("001_unknown.sql"), "select 1;")

      val discovered = DiscoveryService[IO]().discover(dir, DbKind.Postgres).unsafeRunSync()

      assertEquals(discovered.files.map(_.relativePath), List("tables/001_table.sql"))
      assert(!discovered.warnings.exists(_.contains("registry")))
      assert(!discovered.warnings.exists(_.contains("teardown")))
      assert(discovered.warnings.exists(_.contains("scratch")))
    }
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

  private def sql(objectName: String, folder: String): String =
    s"""-- object: $objectName
       |-- folder: $folder
       |-- depends_on: -
       |select 1;
       |""".stripMargin

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      scala.util.Using.resource(Files.walk(path)) { stream =>
        stream.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
      }
