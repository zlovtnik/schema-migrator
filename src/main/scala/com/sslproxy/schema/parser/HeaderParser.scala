package com.sslproxy.schema.parser

object HeaderParser:
  def value(sql: String, key: String): Option[String] =
    val prefix = s"-- ${key.toLowerCase}:"
    sql.linesIterator
      .map(_.trim)
      .takeWhile(line => line.isEmpty || line.startsWith("--"))
      .find(_.toLowerCase.startsWith(prefix))
      .map(_.drop(prefix.length).trim)
      .filter(_.nonEmpty)

  def dependsOn(value: String): List[String] =
    value
      .split(',')
      .iterator
      .map(_.trim)
      .filter(item => item.nonEmpty && item != "-")
      .toList

  def transactional(sql: String, requiresNonTransactional: Boolean): Either[String, Boolean] =
    value(sql, "transactional") match
      case None => Right(!requiresNonTransactional)
      case Some(raw) =>
        raw.toLowerCase match
          case "true" | "yes" | "on" | "1" =>
            if requiresNonTransactional then
              Left("-- transactional: true conflicts with SQL that must run outside a transaction")
            else Right(true)
          case "false" | "no" | "off" | "0" => Right(false)
          case other => Left(s"invalid -- transactional value '$other', expected true or false")

  def oracleHeaders(sql: String): Map[String, String] =
    List("oracle_tablespace", "oracle_partitioning", "oracle_pdb")
      .flatMap(key => value(sql, key).map(key -> _))
      .toMap

