package com.sslproxy.schema.store

import com.sslproxy.schema.validation.ValidationReport
import munit.FunSuite

class ValidationStoreSuite extends FunSuite:
  test("keeps discovery warnings out of the blocking invalid set") {
    val report = ValidationReport(
      warnings = List("scratch/002_ignored_view.sql: ignored SQL file; remediation: add it to the manifest"),
      errors = List("views/003_malformed.sql: malformed SQL near SELECT")
    )

    val (invalid, warnings, status) = ValidationStore.findingsAndStatus(report)

    assertEquals(status, "errors")
    assertEquals(invalid.map(_.severity), List("error"))
    assertEquals(invalid.map(_.name), List("views/003_malformed.sql"))
    assertEquals(warnings.map(_.severity), List("warning"))
    assertEquals(warnings.map(_.name), List("scratch/002_ignored_view.sql"))
  }

  test("allows execution when validation has discovery warnings but no malformed SQL errors") {
    val report = ValidationReport(
      warnings = List("scratch/002_ignored_view.sql: ignored SQL file; remediation: add it to the manifest")
    )

    val (invalid, warnings, status) = ValidationStore.findingsAndStatus(report)

    assertEquals(status, "warnings")
    assertEquals(invalid, Nil)
    assertEquals(warnings.length, 1)
  }
