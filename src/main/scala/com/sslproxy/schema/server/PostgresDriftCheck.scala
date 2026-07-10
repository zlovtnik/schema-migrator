package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.JdbcConnectionConfig
import com.sslproxy.schema.db.postgres.PostgresProvider
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.DiscoveryService
import com.sslproxy.schema.engine.ManifestBuilder
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.store.DriftResponse

private[schema] object PostgresDriftCheck:
  import PostgresDriftAnalyzer.*

  def run(config: MigratorConfig): IO[DriftResponse] =
    nowString.flatMap { now =>
      config.dbKind match
        case DbKind.Oracle =>
          IO.pure(
            DriftResponse(
              target_id = targetId(config),
              db_kind = "oracle",
              supported = false,
              checked_at = now,
              control_summary = None,
              control_objects = Nil,
              items = Nil,
              warnings = List(
                "Oracle drift introspection is not implemented; use check-connection for Oracle validation."
              )
            )
          )
        case DbKind.Postgres =>
          postgresReport(config, now)
    }

  private def postgresReport(config: MigratorConfig, now: String): IO[DriftResponse] =
    for
      jdbc <- jdbcConfig(config)
      _ <- PostgresProvider(jdbc).session.use(_.bootstrap)
      discovery <- DiscoveryService[IO]().discover(config.sqlDir, DbKind.Postgres, config.customer)
      manifest <- ManifestBuilder[IO](SqlDialect.Postgres).build(discovery.files)
      expected = manifest.flatMap(expectedFromManifest)
      snapshot <- PostgresCatalogReader.snapshot(jdbc)
      catalog = mergeCatalog(now, expected, snapshot.objects, snapshot.control)
      items = driftItems(now, expected, snapshot.objects, snapshot.control)
      _ <- PostgresDriftRegistry.record(jdbc, customerName(config), catalog, items)
    yield DriftResponse(
      target_id = targetId(config),
      db_kind = "postgres",
      supported = true,
      checked_at = now,
      control_summary = snapshot.control.summary,
      control_objects = snapshot.control.rows,
      items = items,
      warnings = discovery.warnings ++ snapshot.control.warnings
    )

  private def jdbcConfig(config: MigratorConfig): IO[JdbcConnectionConfig] =
    IO.fromEither {
      config.databaseUrl
        .orElse(sys.env.get("DATABASE_URL"))
        .toRight("DATABASE_URL is required for Postgres")
        .flatMap(PostgresProvider.normalize)
        .leftMap(message => MigratorError.Connection(message))
    }

  private def customerName(config: MigratorConfig): String =
    config.customer.getOrElse("core")

  private def targetId(config: MigratorConfig): String =
    config.customer.fold("postgres:core")(customer => s"postgres:$customer")

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)
