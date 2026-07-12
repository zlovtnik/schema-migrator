package com.sslproxy.schema.db

import cats.effect.{IO, Resource}
import com.sslproxy.schema.error.MigratorError
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, SQLException, Statement}
import java.util.Properties
import scala.concurrent.duration.FiniteDuration

/** JDBC connection settings shared by provider configuration.
  *
  * Postgres consumes this through Doobie transactor construction; raw
  * `Connection` handling is retained only for the Oracle path.
  */
final case class JdbcConnectionConfig(
  driver: String,
  url: String,
  user: Option[String] = None,
  password: Option[String] = None
)

/** Legacy raw JDBC helpers retained for Oracle support.
  *
  * New Postgres database code should use `DoobieSupport` and
  * `ConnectionIO` instead of these statement/query helpers.
  */
object JdbcSupport:
  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory[IO].getLogger

  def connection(config: JdbcConnectionConfig): Resource[IO, Connection] =
    Resource.make {
      IO.blocking {
        Class.forName(config.driver)
        val properties = new Properties()
        config.user.foreach(properties.setProperty("user", _))
        config.password.foreach(properties.setProperty("password", _))
        DriverManager.getConnection(config.url, properties)
      }.adaptError { case error: SQLException =>
        MigratorError.Connection(s"database connection failed: ${error.getMessage}", error)
      }
    }(connection => IO.blocking(connection.close()).handleErrorWith(_ => IO.unit))

  def executeStatement(connection: Connection, sql: String): Unit =
    val statement = connection.createStatement()
    try statement.execute(sql)
    finally statement.close()

  def executePrepared(connection: Connection, sql: String)(bind: PreparedStatement => Unit): Int =
    val statement = connection.prepareStatement(sql)
    try
      bind(statement)
      statement.executeUpdate()
    finally statement.close()

  def queryOne[A](connection: Connection, sql: String)(read: ResultSet => A): Option[A] =
    val statement = connection.createStatement()
    try
      val resultSet = statement.executeQuery(sql)
      try if resultSet.next() then Some(read(resultSet)) else None
      finally resultSet.close()
    finally statement.close()

  def queryPrepared[A](connection: Connection, sql: String)(
    bind: PreparedStatement => Unit
  )(read: ResultSet => A): List[A] =
    val statement = connection.prepareStatement(sql)
    try
      bind(statement)
      val resultSet = statement.executeQuery()
      try
        val rows = scala.collection.mutable.ListBuffer.empty[A]
        while resultSet.next() do rows.append(read(resultSet))
        rows.toList
      finally resultSet.close()
    finally statement.close()

  def queryList[A](connection: Connection, sql: String)(read: ResultSet => A): List[A] =
    val statement = connection.createStatement()
    try
      val resultSet = statement.executeQuery(sql)
      try
        val rows = scala.collection.mutable.ListBuffer.empty[A]
        while resultSet.next() do rows.append(read(resultSet))
        rows.toList
      finally resultSet.close()
    finally statement.close()

  def sqlError(file: String, error: SQLException, databaseName: String): String =
    val state = Option(error.getSQLState).getOrElse("unknown")
    val vendor = error.getErrorCode
    val base = s"$file: $databaseName error [$state/$vendor] ${error.getMessage}"
    Option(error.getNextException)
      .map(next => s"$base | next: ${next.getMessage}")
      .getOrElse(base)

  def durationMs(startNanos: Long): Int =
    val millis = (System.nanoTime() - startNanos) / 1000000L
    math.min(Int.MaxValue.toLong, math.max(0L, millis)).toInt

  def retry[A](attempts: Int, backoff: FiniteDuration)(operation: IO[A]): IO[A] =
    def loop(remaining: Int, used: Int): IO[A] =
      operation.handleErrorWith {
        case error if MigratorError.isConnectionFailure(error) && remaining > 0 =>
          logger.warn(
            s"warning: connection attempt ${used + 1}/${attempts + 1} failed, retrying in ${backoff.toSeconds}s: ${error.getMessage}"
          ) *> IO.sleep(backoff * math.pow(2.0, used.toDouble).toLong) *> loop(remaining - 1, used + 1)
        case error => IO.raiseError(error)
      }
    loop(attempts, 0)

  def readPasswordFile(path: Path): IO[String] =
    IO.blocking(Files.readString(path, StandardCharsets.UTF_8).trim)

  def appliedBy(): String =
    s"${System.getenv().getOrDefault("HOSTNAME", "unknown-host")}:${ProcessHandle.current().pid()}"
