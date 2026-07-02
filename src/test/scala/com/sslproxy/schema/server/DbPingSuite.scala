package com.sslproxy.schema.server

import munit.FunSuite

import java.sql.SQLException

class DbPingSuite extends FunSuite:
  test("connection errors redact embedded Postgres URL passwords") {
    val error = SQLException("could not connect to postgres://app:secret@db.example/app")

    val message = DbPing.connectionError(error)

    assert(message.contains("postgres://app:<redacted>@db.example/app"))
    assert(!message.contains("secret"))
  }

  test("connection errors redact password query parameters") {
    val error = RuntimeException(
      "jdbc:postgresql://db.example/app?user=app&password=secret&pwd=backup"
    )

    val message = DbPing.connectionError(error)

    assert(message.contains("password=<redacted>"))
    assert(message.contains("pwd=<redacted>"))
    assert(!message.contains("secret"))
    assert(!message.contains("backup"))
  }
