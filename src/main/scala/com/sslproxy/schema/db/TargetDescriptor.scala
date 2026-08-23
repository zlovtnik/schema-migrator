package com.sslproxy.schema.db

import com.sslproxy.schema.config.DbKind

import java.net.URI
import scala.util.Try

final case class TargetDescriptor(dbKind: DbKind, jdbcUrl: String, host: Option[String])

object TargetDescriptor:
  def parse(rawUrl: String): Either[String, TargetDescriptor] =
    val jdbcUrl = rawUrl.trim
    val oraclePrefix = "jdbc:oracle:thin:"
    val unsupported = "unsupported database URL for migration execution"
    val kind =
      if jdbcUrl.startsWith(oraclePrefix) && jdbcUrl.stripPrefix(oraclePrefix).trim.nonEmpty then Right(DbKind.Oracle)
      else if jdbcUrl.startsWith("jdbc:postgresql:") || jdbcUrl.startsWith("postgres://") || jdbcUrl.startsWith(
          "postgresql://"
        )
      then Right(DbKind.Postgres)
      else Left(unsupported)
    kind.map(dbKind => TargetDescriptor(dbKind, jdbcUrl, hostFor(dbKind, jdbcUrl)))

  private def hostFor(dbKind: DbKind, jdbcUrl: String): Option[String] =
    dbKind match
      case DbKind.Postgres =>
        val uri =
          if jdbcUrl.startsWith("jdbc:postgresql://") then jdbcUrl.stripPrefix("jdbc:")
          else jdbcUrl
        uriHost(uri)
      case DbKind.Oracle =>
        if jdbcUrl.startsWith("jdbc:oracle:thin:@//") then
          uriHost("oracle:" + jdbcUrl.stripPrefix("jdbc:oracle:thin:@"))
        else descriptorHost(jdbcUrl)

  private def uriHost(value: String): Option[String] =
    Try(URI.create(value).getHost).toOption.flatMap(Option(_)).filter(_.nonEmpty)

  private def descriptorHost(value: String): Option[String] =
    raw"(?i)\bhost\s*=\s*([^)]+)".r
      .findAllMatchIn(value)
      .map(_.group(1).trim)
      .filter(_.nonEmpty)
      .toList match
      case host :: Nil => Some(host)
      case _ => None
