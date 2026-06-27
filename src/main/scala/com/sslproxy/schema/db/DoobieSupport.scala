package com.sslproxy.schema.db

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.error.MigratorError
import doobie.Transactor
import doobie.free.FC
import doobie.util.transactor.Strategy

import java.sql.SQLException
import java.util.Properties

object DoobieSupport:
  def postgresDriverManagerTransactor(config: JdbcConnectionConfig): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      config.driver,
      config.url,
      properties(config),
      None
    )

  def postgresSessionTransactor(config: JdbcConnectionConfig): Resource[IO, Transactor[IO]] =
    val base = postgresDriverManagerTransactor(config)
    base.connect(base.kernel)
      .map(connection => Transactor.fromConnection[IO](connection, None))
      .handleErrorWith {
        case error: SQLException =>
          Resource.eval(IO.raiseError[Transactor[IO]](
            MigratorError.Connection(s"database connection failed: ${error.getMessage}", error)
          ))
        case error =>
          Resource.eval(IO.raiseError[Transactor[IO]](error))
      }

  def withoutTransaction(transactor: Transactor[IO]): Transactor[IO] =
    Transactor.strategy.set(
      transactor,
      Strategy(FC.setAutoCommit(true), FC.unit, FC.unit, FC.unit)
    )

  private def properties(config: JdbcConnectionConfig): Properties =
    val props = Properties()
    config.user.foreach(props.setProperty("user", _))
    config.password.foreach(props.setProperty("password", _))
    props
