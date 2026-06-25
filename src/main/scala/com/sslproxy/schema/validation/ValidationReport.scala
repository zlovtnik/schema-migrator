package com.sslproxy.schema.validation

final case class ValidationReport(warnings: List[String] = Nil, errors: List[String] = Nil):
  def hasErrors: Boolean = errors.nonEmpty
  def addWarning(value: String): ValidationReport = copy(warnings = warnings :+ value)
  def addError(value: String): ValidationReport = copy(errors = errors :+ value)
  def combine(other: ValidationReport): ValidationReport =
    ValidationReport(warnings ++ other.warnings, errors ++ other.errors)

