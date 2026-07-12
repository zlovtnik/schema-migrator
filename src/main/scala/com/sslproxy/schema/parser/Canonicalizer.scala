package com.sslproxy.schema.parser

import com.sslproxy.schema.db.syntax.SqlDialect

import java.security.MessageDigest

object Canonicalizer:
  def canonicalize(sql: String, dialect: SqlDialect): String =
    canonicalizeInner(stripDialectTerminators(sql, dialect), dialect).trim

  def sha256Hex(value: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"))
    digest.map(byte => f"${byte & 0xff}%02x").mkString

  def requiresNonTransactionalApply(sql: String, dialect: SqlDialect): Boolean =
    val canonical = canonicalize(sql, dialect).toLowerCase(java.util.Locale.ROOT)
    canonical.contains("create index concurrently") ||
    canonical.contains("drop index concurrently") ||
    (canonical.contains("reindex") && canonical.contains(" concurrently"))

  private def stripDialectTerminators(sql: String, dialect: SqlDialect): String =
    dialect match
      case SqlDialect.Postgres => sql
      case SqlDialect.Oracle =>
        sql.linesIterator
          .filterNot(line => line.trim == "/")
          .mkString("\n")

  private def canonicalizeInner(sql: String, dialect: SqlDialect): String =
    val output = new StringBuilder(sql.length)
    var index = 0
    var pendingSpace = false

    def pushPendingSpace(): Unit =
      if pendingSpace && output.nonEmpty && !output.endsWith(" ") then output.append(' ')
      pendingSpace = false

    while index < sql.length do
      val current = sql.charAt(index)
      val next = if index + 1 < sql.length then Some(sql.charAt(index + 1)) else None

      if current == '-' && next.contains('-') then
        index += 2
        while index < sql.length && sql.charAt(index) != '\n' do index += 1
        pendingSpace = true
      else if current == '/' && next.contains('*') then
        if dialect == SqlDialect.Oracle && index + 2 < sql.length && sql.charAt(index + 2) == '+' then
          pushPendingSpace()
          val end = sql.indexOf("*/", index + 2)
          val stop = if end >= 0 then end + 2 else sql.length
          output.append(sql.substring(index, stop).trim.replaceAll("\\s+", " "))
          index = stop
        else
          index += 2
          while index + 1 < sql.length && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') do index += 1
          index = math.min(index + 2, sql.length)
          pendingSpace = true
      else if current == '\'' then
        pushPendingSpace()
        index = copySingleQuoted(sql, index, output)
      else if current == '"' then
        pushPendingSpace()
        index = copyDoubleQuoted(sql, index, output)
      else if current == '$' then
        parseDollarTag(sql, index) match
          case Some(tag) =>
            val bodyStart = index + tag.length
            val end = sql.indexOf(tag, bodyStart)
            if end >= 0 then
              pushPendingSpace()
              output.append("$$")
              output.append(sql.substring(bodyStart, end))
              output.append("$$")
              index = end + tag.length
            else
              output.append(current)
              index += 1
          case None =>
            output.append(current)
            index += 1
      else if current.isWhitespace then
        pendingSpace = true
        index += 1
      else
        pushPendingSpace()
        output.append(current)
        index += 1

    output.toString.trim

  private def copySingleQuoted(sql: String, start: Int, output: StringBuilder): Int =
    val escapeBackslash = start > 0 && Set('e', 'E').contains(sql.charAt(start - 1))
    output.append('\'')
    var index = start + 1
    var done = false
    while index < sql.length && !done do
      val current = sql.charAt(index)
      output.append(current)
      index += 1

      if escapeBackslash && current == '\\' && index < sql.length then
        output.append(sql.charAt(index))
        index += 1
      else if current == '\'' then
        if index < sql.length && sql.charAt(index) == '\'' then
          output.append('\'')
          index += 1
        else done = true
    index

  private def copyDoubleQuoted(sql: String, start: Int, output: StringBuilder): Int =
    output.append('"')
    var index = start + 1
    var done = false
    while index < sql.length && !done do
      val current = sql.charAt(index)
      output.append(current)
      index += 1
      if current == '"' then done = true
    index

  private def parseDollarTag(sql: String, start: Int): Option[String] =
    if start >= sql.length || sql.charAt(start) != '$' then None
    else
      var index = start + 1
      var done: Option[String] = None
      while index < sql.length && done.isEmpty do
        val current = sql.charAt(index)
        if current == '$' then done = Some(sql.substring(start, index + 1))
        else if !current.isLetterOrDigit && current != '_' then return None
        index += 1
      done
