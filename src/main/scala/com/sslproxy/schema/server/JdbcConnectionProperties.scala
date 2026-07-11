package com.sslproxy.schema.server

import com.sslproxy.schema.db.postgres.PostgresProvider

import java.util.Properties
import scala.concurrent.duration.FiniteDuration

private[server] final case class JdbcConnectionSettings(jdbcUrl: String, user: Option[String], password: Option[String])

private[server] object JdbcConnectionProperties:
  def normalize(jdbcUrl: String, password: Option[String]): JdbcConnectionSettings =
    val trimmed = jdbcUrl.trim
    if trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://") || trimmed.startsWith(
        "jdbc:postgresql://"
      )
    then
      PostgresProvider
        .normalize(trimmed)
        .map(config => JdbcConnectionSettings(config.url, config.user, password.orElse(config.password)))
        .getOrElse(JdbcConnectionSettings(trimmed, None, password))
    else JdbcConnectionSettings(trimmed, None, password)

  def withTimeouts(
    jdbcUrl: String,
    password: Option[String],
    timeout: FiniteDuration,
    user: Option[String] = None
  ): Properties =
    val properties = Properties()
    user.foreach(properties.setProperty("user", _))
    password.foreach(properties.setProperty("password", _))

    val trimmed = jdbcUrl.trim
    val seconds = math.max(1L, timeout.toSeconds).toString
    val millis = math.max(1L, timeout.toMillis).toString

    if trimmed.startsWith("jdbc:postgresql:") then
      properties.setProperty("connectTimeout", seconds)
      properties.setProperty("loginTimeout", seconds)
      properties.setProperty("socketTimeout", seconds)
    else if trimmed.startsWith("jdbc:oracle:thin:") then
      properties.setProperty("oracle.net.CONNECT_TIMEOUT", millis)
      properties.setProperty("oracle.net.OUTBOUND_CONNECT_TIMEOUT", millis)
      properties.setProperty("oracle.net.READ_TIMEOUT", millis)
      properties.setProperty("oracle.jdbc.ReadTimeout", millis)

    properties
