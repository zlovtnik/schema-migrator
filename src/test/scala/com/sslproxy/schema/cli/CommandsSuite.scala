package com.sslproxy.schema.cli

import cats.effect.ExitCode
import com.sslproxy.schema.TestSqlSupport
import munit.FunSuite

class CommandsSuite extends FunSuite with TestSqlSupport:
  test("validate does not require database connection settings") {
    val exitCode = withSqlDir { sqlDir =>
      Commands.run(config(sqlDir), CliCommand.Validate)
    }

    assertEquals(exitCode, ExitCode.Success)
  }

  test("dry-run apply does not require database connection settings") {
    val exitCode = withSqlDir { sqlDir =>
      Commands.run(config(sqlDir).copy(dryRun = true), CliCommand.Apply)
    }

    assertEquals(exitCode, ExitCode.Success)
  }
