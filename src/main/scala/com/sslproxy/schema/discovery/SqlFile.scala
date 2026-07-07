package com.sslproxy.schema.discovery

import com.sslproxy.schema.config.DbKind

import java.nio.file.{Files, Path}

final case class SqlFile(
  folder: String,
  path: Path,
  name: String,
  relativePath: String,
  content: Option[String] = None
):
  def readString: String =
    content.getOrElse(Files.readString(path))

final case class DiscoveryResult(files: List[SqlFile], warnings: List[String])

object SqlPathNormalizer:
  final case class NormalizedPath(path: String, folder: String, filename: String)

  def normalizeUploadPath(path: String): NormalizedPath =
    toNormalizedPath(stripSqlRoot(pathParts(path)))

  def normalizeForDb(file: SqlFile, dbKind: DbKind): Either[String, Option[SqlFile]] =
    val normalized = normalizeUploadPath(discoveryPath(file))
    dbKind match
      case DbKind.Postgres =>
        normalizeForEngine(file, normalized, DbKind.Postgres)

      case DbKind.Oracle =>
        normalizeForEngine(file, normalized, DbKind.Oracle)

  private def normalizeForEngine(
    file: SqlFile,
    normalized: NormalizedPath,
    dbKind: DbKind
  ): Either[String, Option[SqlFile]] =
    val engine = SqlLayout.engineName(dbKind)
    val otherEngines = List("postgres", "oracle").filterNot(_ == engine)
    if otherEngines.exists(other => normalized.folder == other || normalized.folder.startsWith(s"$other/")) then
      Right(None)
    else if normalized.folder == "baseline" then
      dbKind match
        case DbKind.Oracle => Right(Some(withNormalizedPath(file, "baseline", normalized)))
        case DbKind.Postgres => Right(None)
    else if SqlLayout.isAuxiliaryPath(normalized.path) then Right(None)
    else
      val category = SqlLayout.folderFromPath(normalized.path, dbKind)
      if FolderOrder.forDb(dbKind).contains(category) then Right(Some(withNormalizedPath(file, category, normalized)))
      else Left(normalized.folder)

  private def discoveryPath(file: SqlFile): String =
    val relativePath = file.relativePath.replace('\\', '/').trim
    val folder = file.folder.replace('\\', '/').trim
    if relativePath.contains("/") || folder.isEmpty || folder == "baseline" || folder == "uncategorized" then
      relativePath
    else s"$folder/${file.name}"

  private def withNormalizedPath(file: SqlFile, folder: String, normalized: NormalizedPath): SqlFile =
    file.copy(folder = folder, name = normalized.filename, relativePath = normalized.path)

  private def stripSqlRoot(parts: List[String]): List[String] =
    val index = parts.lastIndexWhere(_.equalsIgnoreCase("sql"))
    if index >= 0 && index < parts.length - 1 then parts.drop(index + 1) else parts

  private def pathParts(path: String): List[String] =
    path
      .replace('\\', '/')
      .split("/")
      .toList
      .map(_.trim)
      .filter(part => part.nonEmpty && part != "." && part != "..")

  private def toNormalizedPath(parts: List[String]): NormalizedPath =
    val filename = parts.lastOption.getOrElse("script.sql")
    val folder =
      if parts.lengthCompare(1) > 0 then parts.init.mkString("/")
      else if filename == "000_baseline.sql" then "baseline"
      else "uncategorized"
    val path = if parts.nonEmpty then parts.mkString("/") else filename
    NormalizedPath(path, folder, filename)
