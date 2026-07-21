package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.{DbProvider, JdbcConnectionConfig, TargetDescriptor}
import com.sslproxy.schema.db.oracle.OracleProvider
import com.sslproxy.schema.db.postgres.PostgresProvider
import com.sslproxy.schema.db.tidb.TiDBProvider
import io.r2dbc.spi.{ConnectionFactories, ConnectionFactoryOptions}
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.store.StoredTarget

private[server] object TargetDatabase:
  def dbKindFor(jdbcUrl: String): Either[String, DbKind] =
    TargetDescriptor.parse(jdbcUrl).map(_.dbKind)

  def providerFor(config: MigratorConfig, target: StoredTarget): IO[(DbKind, DbProvider)] =
    IO.fromEither(dbKindFor(target.target.jdbc_url).leftMap(MigratorError.Validation(_))).flatMap {
      case DbKind.Postgres =>
        IO.fromEither {
          PostgresProvider
            .normalize(target.target.jdbc_url)
            .map(normalized =>
              DbKind.Postgres -> PostgresProvider(
                JdbcConnectionConfig(
                  driver = normalized.driver,
                  url = normalized.url,
                  user = normalized.user,
                  password = target.password.orElse(normalized.password)
                )
              )
            )
            .leftMap(MigratorError.Connection(_))
        }
      case DbKind.Oracle =>
        OracleProvider
          .fromJdbcUrl(config, target.target.jdbc_url, target.password)
          .map(provider => DbKind.Oracle -> provider)
      case DbKind.TiDB =>
        IO.delay {
          val r2dbcUrl = target.target.jdbc_url
            .replace("jdbc:mysql://", "mysql://")
            .replace("mysql://", "r2dbc:mysql://")
          val opts = ConnectionFactoryOptions.parse(r2dbcUrl)
          val builder = ConnectionFactoryOptions.builder().from(opts)
          target.password.foreach(p => builder.option(ConnectionFactoryOptions.PASSWORD, p))
          val cf = ConnectionFactories.get(builder.build())
          DbKind.TiDB -> TiDBProvider(cf)
        }
    }
