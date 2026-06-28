package com.sslproxy.schema.db.oracle

import java.sql.Connection

object OracleDdlHelper:
  def executeSql(connection: Connection, sql: String): Unit =
    splitExecutableBlocks(sql).foreach { statementSql =>
      val statement = connection.createStatement()
      try
        val stripped = stripTrailingSemicolon(statementSql)
        statement.execute(stripped)
      catch
        case e: java.sql.SQLException =>
          throw new java.sql.SQLException(
            s"OracleDdlHelper block failed: ${e.getMessage}",
            e.getSQLState,
            e.getErrorCode,
            e
          )
      finally statement.close()
    }

  def splitExecutableBlocks(sql: String): List[String] =
    val blocks = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    sql.linesIterator.foreach { line =>
      if line.trim == "/" then appendCurrent(blocks, current)
      else current.append(line).append('\n')
    }
    appendCurrent(blocks, current)
    blocks.toList

  private def appendCurrent(blocks: scala.collection.mutable.ListBuffer[String], current: StringBuilder): Unit =
    val text = current.toString.trim
    if text.nonEmpty then blocks.append(text)
    current.clear()

  private def stripTrailingSemicolon(sql: String): String =
    val trimmed = sql.trim
    val lower = trimmed.toLowerCase
    if lower.startsWith("begin") || lower.startsWith("declare") then trimmed
    else if trimmed.endsWith(";") then
      val withoutSemi = trimmed.dropRight(1).trim
      val withoutSemiLower = withoutSemi.toLowerCase
      if withoutSemiLower.startsWith("create or replace") ||
        withoutSemiLower.startsWith("create") ||
        withoutSemiLower.startsWith("replace")
      then trimmed
      else withoutSemi
    else trimmed
