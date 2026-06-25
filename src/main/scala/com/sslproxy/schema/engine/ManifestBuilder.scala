package com.sslproxy.schema.engine

import cats.effect.Sync
import cats.syntax.all.*
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.SqlFile
import com.sslproxy.schema.error.MigratorError
import com.sslproxy.schema.parser.{Canonicalizer, HeaderParser}

import java.nio.file.Files

final class ManifestBuilder[F[_]: Sync](dialect: SqlDialect):
  def build(files: List[SqlFile]): F[List[SchemaObject]] =
    files.traverse(file => Sync[F].blocking(fromFile(file)))

  private def fromFile(file: SqlFile): SchemaObject =
    val rawSql = Files.readString(file.path)
    val objectName = HeaderParser.value(rawSql, "object").getOrElse {
      if file.folder == "baseline" then "oracle_baseline"
      else throw MigratorError.Apply(s"${file.relativePath}: missing required '-- object:' header")
    }
    val dependsOn = HeaderParser.value(rawSql, "depends_on").map(HeaderParser.dependsOn).getOrElse(Nil)
    val rollbackFile = HeaderParser.value(rawSql, "rollback")
    val canonicalSql = Canonicalizer.canonicalize(rawSql, dialect)
    val transactional = HeaderParser
      .transactional(rawSql, Canonicalizer.requiresNonTransactionalApply(rawSql, dialect))
      .fold(message => throw MigratorError.Apply(s"${file.relativePath}: $message"), identity)
    val sha256 = Canonicalizer.sha256Hex(canonicalSql)

    SchemaObject(
      kind = kindForFolder(file.folder, file.name),
      objectName = objectName,
      sourceFile = file.relativePath,
      dependsOn = dependsOn,
      rollbackFile = rollbackFile,
      transactional = transactional,
      rawSql = rawSql,
      canonicalSql = canonicalSql,
      sha256 = sha256,
      oracleHeaders = HeaderParser.oracleHeaders(rawSql)
    )

  private def kindForFolder(folder: String, name: String): String =
    folder match
      case "extensions"                       => "extension"
      case "schemas"                          => "schema"
      case "types"                            => "type"
      case "tables"                           => "table"
      case "indexes"                          => "index"
      case "functions"                        => "function"
      case "procedures"                       => "procedure"
      case "packages"                         => "package"
      case "triggers"                         => "trigger"
      case "views"                            => "view"
      case "materialized_views"               => "materialized_view"
      case "cron" if name.startsWith("000_")  => "pre_apply_hook"
      case "cron"                             => "cron_job"
      case "policies"                         => "policy"
      case "baseline"                         => "baseline"
      case _                                  => "sql_file"

