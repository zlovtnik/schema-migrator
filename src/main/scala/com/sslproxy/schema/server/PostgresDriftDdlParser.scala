package com.sslproxy.schema.server

import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.parser.Canonicalizer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import scala.collection.mutable.ListBuffer

private[schema] object PostgresDriftDdlParser:
  import PostgresDriftModel.*

  def normalizeObjectKey(key: ObjectKey): ObjectKey =
    if isRoutineType(key.objectType) then key.copy(name = normalizeRoutineObjectName(key.name))
    else key

  def definitionChanged(key: ObjectKey, expectedDdl: Option[String], actualDdl: Option[String]): Boolean =
    comparableDefinitionTypes.contains(key.objectType) &&
      expectedDdl.exists(expected => actualDdl.exists(actual => !definitionsEquivalent(key, expected, actual)))

  def routineDefinitions(sql: String): List[RoutineDefinition] =
    val tokens = tokenize(sql)
    tokens.indices.toList.flatMap { index =>
      if tokenValue(tokens, index) != "create" then None
      else
        val kindIndex =
          if tokenValue(tokens, index + 1) == "or" && tokenValue(tokens, index + 2) == "replace" then index + 3
          else index + 1
        tokenValue(tokens, kindIndex) match
          case "function" | "procedure" =>
            parseRoutineKey(sql, tokens, kindIndex + 1, tokenValue(tokens, kindIndex)).map { key =>
              val end = statementEnd(sql, tokens(index).start)
              RoutineDefinition(key, sql.substring(tokens(index).start, end).trim)
            }
          case _ => None
    }

  def catalogDefinitions(sql: String): List[DdlDefinition] =
    val tokens = tokenize(sql)
    tokens.indices.toList.flatMap(index => parseCreateDefinition(sql, tokens, index))

  def definitionHash(key: ObjectKey, value: String): String =
    sha256Hex(comparableDefinition(key, value))

  def hasCorruptCanonicalRoutineBody(key: ObjectKey, value: String): Boolean =
    val lowerValue = value.toLowerCase(Locale.ROOT)
    val hasCorruptPrefix = lowerValue.contains("as $$(-- object:")
    val hasCorruptParsedBody =
      routineDefinitions(value)
        .find(routine => normalizeObjectKey(routine.key) == normalizeObjectKey(key))
        .flatMap(routineBodyParts)
        .exists { case (_, body) =>
          val normalized = body.trim.stripPrefix("(").trim.toLowerCase(Locale.ROOT)
          normalized.startsWith("-- object:") &&
          normalized.contains("create or replace function")
        }
    isRoutineType(key.objectType) && (hasCorruptPrefix || hasCorruptParsedBody)

  private def definitionsEquivalent(key: ObjectKey, expected: String, actual: String): Boolean =
    val expectedComparable = comparableDefinition(key, expected)
    val actualComparable = comparableDefinition(key, actual)
    expectedComparable == actualComparable ||
      (isViewType(key.objectType) && starExpansionEquivalent(expectedComparable, actualComparable))

  private def comparableDefinition(key: ObjectKey, value: String): String =
    if isRoutineType(key.objectType) then routineComparableDdl(key, value).getOrElse(comparableDdl(key, value))
    else if isViewType(key.objectType) then viewComparableDdl(key, value)
    else if key.objectType == "index" then indexComparableDdl(key, value)
    else comparableDdl(key, value)

  private def indexComparableDdl(key: ObjectKey, value: String): String =
    compactSqlSurface(
      transformUnquotedSql(comparableDdl(key, value)) { text =>
        text
          .replaceAll("\\bcreate\\s+(unique\\s+)?index\\s+if\\s+not\\s+exists\\b", "create $1index")
          .replaceAll("\\s+using\\s+btree\\s+(?=\\()", " ")
      }
    )

  private def routineComparableDdl(key: ObjectKey, value: String): Option[String] =
    routineDefinitions(value)
      .find(routine => normalizeObjectKey(routine.key) == normalizeObjectKey(key))
      .flatMap(routineBodyParts)
      .map { case (header, body) =>
        val comparableHeader = routineHeaderComparable(key, header)
        val comparableBody = normalizeSqlTypeAliases(comparableDdl(key, body))
        s"$comparableHeader as $$\n$comparableBody\n$$"
      }

  private def routineHeaderComparable(key: ObjectKey, header: String): String =
    val normalizedKey = normalizeObjectKey(key)
    val comparableHeader = normalizeSqlTypeAliases(comparableDdl(key, header)).stripSuffix(" as").trim
    val suffix =
      comparableHeader.indexOf('(') match
        case -1 => comparableHeader
        case openParen =>
          matchingParen(comparableHeader, openParen)
            .map(closeParen => comparableHeader.substring(closeParen + 1).trim)
            .getOrElse(comparableHeader)
    s"${normalizedKey.objectType} ${normalizedKey.name} ${normalizeRoutineHeaderDefaults(suffix)}".trim

  private def normalizeRoutineHeaderDefaults(value: String): String =
    compactSqlSurface(
      transformUnquotedSql(value) { text =>
        text
          .replaceAll("(?i)\\breturns\\s+table\\s*\\(", "returns table(")
          .replaceAll("(?i)\\bcalled\\s+on\\s+null\\s+input\\b", " ")
          .replaceAll("(?i)\\bsecurity\\s+invoker\\b", " ")
          .replaceAll("(?i)\\bparallel\\s+unsafe\\b", " ")
          .replaceAll("(?i)\\bvolatile\\b", " ")
      }
    )

  private def routineBodyParts(routine: RoutineDefinition): Option[(String, String)] =
    val ddl = routine.ddl
    var index = 0
    while index < ddl.length do
      val current = ddl.charAt(index)
      if current == '\'' then index = skipSingleQuoted(ddl, index)
      else if current == '"' then index = skipDoubleQuoted(ddl, index)
      else if current == '$' then
        parseDollarTag(ddl, index) match
          case Some(tag) =>
            val bodyStart = index + tag.length
            val bodyEnd = ddl.indexOf(tag, bodyStart)
            if bodyEnd >= 0 then
              val header = ddl.substring(0, index)
              val body = ddl.substring(bodyStart, bodyEnd)
              return Some(header -> body)
            else return None
          case None => index += 1
      else index += 1
    None

  private def parseCreateDefinition(sql: String, tokens: List[Token], index: Int): Option[DdlDefinition] =
    if tokenValue(tokens, index) != "create" then None
    else
      val createStart = tokens(index).start
      val afterCreate = skipCreateModifiers(tokens, index + 1)
      tokenValue(tokens, afterCreate) match
        case "function" | "procedure" =>
          parseRoutineKey(sql, tokens, afterCreate + 1, tokenValue(tokens, afterCreate)).map { key =>
            DdlDefinition(key, sql.substring(createStart, statementEnd(sql, createStart)).trim)
          }
        case "schema" =>
          parseSchemaDefinition(sql, tokens, createStart, afterCreate + 1)
        case "extension" =>
          parseExtensionDefinition(sql, tokens, createStart, afterCreate + 1)
        case "table" =>
          parseNamedDefinition(sql, tokens, createStart, "table", skipIfNotExists(tokens, afterCreate + 1))
        case "view" =>
          parseNamedDefinition(sql, tokens, createStart, "view", afterCreate + 1)
        case "materialized" if tokenValue(tokens, afterCreate + 1) == "view" =>
          parseNamedDefinition(
            sql,
            tokens,
            createStart,
            "materialized_view",
            skipIfNotExists(tokens, afterCreate + 2)
          )
        case "type" | "domain" =>
          parseNamedDefinition(sql, tokens, createStart, "type", afterCreate + 1)
        case "sequence" =>
          parseNamedDefinition(sql, tokens, createStart, "sequence", skipIfNotExists(tokens, afterCreate + 1))
        case "trigger" =>
          parseTriggerDefinition(sql, tokens, createStart, afterCreate + 1)
        case "unique" if tokenValue(tokens, afterCreate + 1) == "index" =>
          parseIndexDefinition(sql, tokens, createStart, afterCreate + 2)
        case "index" =>
          parseIndexDefinition(sql, tokens, createStart, afterCreate + 1)
        case _ => None

  private def parseSchemaDefinition(
    sql: String,
    tokens: List[Token],
    createStart: Int,
    nameStart: Int
  ): Option[DdlDefinition] =
    val index = skipIfNotExists(tokens, nameStart)
    parseIdentifier(tokens, index).map { case (name, _) =>
      DdlDefinition(ObjectKey(name, name, "schema"), sql.substring(createStart, statementEnd(sql, createStart)).trim)
    }

  private def parseExtensionDefinition(
    sql: String,
    tokens: List[Token],
    createStart: Int,
    nameStart: Int
  ): Option[DdlDefinition] =
    parseIdentifier(tokens, skipIfNotExists(tokens, nameStart)).map { case (name, afterName) =>
      val statementEndOffset = statementEnd(sql, createStart)
      val schema = extensionSchema(tokens, afterName, statementEndOffset).getOrElse("public")
      DdlDefinition(
        ObjectKey(schema, name, "extension"),
        sql.substring(createStart, statementEnd(sql, createStart)).trim
      )
    }

  private def parseNamedDefinition(
    sql: String,
    tokens: List[Token],
    createStart: Int,
    objectType: String,
    nameStart: Int
  ): Option[DdlDefinition] =
    parseQualifiedName(tokens, nameStart).map { parsed =>
      DdlDefinition(
        ObjectKey(parsed.schema, parsed.name, objectType),
        sql.substring(createStart, statementEnd(sql, createStart)).trim
      )
    }

  private def parseIndexDefinition(
    sql: String,
    tokens: List[Token],
    createStart: Int,
    nameStart: Int
  ): Option[DdlDefinition] =
    val afterConcurrently =
      if tokenValue(tokens, nameStart) == "concurrently" then nameStart + 1 else nameStart
    parseNamedDefinition(sql, tokens, createStart, "index", skipIfNotExists(tokens, afterConcurrently))

  private def parseTriggerDefinition(
    sql: String,
    tokens: List[Token],
    createStart: Int,
    nameStart: Int
  ): Option[DdlDefinition] =
    parseIdentifier(tokens, nameStart).flatMap { case (triggerName, afterTriggerName) =>
      val statementEndOffset = statementEnd(sql, createStart)
      val tableStart =
        (afterTriggerName until tokens.length)
          .takeWhile(index => tokens(index).start < statementEndOffset)
          .find(index => tokenValue(tokens, index) == "on")
          .map(_ + 1)
      tableStart.flatMap(parseQualifiedName(tokens, _)).map { table =>
        DdlDefinition(
          ObjectKey(table.schema, s"${table.name}.$triggerName", "trigger"),
          sql.substring(createStart, statementEndOffset).trim
        )
      }
    }

  private def skipCreateModifiers(tokens: List[Token], start: Int): Int =
    var index = start
    if tokenValue(tokens, index) == "or" && tokenValue(tokens, index + 1) == "replace" then index += 2
    while Set("temporary", "temp", "unlogged").contains(tokenValue(tokens, index)) do index += 1
    index

  private def skipIfNotExists(tokens: List[Token], start: Int): Int =
    if tokenValue(tokens, start) == "if" && tokenValue(tokens, start + 1) == "not" && tokenValue(
        tokens,
        start + 2
      ) == "exists"
    then start + 3
    else start

  private def extensionSchema(tokens: List[Token], start: Int, statementEndOffset: Int): Option[String] =
    (start until tokens.length)
      .takeWhile(index => tokens(index).start < statementEndOffset)
      .collectFirst {
        case index if tokenValue(tokens, index) == "schema" =>
          parseIdentifier(tokens, index + 1).map(_._1)
      }
      .flatten

  private final case class ParsedName(schema: String, name: String)

  private def parseQualifiedName(tokens: List[Token], start: Int): Option[ParsedName] =
    parseIdentifier(tokens, start).flatMap { case (first, afterFirst) =>
      if tokenValue(tokens, afterFirst) == "." then
        parseIdentifier(tokens, afterFirst + 1).map { case (second, _) =>
          ParsedName(first, second)
        }
      else Some(ParsedName("public", first))
    }

  private def parseIdentifier(tokens: List[Token], start: Int): Option[(String, Int)] =
    tokens.lift(start).filter(token => isIdentifierToken(token.value)).map { token =>
      normalizeIdentifier(token.value) -> (start + 1)
    }

  private def comparableDdl(key: ObjectKey, value: String): String =
    val canonical = Canonicalizer
      .canonicalize(value, SqlDialect.Postgres)
      .stripSuffix(";")
      .trim
    val withoutDefaultPublic = if key.schema == "public" then stripDefaultPublicQualifier(canonical) else canonical
    compactSqlSurface(normalizeSqlTypeAliases(lowerUnquotedSql(withoutDefaultPublic)))

  private def viewComparableDdl(key: ObjectKey, value: String): String =
    compactViewExpression(stripViewCreateWrapper(comparableDdl(key, value)))

  private def stripViewCreateWrapper(value: String): String =
    val tokens = tokenize(value)
    val asIndex = tokens.indices.find(index => tokenValue(tokens, index) == "as")
    asIndex.map(index => value.substring(tokens(index).end).trim).getOrElse(value)

  private def compactViewExpression(value: String): String =
    compactSqlSurface(
      transformUnquotedSql(value) { text =>
        text
          .replaceAll("\\(([^()]+)\\)\\s+as\\s+", "$1 as ")
      }
    )

  private def starExpansionEquivalent(left: String, right: String): Boolean =
    (selectStarSource(left), selectStarSource(right)) match
      case (Some(leftSource), Some(rightSource)) => leftSource == rightSource
      case (Some(source), None) => selectSource(right).contains(source)
      case (None, Some(source)) => selectSource(left).contains(source)
      case _ => false

  private def selectStarSource(value: String): Option[String] =
    selectSource(value).filter(_ => value.matches("(?is)^select\\s+\\*\\s+from\\s+.+$"))

  private def selectSource(value: String): Option[String] =
    val normalized = value.trim.stripSuffix(";").trim
    val pattern = "(?is)^select\\s+.+\\s+from\\s+([a-z_][a-z0-9_$]*(?:\\.[a-z_][a-z0-9_$]*)?)\\s*$".r
    normalized match
      case pattern(source) => Some(source.stripPrefix("public."))
      case _ => None

  private def stripDefaultPublicQualifier(value: String): String =
    transformUnquotedSql(value) { text =>
      text.replaceAll("(?i)\\bpublic\\.", "")
    }

  private def lowerUnquotedSql(value: String): String =
    transformUnquotedSql(value)(_.toLowerCase(Locale.ROOT))

  private def normalizeSqlTypeAliases(value: String): String =
    transformUnquotedSql(value) { text =>
      text
        .replaceAll("(?i)\\btimestamp\\s+with\\s+time\\s+zone\\b", "timestamp with time zone")
        .replaceAll("(?i)\\btimestamp\\s+without\\s+time\\s+zone\\b", "timestamp without time zone")
        .replaceAll("(?i)\\btime\\s+with\\s+time\\s+zone\\b", "time with time zone")
        .replaceAll("(?i)\\btime\\s+without\\s+time\\s+zone\\b", "time without time zone")
        .replaceAll("(?i)\\bdouble\\s+precision\\b", "double precision")
        .replaceAll("(?i)\\bcharacter\\s+varying\\b", "character varying")
        .replaceAll("(?i)\\btimestamptz\\b", "timestamp with time zone")
        .replaceAll("(?i)\\btimetz\\b", "time with time zone")
        .replaceAll("(?i)\\bint8\\b", "bigint")
        .replaceAll("(?i)\\bint4\\b", "integer")
        .replaceAll("(?i)\\bint2\\b", "smallint")
        .replaceAll("(?i)\\bbool\\b", "boolean")
        .replaceAll("(?i)\\bfloat8\\b", "double precision")
        .replaceAll("(?i)\\bfloat4\\b", "real")
        .replaceAll("(?i)\\bvarchar\\b", "character varying")
        .replaceAll("(?i)\\bdecimal\\b", "numeric")
        .replaceAll("(?i)\\bint\\b", "integer")
    }

  private def compactSqlSurface(value: String): String =
    transformUnquotedSql(value) { text =>
      text
        .replaceAll("\\(\\s+", "(")
        .replaceAll("\\s+\\)", ")")
        .replaceAll("\\s*,\\s*", ", ")
        .replaceAll("\\s*::\\s*", "::")
        .replaceAll("\\s*\\.\\s*", ".")
        .replaceAll("\\s+", " ")
    }.trim

  private def transformUnquotedSql(value: String)(transform: String => String): String =
    val output = StringBuilder()
    var index = 0
    var segmentStart = 0

    def appendPlain(until: Int): Unit =
      if until > segmentStart then output.append(transform(value.substring(segmentStart, until)))

    while index < value.length do
      val current = value.charAt(index)
      if current == '\'' then
        appendPlain(index)
        val end = skipSingleQuoted(value, index)
        output.append(value.substring(index, end))
        index = end
        segmentStart = index
      else if current == '"' then
        appendPlain(index)
        val end = skipDoubleQuoted(value, index)
        output.append(value.substring(index, end))
        index = end
        segmentStart = index
      else if current == '$' then
        parseDollarTag(value, index) match
          case Some(tag) =>
            appendPlain(index)
            val bodyStart = index + tag.length
            val bodyEnd = value.indexOf(tag, bodyStart)
            val end = if bodyEnd >= 0 then bodyEnd + tag.length else value.length
            output.append(value.substring(index, end))
            index = end
            segmentStart = index
          case None => index += 1
      else index += 1

    appendPlain(value.length)
    output.toString

  private def parseRoutineKey(sql: String, tokens: List[Token], start: Int, objectType: String): Option[ObjectKey] =
    tokens.lift(start).filter(token => isIdentifierToken(token.value)).flatMap { first =>
      if tokens.lift(start + 1).exists(_.value == ".") then
        tokens
          .lift(start + 2)
          .filter(token => isIdentifierToken(token.value))
          .filter(_ => tokens.lift(start + 3).exists(_.value == "("))
          .flatMap { second =>
            routineSignature(sql, tokens(start + 3).start).map { signature =>
              ObjectKey(
                normalizeIdentifier(first.value),
                s"${normalizeIdentifier(second.value)}($signature)",
                objectType
              )
            }
          }
      else
        Option.when(tokens.lift(start + 1).exists(_.value == "("))(tokens(start + 1).start).flatMap { openParen =>
          routineSignature(sql, openParen).map { signature =>
            ObjectKey("public", s"${normalizeIdentifier(first.value)}($signature)", objectType)
          }
        }
    }

  private def routineSignature(sql: String, openParen: Int): Option[String] =
    matchingParen(sql, openParen).map { closeParen =>
      splitTopLevel(sql.substring(openParen + 1, closeParen), ',')
        .flatMap(identityParameterType)
        .mkString(", ")
    }

  private def matchingParen(value: String, openParen: Int): Option[Int] =
    var index = openParen
    var depth = 0
    while index < value.length do
      val current = value.charAt(index)
      val next = if index + 1 < value.length then value.charAt(index + 1) else 0.toChar
      if current == '-' && next == '-' then index = skipLineComment(value, index + 2)
      else if current == '/' && next == '*' then index = skipBlockComment(value, index + 2)
      else if current == '\'' then index = skipSingleQuoted(value, index)
      else if current == '"' then index = skipDoubleQuoted(value, index)
      else if current == '$' then
        parseDollarTag(value, index) match
          case Some(tag) =>
            val bodyStart = index + tag.length
            val bodyEnd = value.indexOf(tag, bodyStart)
            index = if bodyEnd >= 0 then bodyEnd + tag.length else value.length
          case None => index += 1
      else if current == '(' then
        depth += 1
        index += 1
      else if current == ')' then
        depth -= 1
        if depth == 0 then return Some(index)
        index += 1
      else index += 1
    None

  private def splitTopLevel(value: String, delimiter: Char): List[String] =
    val parts = ListBuffer.empty[String]
    var index = 0
    var start = 0
    var depth = 0
    while index < value.length do
      val current = value.charAt(index)
      val next = if index + 1 < value.length then value.charAt(index + 1) else 0.toChar
      if current == '-' && next == '-' then index = skipLineComment(value, index + 2)
      else if current == '/' && next == '*' then index = skipBlockComment(value, index + 2)
      else if current == '\'' then index = skipSingleQuoted(value, index)
      else if current == '"' then index = skipDoubleQuoted(value, index)
      else if current == '$' then
        parseDollarTag(value, index) match
          case Some(tag) =>
            val bodyStart = index + tag.length
            val bodyEnd = value.indexOf(tag, bodyStart)
            index = if bodyEnd >= 0 then bodyEnd + tag.length else value.length
          case None => index += 1
      else if current == '(' then
        depth += 1
        index += 1
      else if current == ')' then
        depth = (depth - 1).max(0)
        index += 1
      else if current == delimiter && depth == 0 then
        parts += value.substring(start, index)
        start = index + 1
        index += 1
      else index += 1
    parts += value.substring(start)
    parts.toList

  private def identityParameterType(parameter: String): Option[String] =
    val withoutDefault = stripParameterDefault(parameter).trim
    if withoutDefault.isEmpty then None
    else
      val (mode, afterMode) = takeLeadingWord(withoutDefault) match
        case Some((word, rest)) if parameterModes.contains(word.toLowerCase(Locale.ROOT)) =>
          word.toLowerCase(Locale.ROOT) -> rest.trim
        case _ => "" -> withoutDefault
      if mode == "out" || afterMode.isEmpty then None
      else Some(normalizeParameterType(parameterTypeOnly(afterMode)))

  private def normalizeRoutineObjectName(name: String): String =
    val openParen = name.indexOf('(')
    val closeParen = name.lastIndexOf(')')
    if openParen < 0 || closeParen < openParen then name.toLowerCase(Locale.ROOT)
    else
      val routineName = name.substring(0, openParen).toLowerCase(Locale.ROOT)
      val signature = name.substring(openParen + 1, closeParen)
      val suffix = name.substring(closeParen + 1)
      val normalizedSignature = splitTopLevel(signature, ',').map(normalizeParameterType).mkString(", ")
      s"$routineName($normalizedSignature)$suffix"

  private def normalizeParameterType(value: String): String =
    val lowered = normalizeSqlTypeAliases(value)
      .toLowerCase(Locale.ROOT)
      .replaceAll("\\s+", " ")
      .trim
    val arraySuffix = "[]"
    if lowered.endsWith(arraySuffix) then normalizeTypeName(lowered.stripSuffix(arraySuffix).trim) + arraySuffix
    else normalizeTypeName(lowered)

  private def normalizeTypeName(value: String): String =
    val compact = value.replaceAll("\\s+", " ").trim
    exactTypeAliases.getOrElse(compact, compact)

  private def stripParameterDefault(parameter: String): String =
    val withoutDefault = parameter.split("(?i)\\s+default\\s+", 2).headOption.getOrElse(parameter)
    splitTopLevel(withoutDefault, '=').headOption.getOrElse(withoutDefault)

  private def parameterTypeOnly(parameter: String): String =
    takeLeadingWord(parameter) match
      case Some((first, rest)) if rest.trim.nonEmpty && isParameterName(first) =>
        rest.trim
      case _ => parameter.trim

  private def isParameterName(firstWord: String): Boolean =
    val normalized = firstWord.stripPrefix("\"").stripSuffix("\"").toLowerCase(Locale.ROOT)
    firstWord.startsWith("\"") || !typeLeadingWords.contains(normalized)

  private def takeLeadingWord(value: String): Option[(String, String)] =
    val trimmed = value.trim
    if trimmed.isEmpty then None
    else if trimmed.startsWith("\"") then
      val end = skipDoubleQuoted(trimmed, 0)
      Some(trimmed.substring(0, end) -> trimmed.substring(end))
    else
      val end = trimmed.indexWhere(_.isWhitespace)
      if end < 0 then Some(trimmed -> "")
      else Some(trimmed.substring(0, end) -> trimmed.substring(end))

  private def tokenize(sql: String): List[Token] =
    val tokens = ListBuffer.empty[Token]
    var index = 0
    while index < sql.length do
      val current = sql.charAt(index)
      val next = if index + 1 < sql.length then sql.charAt(index + 1) else 0.toChar

      if current.isWhitespace then index += 1
      else if current == '-' && next == '-' then index = skipLineComment(sql, index + 2)
      else if current == '/' && next == '*' then index = skipBlockComment(sql, index + 2)
      else if current == '\'' then index = skipSingleQuoted(sql, index)
      else if current == '$' then
        parseDollarTag(sql, index) match
          case Some(tag) =>
            val bodyStart = index + tag.length
            val end = sql.indexOf(tag, bodyStart)
            index = if end >= 0 then end + tag.length else sql.length
          case None => index += 1
      else if current == '"' then
        val end = skipDoubleQuoted(sql, index)
        tokens += Token(sql.substring(index, end), index, end)
        index = end
      else if isIdentifierStart(current) then
        val start = index
        index += 1
        while index < sql.length && isIdentifierPart(sql.charAt(index)) do index += 1
        tokens += Token(sql.substring(start, index), start, index)
      else if current == '.' || current == '(' then
        tokens += Token(current.toString, index, index + 1)
        index += 1
      else index += 1
    tokens.toList

  private def statementEnd(sql: String, start: Int): Int =
    var index = start
    while index < sql.length do
      val current = sql.charAt(index)
      val next = if index + 1 < sql.length then sql.charAt(index + 1) else 0.toChar
      if current == '-' && next == '-' then index = skipLineComment(sql, index + 2)
      else if current == '/' && next == '*' then index = skipBlockComment(sql, index + 2)
      else if current == '\'' then index = skipSingleQuoted(sql, index)
      else if current == '"' then index = skipDoubleQuoted(sql, index)
      else if current == '$' then
        parseDollarTag(sql, index) match
          case Some(tag) =>
            val bodyStart = index + tag.length
            val end = sql.indexOf(tag, bodyStart)
            index = if end >= 0 then end + tag.length else sql.length
          case None => index += 1
      else if current == ';' then return index + 1
      else index += 1
    sql.length

  private def tokenValue(tokens: List[Token], index: Int): String =
    tokens.lift(index).map(_.value.toLowerCase(Locale.ROOT)).getOrElse("")

  private def isIdentifierToken(value: String): Boolean =
    value.startsWith("\"") || value.headOption.exists(isIdentifierStart)

  private def normalizeIdentifier(value: String): String =
    if value.startsWith("\"") && value.endsWith("\"") && value.length >= 2 then
      value.substring(1, value.length - 1).replace("\"\"", "\"")
    else value.toLowerCase(Locale.ROOT)

  private def isIdentifierStart(value: Char): Boolean =
    value.isLetter || value == '_'

  private def isIdentifierPart(value: Char): Boolean =
    value.isLetterOrDigit || value == '_' || value == '$'

  private def skipLineComment(sql: String, start: Int): Int =
    val end = sql.indexOf('\n', start)
    if end >= 0 then end + 1 else sql.length

  private def skipBlockComment(sql: String, start: Int): Int =
    val end = sql.indexOf("*/", start)
    if end >= 0 then end + 2 else sql.length

  private def skipSingleQuoted(sql: String, start: Int): Int =
    val escapeBackslash = start > 0 && Set('e', 'E').contains(sql.charAt(start - 1))
    var index = start + 1
    var done = false
    while index < sql.length && !done do
      val current = sql.charAt(index)
      index += 1
      if escapeBackslash && current == '\\' && index < sql.length then index += 1
      else if current == '\'' then
        if index < sql.length && sql.charAt(index) == '\'' then index += 1
        else done = true
    index

  private def skipDoubleQuoted(sql: String, start: Int): Int =
    var index = start + 1
    var done = false
    while index < sql.length && !done do
      val current = sql.charAt(index)
      index += 1
      if current == '"' then
        if index < sql.length && sql.charAt(index) == '"' then index += 1
        else done = true
    index

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

  private final case class Token(value: String, start: Int, end: Int)

  private def sha256Hex(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private val comparableDefinitionTypes: Set[String] =
    Set("function", "procedure", "trigger", "view", "materialized_view", "index")

  private def isRoutineType(objectType: String): Boolean =
    objectType == "function" || objectType == "procedure"

  private def isViewType(objectType: String): Boolean =
    objectType == "view" || objectType == "materialized_view"

  private val parameterModes: Set[String] =
    Set("in", "out", "inout", "variadic")

  private val exactTypeAliases: Map[String, String] =
    Map(
      "timestamptz" -> "timestamp with time zone",
      "timestamp" -> "timestamp without time zone",
      "timetz" -> "time with time zone",
      "time" -> "time without time zone",
      "int" -> "integer",
      "int4" -> "integer",
      "int2" -> "smallint",
      "int8" -> "bigint",
      "bool" -> "boolean",
      "float8" -> "double precision",
      "float4" -> "real",
      "varchar" -> "character varying",
      "decimal" -> "numeric"
    )

  private val typeLeadingWords: Set[String] =
    Set(
      "anyarray",
      "anycompatible",
      "anycompatiblearray",
      "anycompatiblemultirange",
      "anycompatiblenonarray",
      "anycompatiblerange",
      "anyelement",
      "anyenum",
      "anymultirange",
      "anynonarray",
      "anyrange",
      "bigint",
      "bigserial",
      "bit",
      "bool",
      "boolean",
      "box",
      "bytea",
      "char",
      "character",
      "cidr",
      "circle",
      "date",
      "decimal",
      "double",
      "inet",
      "int",
      "int2",
      "int4",
      "int8",
      "integer",
      "interval",
      "json",
      "jsonb",
      "line",
      "lseg",
      "macaddr",
      "macaddr8",
      "money",
      "name",
      "numeric",
      "oid",
      "path",
      "point",
      "polygon",
      "real",
      "record",
      "regclass",
      "regnamespace",
      "regoper",
      "regoperator",
      "regproc",
      "regprocedure",
      "regrole",
      "regtype",
      "serial",
      "smallint",
      "smallserial",
      "text",
      "time",
      "timestamp",
      "trigger",
      "tsquery",
      "tsvector",
      "uuid",
      "varchar",
      "void",
      "xml"
    )
