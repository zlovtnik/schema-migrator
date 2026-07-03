package com.sslproxy.schema.validation

import com.sslproxy.schema.discovery.SqlFile
import com.sslproxy.schema.parser.HeaderParser

import java.nio.file.{Files, Path}

object RollbackValidator:
  private val ErrorPrefix = "__ROLLBACK_VALIDATION_ERROR__"

  def validate(files: List[SqlFile]): List[String] =
    val results = scala.collection.mutable.ListBuffer.empty[String]
    files.foreach { file =>
      var sql: String = null
      try sql = file.readString
      catch
        case error: Exception =>
          results += s"${file.relativePath}: unreadable SQL file (${error.getMessage})"

      if sql != null then
        HeaderParser.value(sql, "rollback").toList.foreach { rollback =>
          resolveExistingRollbackPath(file, rollback) match
            case None => results += s"${file.relativePath}: rollback file '$rollback' does not exist"
            case Some(path) =>
              var rollbackSql: String = null
              try rollbackSql = Files.readString(path)
              catch
                case error: Exception =>
                  results += s"${file.relativePath}: rollback file '$path' is unreadable (${error.getMessage})"
              if rollbackSql != null && rollbackSql.trim.isEmpty then
                results += s"${file.relativePath}: rollback file '$path' is empty"
        }
    }
    results.toList

  def resolveExistingRollbackPath(file: SqlFile, rollback: String): Option[Path] =
    try candidates(file, rollback).find(Files.exists(_))
    catch case error: Exception => None

  def candidates(file: SqlFile, rollback: String): List[Path] =
    val reference = Path.of(rollback)
    if reference.isAbsolute then List(reference)
    else
      val sqlDirCandidates =
        Option(file.path.getParent)
          .flatMap(parent => Option(parent.getParent))
          .toList
          .flatMap { sqlDir =>
            val stripped =
              if reference.getNameCount > 0 && reference.getName(0).toString == "sql" then
                List(sqlDir.resolve(reference.subpath(1, reference.getNameCount)))
              else Nil
            val repoRelative = Option(sqlDir.getParent).toList.map(_.resolve(reference))
            List(sqlDir.resolve(reference)) ::: stripped ::: repoRelative
          }
      val fileRelative = Option(file.path.getParent).toList.map(_.resolve(reference))
      (sqlDirCandidates ::: fileRelative).distinct
