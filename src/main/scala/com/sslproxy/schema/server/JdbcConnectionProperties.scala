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
    user.foreach(value => setProperty(properties, "user", value))
    password.foreach(value => setProperty(properties, "password", value))

    val trimmed = jdbcUrl.trim
    val seconds = math.max(1L, timeout.toSeconds).toString
    val millis = math.max(1L, timeout.toMillis).toString

    if trimmed.startsWith("jdbc:postgresql:") then
      setProperty(properties, "connectTimeout", seconds)
      setProperty(properties, "loginTimeout", seconds)
      setProperty(properties, "socketTimeout", seconds)
    else if trimmed.startsWith("jdbc:oracle:thin:") then
      setProperty(properties, "oracle.net.CONNECT_TIMEOUT", millis)
      setProperty(properties, "oracle.net.OUTBOUND_CONNECT_TIMEOUT", millis)
      setProperty(properties, "oracle.net.READ_TIMEOUT", millis)
      setProperty(properties, "oracle.jdbc.ReadTimeout", millis)

    properties

  private def setProperty(properties: Properties, key: String, value: String): Unit =
    properties.setProperty(key, value)
    ()
