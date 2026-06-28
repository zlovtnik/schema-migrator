package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import com.sslproxy.schema.store.{ConnectionTestResult, StoredTarget, TargetPayload}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import scala.concurrent.duration.*

object DbPing:
  private val connectTimeout = 5.seconds

  def test(payload: TargetPayload): IO[ConnectionTestResult] =
    run(payload, payload.password.getOrElse(""))

  def test(stored: StoredTarget): IO[ConnectionTestResult] =
    val target = stored.target
    val payload = TargetPayload(
      label = target.label,
      app_name = target.app_name,
      env = target.env,
      host = target.host,
      port = target.port,
      dbname = target.dbname,
      user = target.user,
      password = stored.password,
      schema = target.schema,
      ssl_mode = target.ssl_mode
    )
    run(payload, stored.password.getOrElse(""))

  private def run(payload: TargetPayload, password: String): IO[ConnectionTestResult] =
    for
      started <- Clock[IO].monotonic
      result <- IO.interruptibleMany {
        val url = jdbcUrl(payload)
        val previousLoginTimeout = DriverManager.getLoginTimeout
        DriverManager.setLoginTimeout(connectTimeout.toSeconds.toInt)
        try
          val connection = DriverManager.getConnection(url, payload.user, password)
          try connection.isValid(connectTimeout.toSeconds.toInt)
          finally connection.close()
        finally
          DriverManager.setLoginTimeout(previousLoginTimeout)
      }.timeout(connectTimeout + 1.second).attempt
      ended <- Clock[IO].monotonic
      elapsed = (ended - started).toMillis
    yield result match
      case Right(true) => ConnectionTestResult(ok = true, latency_ms = Some(elapsed), error = None)
      case Right(false) => ConnectionTestResult(ok = false, latency_ms = Some(elapsed), error = Some("connection is not valid"))
      case Left(error) => ConnectionTestResult(ok = false, latency_ms = Some(elapsed), error = Some(error.getMessage))

  private def jdbcUrl(payload: TargetPayload): String =
    val base = s"jdbc:postgresql://${payload.host}:${payload.port}/${encode(payload.dbname)}"
    val params = List(
      "sslmode" -> payload.ssl_mode,
      "currentSchema" -> payload.schema,
      "connectTimeout" -> connectTimeout.toSeconds.toString
    ).map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")
    s"$base?$params"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
