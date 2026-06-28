package com.sslproxy.schema.validation

import cats.effect.Sync
import cats.syntax.all.*
import com.sslproxy.schema.config.DbKind
import com.sslproxy.schema.discovery.SqlFile
import com.sslproxy.schema.parser.{BalanceChecker, HeaderParser}

import java.nio.file.Files

final class Validator[F[_]: Sync](dbKind: DbKind):
  private val tableColumnWarningLimit = 15

  def validate(files: List[SqlFile]): F[ValidationReport] =
    Sync[F].blocking {
      val fileReport = files.foldLeft(ValidationReport()) { (report, file) =>
        validateOne(report, file)
      }
      val graphErrors = DependencyValidator.validate(files)
      val rollbackErrors = RollbackValidator.validate(files)
      fileReport.copy(errors = fileReport.errors ++ graphErrors ++ rollbackErrors)
    }

  private def validateOne(report: ValidationReport, file: SqlFile): ValidationReport =
    val sql =
      try Files.readString(file.path)
      catch
        case error: Exception =>
          return report.addError(s"${file.relativePath}: unreadable SQL file (${error.getMessage})")

    if sql.trim.isEmpty then return report.addError(s"${file.relativePath}: SQL file is empty")

    BalanceChecker.check(sql) match
      case Left(error) =>
        report.addError(s"${file.relativePath}: SQL parsing check failed ($error)")
      case Right(()) if !hasRequiredHeader(sql, file) =>
        report.addError(s"${file.relativePath}: missing required header comments (-- object, -- folder, -- depends_on)")
      case Right(()) =>
        applyFolderHeuristics(report, file, sql)

  private def hasRequiredHeader(sql: String, file: SqlFile): Boolean =
    file.folder == "baseline" || (
      HeaderParser.value(sql, "object").nonEmpty &&
        HeaderParser.value(sql, "folder").nonEmpty &&
        HeaderParser.value(sql, "depends_on").nonEmpty
    )

  private def applyFolderHeuristics(report: ValidationReport, file: SqlFile, sql: String): ValidationReport =
    dbKind match
      case DbKind.Postgres => postgresHeuristics(report, file, sql)
      case DbKind.Oracle => oracleHeuristics(report, file, sql)

  private def postgresHeuristics(report: ValidationReport, file: SqlFile, sql: String): ValidationReport =
    val lower = sql.toLowerCase
    val path = file.relativePath
    file.folder match
      case "tables" =>
        val withTableWarning =
          if !lower.contains("create table if not exists") then
            report.addWarning(s"$path: expected 'CREATE TABLE IF NOT EXISTS' for idempotency")
          else report
        createTableColumnCount(sql) match
          case Some(count) if count > tableColumnWarningLimit =>
            withTableWarning.addWarning(
              s"$path: table has $count columns; prefer <= $tableColumnWarningLimit columns and vertical partitioning for hot-path schemas"
            )
          case _ => withTableWarning
      case "functions"
          if !lower.contains("create or replace function") && !lower.contains("create or replace procedure") =>
        report.addWarning(
          s"$path: expected 'CREATE OR REPLACE FUNCTION' or 'CREATE OR REPLACE PROCEDURE' for idempotency"
        )
      case "views" =>
        val replace = lower.contains("create or replace view")
        val dropCreate = lower.contains("drop view if exists") && lower.contains("create view")
        if replace || dropCreate then report
        else report.addWarning(s"$path: expected 'CREATE OR REPLACE VIEW' or 'DROP VIEW IF EXISTS' + 'CREATE VIEW'")
      case "indexes" =>
        val hasIndex =
          lower.contains("create index if not exists") || lower.contains("create unique index if not exists")
        if hasIndex then report
        else report.addWarning(s"$path: expected 'CREATE INDEX IF NOT EXISTS' or 'CREATE UNIQUE INDEX IF NOT EXISTS'")
      case "extensions" if !lower.contains("create extension if not exists") =>
        report.addWarning(s"$path: expected 'CREATE EXTENSION IF NOT EXISTS'")
      case _ => report

  private def oracleHeuristics(report: ValidationReport, file: SqlFile, sql: String): ValidationReport =
    val lower = sql.toLowerCase
    val path = file.relativePath
    file.folder match
      case "tables" if lower.contains("create table") && !lower.contains("sqlcode = -955") =>
        report.addWarning(s"$path: Oracle CREATE TABLE should be wrapped with ORA-00955 idempotency handling")
      case "indexes" if lower.contains("create index") && !lower.contains("sqlcode = -955") =>
        report.addWarning(s"$path: Oracle CREATE INDEX should be wrapped with ORA-00955 idempotency handling")
      case "functions"
          if !lower.contains("create or replace function") && !lower.contains("create or replace procedure") =>
        report.addWarning(
          s"$path: expected 'CREATE OR REPLACE FUNCTION' or 'CREATE OR REPLACE PROCEDURE' for idempotency"
        )
      case "procedures" if !lower.contains("create or replace procedure") =>
        report.addWarning(s"$path: expected 'CREATE OR REPLACE PROCEDURE' for idempotency")
      case "packages" if !lower.contains("create or replace package") =>
        report.addWarning(s"$path: expected 'CREATE OR REPLACE PACKAGE' for idempotency")
      case "views" if !lower.contains("create or replace view") =>
        report.addWarning(s"$path: expected 'CREATE OR REPLACE VIEW' for idempotency")
      case _ => report

  private def createTableColumnCount(sql: String): Option[Int] =
    val lower = sql.toLowerCase
    val createPos = lower.indexOf("create table")
    if createPos < 0 then None
    else
      val open = sql.indexOf('(', createPos)
      if open < 0 then None
      else
        matchingCloseParen(sql, open).map { close =>
          splitTopLevelCommas(sql.substring(open + 1, close)).count(isColumnDefinition)
        }

  private def matchingCloseParen(sql: String, open: Int): Option[Int] =
    var index = open
    var depth = 0
    var inSingle = false
    var inDouble = false
    while index < sql.length do
      val current = sql.charAt(index)
      val next = if index + 1 < sql.length then sql.charAt(index + 1) else 0.toChar
      if inSingle then
        if current == '\'' && next == '\'' then index += 2
        else
          if current == '\'' then inSingle = false
          index += 1
      else if inDouble then
        if current == '"' then inDouble = false
        index += 1
      else
        current match
          case '\'' => inSingle = true
          case '"' => inDouble = true
          case '(' => depth += 1
          case ')' =>
            depth -= 1
            if depth == 0 then return Some(index)
          case _ => ()
        index += 1
    None

  private def splitTopLevelCommas(body: String): List[String] =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    var start = 0
    var index = 0
    var depth = 0
    var inSingle = false
    var inDouble = false
    while index < body.length do
      val current = body.charAt(index)
      val next = if index + 1 < body.length then body.charAt(index + 1) else 0.toChar
      if inSingle then
        if current == '\'' && next == '\'' then index += 2
        else
          if current == '\'' then inSingle = false
          index += 1
      else if inDouble then
        if current == '"' then inDouble = false
        index += 1
      else
        current match
          case '\'' => inSingle = true
          case '"' => inDouble = true
          case '(' => depth += 1
          case ')' => depth = math.max(0, depth - 1)
          case ',' if depth == 0 =>
            parts.append(body.substring(start, index).trim)
            start = index + 1
          case _ => ()
        index += 1
    val tail = body.substring(start).trim
    if tail.nonEmpty then parts.append(tail)
    parts.toList

  private def isColumnDefinition(part: String): Boolean =
    val first = part.split("\\s+").headOption.getOrElse("").stripPrefix("\"").stripSuffix("\"").toLowerCase
    !Set("", "constraint", "primary", "foreign", "unique", "check", "exclude").contains(first)
