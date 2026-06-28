package com.sslproxy.schema.error

sealed abstract class MigratorError(message: String, cause: Throwable | Null = null) extends Exception(message, cause)

object MigratorError:
  final case class Connection(message: String, cause: Throwable | Null = null) extends MigratorError(message, cause)

  final case class Apply(message: String, cause: Throwable | Null = null) extends MigratorError(message, cause)

  final case class NonRetryableApply(message: String, cause: Throwable | Null = null)
      extends MigratorError(message, cause)

  final case class LockNotHeld(message: String, cause: Throwable | Null = null) extends MigratorError(message, cause)

  final case class Validation(message: String, cause: Throwable | Null = null) extends MigratorError(message, cause)

  def isConnectionFailure(error: Throwable): Boolean =
    error match
      case _: Connection => true
      case _ => false

  def isNonRetryableApply(error: Throwable): Boolean =
    error match
      case _: NonRetryableApply => true
      case _ => false

  def isValidation(error: Throwable): Boolean =
    error match
      case _: Validation => true
      case _ => false
