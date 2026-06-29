package com.sslproxy.schema.server

import java.util.Properties
import scala.concurrent.duration.FiniteDuration

private[server] object JdbcConnectionProperties:
  def withTimeouts(jdbcUrl: String, password: Option[String], timeout: FiniteDuration): Properties =
    val properties = Properties()
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
