package com.sslproxy.schema.error

import munit.FunSuite

class MigratorErrorSuite extends FunSuite:
  test("isConnectionFailure identifies Connection and rejects other variants") {
    assert(MigratorError.isConnectionFailure(MigratorError.Connection("boom")))
    assert(!MigratorError.isConnectionFailure(MigratorError.Apply("boom")))
    assert(!MigratorError.isConnectionFailure(MigratorError.NonRetryableApply("boom")))
    assert(!MigratorError.isConnectionFailure(MigratorError.LockNotHeld("boom")))
    assert(!MigratorError.isConnectionFailure(MigratorError.Validation("boom")))
    assert(!MigratorError.isConnectionFailure(new RuntimeException("other")))
  }

  test("isNonRetryableApply identifies NonRetryableApply and rejects other variants") {
    assert(MigratorError.isNonRetryableApply(MigratorError.NonRetryableApply("boom")))
    assert(!MigratorError.isNonRetryableApply(MigratorError.Connection("boom")))
    assert(!MigratorError.isNonRetryableApply(MigratorError.Apply("boom")))
    assert(!MigratorError.isNonRetryableApply(MigratorError.LockNotHeld("boom")))
    assert(!MigratorError.isNonRetryableApply(MigratorError.Validation("boom")))
    assert(!MigratorError.isNonRetryableApply(new RuntimeException("other")))
  }

  test("isValidation identifies Validation and rejects other variants") {
    assert(MigratorError.isValidation(MigratorError.Validation("boom")))
    assert(!MigratorError.isValidation(MigratorError.Connection("boom")))
    assert(!MigratorError.isValidation(MigratorError.Apply("boom")))
    assert(!MigratorError.isValidation(MigratorError.NonRetryableApply("boom")))
    assert(!MigratorError.isValidation(MigratorError.LockNotHeld("boom")))
    assert(!MigratorError.isValidation(new RuntimeException("other")))
  }

  test("LockNotHeld is not classified as a connection failure") {
    val error: MigratorError = MigratorError.LockNotHeld("lock lost")
    assertEquals(error.getMessage, "lock lost")
    assert(!MigratorError.isConnectionFailure(error))
  }

  test("case classes preserve message and optional cause") {
    val cause = new RuntimeException("root cause")
    val connection = MigratorError.Connection("msg", cause)
    assertEquals(connection.getMessage, "msg")
    assertEquals(connection.getCause, cause)

    val apply = MigratorError.Apply("a")
    assertEquals(apply.getMessage, "a")
    assert(apply.getCause == null)

    val nonRetryable = MigratorError.NonRetryableApply("nr")
    assertEquals(nonRetryable.getMessage, "nr")

    val lock = MigratorError.LockNotHeld("lk")
    assertEquals(lock.getMessage, "lk")

    val validation = MigratorError.Validation("v", cause)
    assertEquals(validation.getMessage, "v")
    assertEquals(validation.getCause, cause)
  }
