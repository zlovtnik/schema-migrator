package com.sslproxy.schema.cli

import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.{DbKind, MigratorConfig, ServerConfig}
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class CommandsSuite extends FunSuite:
  test("validate does not require database connection settings") {
    val exitCode = withSqlDir { sqlDir =>
      Commands.run(config(sqlDir), CliCommand.Validate)
    }

    assertEquals(exitCode, ExitCode.Success)
  }

  test("dry-run apply does not require database connection settings") {
    val exitCode = withSqlDir { sqlDir =>
      Commands.run(config(sqlDir).copy(dryRun = true), CliCommand.Apply)
    }

    assertEquals(exitCode, ExitCode.Success)
  }

  private def withSqlDir(run: Path => IO[ExitCode]): ExitCode =
    cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-commands")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { root =>
        val sqlDir = root.resolve("sql")
        writeSqlManifest(sqlDir) *> run(sqlDir)
      }
      .unsafeRunSync()

  private def config(sqlDir: Path): MigratorConfig =
    MigratorConfig(
      dbKind = DbKind.Postgres,
      databaseUrl = None,
      sqlDir = sqlDir,
      dryRun = false,
      verbose = false,
      continueOnError = false,
      connectRetries = 0,
      connectRetryBackoff = 1.second,
      oracleWallet = None,
      oracleTnsAlias = None,
      oracleUser = None,
      oraclePasswordFile = None,
      json = false,
      server = ServerConfig(
        host = "127.0.0.1",
        port = 8080,
        corsOrigins = Set.empty,
        encryptKeyBase64 = None,
        jwtSecret = "",
        devAuthSecret = "",
        dbTestAllowedHosts = Set.empty,
        patchStageDir = sqlDir.resolve("patches")
      )
    )

  private def writeSqlManifest(sqlDir: Path): IO[Unit] =
    val tableDir = sqlDir.resolve("tables")
    val sql =
      """-- object: public.devices
        |-- folder: tables
        |-- depends_on: -
        |create table if not exists public.devices (
        |  id bigint primary key
        |);
        |""".stripMargin
    IO.blocking {
      Files.createDirectories(tableDir)
      Files.writeString(tableDir.resolve("001_devices.sql"), sql, StandardCharsets.UTF_8)
      ()
    }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
