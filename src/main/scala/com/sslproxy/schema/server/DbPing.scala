package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.store.{ConnectionTestResult, StoredTarget, TargetPayload}
import io.circe.Json
import io.circe.syntax.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.URI
import java.sql.{DriverManager, SQLException}
import scala.concurrent.duration.*

object DbPing:
  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory[IO].getLogger

  private val connectTimeout = 5.seconds

  def test(payload: TargetPayload): IO[ConnectionTestResult] =
    run(payload.jdbc_url, payload.password)

  def test(stored: StoredTarget): IO[ConnectionTestResult] =
    run(stored.target.jdbc_url, stored.password)

  private def run(jdbcUrl: String, password: Option[String]): IO[ConnectionTestResult] =
    val settings = JdbcConnectionProperties.normalize(jdbcUrl, password)
    for
      started <- Clock[IO].monotonic
      result <- IO
        .interruptibleMany {
          val connection = connect(settings)
          try connection.isValid(connectTimeout.toSeconds.toInt)
          finally connection.close()
        }
        .timeout(connectTimeout + 1.second)
        .attempt
      ended <- Clock[IO].monotonic
      elapsed = (ended - started).toMillis
      testResult = result match
        case Right(true) => ConnectionTestResult(ok = true, latency_ms = Some(elapsed), error = None)
        case Right(false) =>
          ConnectionTestResult(ok = false, latency_ms = Some(elapsed), error = Some("connection is not valid"))
        case Left(error) =>
          ConnectionTestResult(ok = false, latency_ms = Some(elapsed), error = Some(connectionError(error)))
      host = extractHost(settings.jdbcUrl)
      _ <- logConnectionTest(host, testResult, elapsed)
    yield testResult

  private def extractHost(jdbcUrl: String): String =
    val trimmed = jdbcUrl.trim
    val host =
      if trimmed.startsWith("jdbc:postgresql://") then uriHost(trimmed.stripPrefix("jdbc:"))
      else if trimmed.startsWith("jdbc:oracle:thin:@//") then
        uriHost("oracle:" + trimmed.stripPrefix("jdbc:oracle:thin:@"))
      else None
    host.getOrElse("unknown")

  private def uriHost(value: String): Option[String] =
    Either.catchNonFatal(URI.create(value).getHost).toOption.flatMap(Option(_)).filter(_.nonEmpty)

  private def logConnectionTest(host: String, result: ConnectionTestResult, latencyMs: Long): IO[Unit] =
    val fields = List(
      "event" -> Json.fromString("db_connection_test"),
      "host" -> Json.fromString(host),
      "ok" -> Json.fromBoolean(result.ok),
      "latency_ms" -> Json.fromLong(latencyMs)
    ) ++ result.error.map(err => "error" -> Json.fromString(err)).toList
    logger.info(Json.obj(fields*).noSpaces)

  private[server] def connectionError(error: Throwable): String =
    val message = error match
      case sql: SQLException =>
        val state = Option(sql.getSQLState).fold("")(value => s" [$value]")
        s"connection test failed$state: ${sql.getMessage}"
      case other => s"connection test failed: ${other.getMessage}"
    redact(message)

  private def redact(value: String): String =
    value
      .replaceAll("(?i)(password=)[^&;\\s]+", "$1<redacted>")
      .replaceAll("(?i)(pwd=)[^&;\\s]+", "$1<redacted>")
      .replaceAll("(?i)(//[^:/?#]+:)[^@/?#]+(@)", "$1<redacted>$2")

  private def connect(settings: JdbcConnectionSettings) =
    DriverManager.getConnection(
      settings.jdbcUrl,
      JdbcConnectionProperties.withTimeouts(settings.jdbcUrl, settings.password, connectTimeout, user = settings.user)
    )
