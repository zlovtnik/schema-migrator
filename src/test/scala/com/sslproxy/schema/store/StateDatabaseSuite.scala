package com.sslproxy.schema.store

import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.sql.SQLException
import java.nio.file.{Files, Path}

class StateDatabaseSuite extends FunSuite:
  test("accepts only TiDB v8.5 or newer") {
    assert(StateDatabase.isSupportedTiDB("5.7.25-TiDB-v8.5.0"))
    assert(StateDatabase.isSupportedTiDB("5.7.25-TiDB-v9.0.1"))
    assert(!StateDatabase.isSupportedTiDB("5.7.25-TiDB-v8.4.9"))
    assert(!StateDatabase.isSupportedTiDB("8.5.0 MySQL Community Server"))
  }

  test("loads a pinned canonical schema contract") {
    val contract = StateSchemaContract.load.unsafeRunSync()
    val manifest = List(Path.of("."), Path.of("..", ".."))
      .map(_.resolve("sql/tidb/schema_migrator/manifest.yaml").normalize())
      .find(path => Files.isRegularFile(path))
      .getOrElse(fail("canonical schema_migrator manifest is missing"))
    val values = Files.readAllLines(manifest).toArray.toList.map(_.toString).collect {
      case line if line.startsWith("schema_version:") => "version" -> line.stripPrefix("schema_version:").trim
      case line if line.startsWith("manifest_sha256:") => "checksum" -> line.stripPrefix("manifest_sha256:").trim
    }.toMap

    assertEquals(contract.version, values("version"))
    assertEquals(contract.checksum, values("checksum"))
  }

  test("maps ledger and readiness discrepancies to safe stable categories") {
    val contract = StateSchemaContract("001", "a" * 64)

    val missingLedger = StateSchemaVerificationFailure.ledgerFailure(contract, None).getOrElse(fail("expected ledger failure"))
    assertEquals(missingLedger.category, "ledger-version-checksum")
    assertEquals(missingLedger.detail, "missing version 001")

    val checksumMismatch = StateSchemaVerificationFailure.ledgerFailure(contract, Some("b" * 64)).getOrElse(fail("expected checksum failure"))
    assertEquals(checksumMismatch.category, "ledger-version-checksum")
    assert(checksumMismatch.detail.contains("expected="))

    val missingReadiness = StateSchemaVerificationFailure.readinessFailure(contract, None).getOrElse(fail("expected readiness failure"))
    assertEquals(missingReadiness.category, "readiness")
    assertEquals(missingReadiness.detail, "row is missing")

    val readinessMismatch = StateSchemaVerificationFailure
      .readinessFailure(contract, Some(("001", "000", "a" * 64, "b" * 64, false)))
      .getOrElse(fail("expected readiness mismatch"))
    assertEquals(readinessMismatch.category, "readiness")
    assert(readinessMismatch.detail.contains("applied_version=000"))
    assert(readinessMismatch.detail.contains("ready=false"))
  }

  test("reports a safe categorized runtime gate error") {
    val failure = StateSchemaVerificationFailure(StateSchemaVerificationFailure.SessionTimeZone, "session time zone must be UTC")
    val message = StateSchemaVerificationFailure.topLevel(failure).getMessage

    assert(message.contains("[session-timezone]"))
    assert(message.contains("session time zone must be UTC"))
    assert(!message.contains("jdbc:"))
  }

  test("retries TiDB transaction conflicts but not terminal SQL errors") {
    val serialization = SQLException("write conflict", "40001", 0)
    val deadlock = SQLException("deadlock", "HY000", 1213)
    val syntax = SQLException("syntax", "42000", 1064)

    assert(TiDBTransactionRetry.isRetryable(serialization))
    assert(TiDBTransactionRetry.isRetryable(deadlock))
    assert(!TiDBTransactionRetry.isRetryable(syntax))
  }
