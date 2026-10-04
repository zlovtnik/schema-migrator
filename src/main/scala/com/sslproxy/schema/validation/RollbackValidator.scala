package com.sslproxy.schema.validation

import com.sslproxy.schema.discovery.SqlFile
import com.sslproxy.schema.parser.HeaderParser

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption.READ
import scala.jdk.CollectionConverters.*

object RollbackValidator:
  private[validation] val MaxRollbackBytes = 10 * 1024 * 1024

  def validate(files: List[SqlFile], repositoryFiles: List[SqlFile] = Nil): List[String] =
    val results = scala.collection.mutable.ListBuffer.empty[String]
    files.foreach { file =>
      var sql: String = null
      try sql = file.readString
      catch
        case error: Exception =>
          results += s"${file.relativePath}: unreadable SQL file (${error.getMessage})"

      if sql != null then
        HeaderParser.value(sql, "rollback").toList.foreach { rollback =>
          try
            val rollbackSql =
              if file.content.nonEmpty then
                val available = if repositoryFiles.nonEmpty then repositoryFiles else files
                val root = repositoryRoot(file)
                val targets = candidates(file, rollback, root)
                val matches = available.filter(other => targets.contains(other.path.toAbsolutePath.normalize()))
                val content = matches.headOption
                  .flatMap(_.content)
                  .getOrElse(throw IllegalArgumentException("rollback is not in the supplied repository files"))
                if content.getBytes(StandardCharsets.UTF_8).length > MaxRollbackBytes then
                  throw IllegalArgumentException("rollback exceeds the byte limit")
                content
              else readRollback(file, rollback, repositoryRoot(file))
            if rollbackSql.trim.isEmpty then results += s"${file.relativePath}: rollback file '$rollback' is empty"
          catch
            case error: Exception =>
              results += s"${file.relativePath}: invalid rollback file '$rollback' (${error.getMessage})"
        }
    }
    results.toList

  def resolveExistingRollbackPath(file: SqlFile, rollback: String, root: Path): Option[Path] =
    try
      val realRoot = root.toRealPath()
      candidates(file, rollback, root).find { path =>
        val relative = root.toAbsolutePath.normalize().relativize(path)
        val components = (0 until relative.getNameCount).map(index => root.resolve(relative.subpath(0, index + 1)))
        !components.exists(Files.isSymbolicLink(_)) &&
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().startsWith(realRoot)
      }
    catch case _: Exception => None

  def readRollback(file: SqlFile, rollback: String, root: Path): String =
    val path = resolveExistingRollbackPath(file, rollback, root)
      .getOrElse(throw IllegalArgumentException("rollback must be a regular file inside the SQL root"))
    scala.util.Using.resource(Files.newByteChannel(path, READ, LinkOption.NOFOLLOW_LINKS)) { channel =>
      val output = new java.io.ByteArrayOutputStream()
      val buffer = ByteBuffer.allocate(8192)
      var count = channel.read(buffer)
      while count >= 0 do
        if output.size().toLong + count > MaxRollbackBytes then
          throw IllegalArgumentException("rollback exceeds the byte limit")
        output.write(buffer.array(), 0, count)
        buffer.clear()
        count = channel.read(buffer)
      StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray)).toString
    }

  private def repositoryRoot(file: SqlFile): Path =
    val relative = Path.of(file.relativePath)
    val absolute = file.path.toAbsolutePath.normalize()
    if relative.isAbsolute || relative.iterator().asScala.exists(_.toString == "..") || !absolute.endsWith(relative)
    then throw IllegalArgumentException("invalid repository-relative SQL path")
    (0 until relative.getNameCount).foldLeft(absolute)((path, _) => path.getParent)

  private def candidates(file: SqlFile, rollback: String, root: Path): List[Path] =
    val reference = Path.of(rollback.replace('\\', '/'))
    val normalizedRoot = root.toAbsolutePath.normalize()
    if reference.isAbsolute || rollback.matches("^[A-Za-z]:.*") || rollback.startsWith("\\\\") then
      throw IllegalArgumentException("absolute rollback paths are not allowed")
    else
      val sqlDirCandidates =
        Option(file.path.getParent)
          .flatMap(parent => Option(parent.getParent))
          .toList
          .flatMap { sqlDir =>
            val stripped =
              if reference.getNameCount > 1 && reference.getName(0).toString == "sql" then
                List(sqlDir.resolve(reference.subpath(1, reference.getNameCount)))
              else Nil
            val repoRelative = Option(sqlDir.getParent).toList.map(_.resolve(reference))
            List(sqlDir.resolve(reference)) ::: stripped ::: repoRelative
          }
      val fileRelative = Option(file.path.getParent).toList.map(_.resolve(reference))
      val rootRelative = List(normalizedRoot.resolve(reference)) ++
        Option
          .when(reference.getNameCount > 1 && reference.getName(0).toString == "sql")(
            normalizedRoot.resolve(reference.subpath(1, reference.getNameCount))
          )
          .toList
      (sqlDirCandidates ::: fileRelative ::: rootRelative)
        .map(_.toAbsolutePath.normalize())
        .filter(_.startsWith(normalizedRoot))
        .distinct
