package com.sslproxy.schema.store

import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.sql.SQLException

class StateDatabaseSuite extends FunSuite:
  test("accepts only TiDB v8.5 or newer") {
    assert(StateDatabase.isSupportedTiDB("5.7.25-TiDB-v8.5.0"))
    assert(StateDatabase.isSupportedTiDB("5.7.25-TiDB-v9.0.1"))
    assert(!StateDatabase.isSupportedTiDB("5.7.25-TiDB-v8.4.9"))
    assert(!StateDatabase.isSupportedTiDB("8.5.0 MySQL Community Server"))
  }

  test("loads a pinned canonical schema contract") {
    val contract = StateSchemaContract.load.unsafeRunSync()

    assert(contract.version.nonEmpty)
    assert(contract.checksum.matches("[0-9a-f]{64}"))
  }

  test("retries TiDB transaction conflicts but not terminal SQL errors") {
    val serialization = SQLException("write conflict", "40001", 0)
    val deadlock = SQLException("deadlock", "HY000", 1213)
    val syntax = SQLException("syntax", "42000", 1064)

    assert(TiDBTransactionRetry.isRetryable(serialization))
    assert(TiDBTransactionRetry.isRetryable(deadlock))
    assert(!TiDBTransactionRetry.isRetryable(syntax))
  }
