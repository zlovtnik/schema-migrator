package com.sslproxy.schema

import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.{DbKind, MigratorConfig, ServerConfig}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

trait TestSqlSupport:
  protected def withSqlDir(run: Path => IO[ExitCode]): ExitCode =
    cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-commands")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { root =>
        val sqlDir = root.resolve("sql")
        writeSqlManifest(sqlDir) *> run(sqlDir)
      }
      .unsafeRunSync()

  protected def config(sqlDir: Path): MigratorConfig =
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

  protected def writeSqlManifest(sqlDir: Path): IO[Unit] =
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

  protected def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
      ()
