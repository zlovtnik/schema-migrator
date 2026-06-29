package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.store.{ConnectionTestResult, StoredTarget, TargetPayload}
import io.circe.Json
import io.circe.syntax.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.URI
import java.sql.DriverManager
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
    for
      started <- Clock[IO].monotonic
      result <- IO
        .interruptibleMany {
          val connection = connect(jdbcUrl, password)
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
        case Left(_) => ConnectionTestResult(ok = false, latency_ms = Some(elapsed), error = Some("connection test failed"))
      host = extractHost(jdbcUrl)
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

  private def connect(jdbcUrl: String, password: Option[String]) =
    DriverManager.getConnection(jdbcUrl, JdbcConnectionProperties.withTimeouts(jdbcUrl, password, connectTimeout))
