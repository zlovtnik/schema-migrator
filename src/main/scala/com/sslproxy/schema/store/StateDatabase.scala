package com.sslproxy.schema.store

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.config.StateStoreConfig
import com.sslproxy.schema.db.postgres.PostgresProvider
import com.zaxxer.hikari.HikariConfig
import doobie.*
import doobie.free.FS
import doobie.hikari.HikariTransactor
import doobie.hi.HC
import doobie.implicits.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class StateDatabase(transactor: Transactor[IO])

object StateDatabase:
  private val MigrationVersion = 1
  private val MigrationResource = "state-migrations/001_initial.sql"
  private val MigrationLockKey = 759903457245995748L

  def resource(config: StateStoreConfig): Resource[IO, StateDatabase] =
    for
      jdbc <- Resource.eval(IO.fromEither(PostgresProvider.normalize(config.url).leftMap(IllegalArgumentException(_))))
      hikariConfig <- Resource.eval(IO.delay {
        val value = HikariConfig()
        value.setDriverClassName(jdbc.driver)
        value.setJdbcUrl(jdbc.url)
        value.setUsername(config.user)
        value.setPassword(config.password)
        value.setMaximumPoolSize(config.poolSize)
        value.setMinimumIdle(1)
        value.setPoolName("schema-migrator-state")
        value.setConnectionInitSql(s"SET search_path TO \"${config.schema}\", public")
        value
      })
      xa <- HikariTransactor.fromHikariConfig[IO](hikariConfig)
      database = StateDatabase(xa)
      _ <- Resource.eval(database.migrate)
    yield database

  extension (database: StateDatabase)
    def migrate: IO[Unit] =
      for
        sqlText <- readMigration
        checksum = sha256(sqlText)
        _ <- migration(checksum, sqlText).transact(database.transactor)
      yield ()

  private def migration(checksum: String, sqlText: String): ConnectionIO[Unit] =
    for
      _ <- sql"select pg_advisory_xact_lock($MigrationLockKey)".query[Unit].unique
      _ <- sql"""
        create table if not exists state_schema_migrations (
          version integer primary key,
          checksum text not null,
          applied_at timestamptz not null default now()
        )
      """.update.run
      existing <- sql"select checksum from state_schema_migrations where version = $MigrationVersion"
        .query[String]
        .option
      _ <- existing match
        case Some(value) if value == checksum => ().pure[ConnectionIO]
        case Some(value) =>
          FC.raiseError(
            IllegalStateException(
              s"state migration $MigrationVersion checksum mismatch: database=$value resource=$checksum"
            )
          )
        case None =>
          execute(sqlText) *> sql"""
            insert into state_schema_migrations (version, checksum)
            values ($MigrationVersion, $checksum)
          """.update.run.void
    yield ()

  private def execute(sqlText: String): ConnectionIO[Unit] =
    HC.createStatement(FS.execute(sqlText)).void

  private def readMigration: IO[String] =
    Resource
      .fromAutoCloseable(
        IO.blocking(
          Option(Thread.currentThread().getContextClassLoader.getResourceAsStream(MigrationResource))
            .getOrElse(throw IllegalStateException(s"missing state migration resource $MigrationResource"))
        )
      )
      .use(stream => IO.blocking(String(stream.readAllBytes(), StandardCharsets.UTF_8)))

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
