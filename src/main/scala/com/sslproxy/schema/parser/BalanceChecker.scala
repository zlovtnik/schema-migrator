package com.sslproxy.schema.parser

object BalanceChecker:
  def check(sql: String): Either[String, Unit] =
    var index = 0
    var inSingle = false
    var inDouble = false
    var inLineComment = false
    var inBlockComment = false
    var dollarTag: Option[String] = None
    var inEString = false

    while index < sql.length do
      val current = sql.charAt(index)
      val next = if index + 1 < sql.length then sql.charAt(index + 1) else 0.toChar

      if inLineComment then
        if current == '\n' then inLineComment = false
        index += 1
      else if inBlockComment then
        if current == '*' && next == '/' then
          inBlockComment = false
          index += 2
        else index += 1
      else
        dollarTag match
          case Some(tag) if sql.startsWith(tag, index) =>
            index += tag.length
            dollarTag = None
          case Some(_) =>
            index += 1
          case None if inSingle =>
            if inEString && current == '\\' && next != 0.toChar then index += 2
            else if current == '\'' && next == '\'' then index += 2
            else
              if current == '\'' then
                inSingle = false
                inEString = false
              index += 1
          case None if inDouble =>
            if current == '"' then inDouble = false
            index += 1
          case None =>
            if current == '-' && next == '-' then
              inLineComment = true
              index += 2
            else if current == '/' && next == '*' then
              inBlockComment = true
              index += 2
            else if current == '\'' then
              inSingle = true
              inEString = index > 0 && Set('e', 'E').contains(sql.charAt(index - 1))
              index += 1
            else if current == '"' then
              inDouble = true
              index += 1
            else if current == '$' then
              parseDollarTag(sql, index) match
                case Some(tag) =>
                  dollarTag = Some(tag)
                  index += tag.length
                case None => index += 1
            else index += 1

    if inSingle then Left("unterminated single-quoted string")
    else if inDouble then Left("unterminated double-quoted identifier")
    else if inBlockComment then Left("unterminated block comment")
    else dollarTag match
      case Some(tag) => Left(s"unterminated dollar-quoted block with tag $tag")
      case None      => Right(())

  private def parseDollarTag(sql: String, start: Int): Option[String] =
    if start >= sql.length || sql.charAt(start) != '$' then None
    else
      var index = start + 1
      while index < sql.length do
        val current = sql.charAt(index)
        if current == '$' then return Some(sql.substring(start, index + 1))
        if !current.isLetterOrDigit && current != '_' then return None
        index += 1
      None