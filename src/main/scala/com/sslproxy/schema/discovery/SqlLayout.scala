package com.sslproxy.schema.discovery

import com.sslproxy.schema.config.DbKind

import java.nio.file.{Files, Path}
import java.util.Locale

object SqlLayout:
  val ManifestName = "manifest.yaml"
  val GeneratedBaselineName = "_generated_baseline.sql"

  def engineName(dbKind: DbKind): String =
    dbKind match
      case DbKind.Postgres => "postgres"
      case DbKind.Oracle => "oracle"

  def resolveEngineRoot(sqlDir: Path, dbKind: DbKind): Path =
    val engine = sqlDir.resolve(engineName(dbKind))
    if hasLayerManifest(sqlDir) then sqlDir
    else if Files.isDirectory(engine) && hasLayerManifest(engine) then engine
    else sqlDir

  def generatedBaselinePath(sqlDir: Path, dbKind: DbKind): Path =
    resolveEngineRoot(sqlDir, dbKind).resolve(GeneratedBaselineName)

  def canonicalFolder(folder: String): String =
    val normalized = folder.replace('\\', '/').split('/').toList.lastOption.getOrElse(folder)
    val withoutOrder = normalized.replaceFirst("^\\d+_", "")
    withoutOrder.stripSuffix("_n-a").toLowerCase(Locale.ROOT)

  def folderFromPath(path: String, dbKind: DbKind): String =
    val parts = path.replace('\\', '/').split('/').toList.filter(_.nonEmpty)
    val categories = FolderOrder.forDb(dbKind).toSet
    parts.dropRight(1).reverse.map(canonicalFolder).find(categories.contains).getOrElse {
      parts.dropRight(1).lastOption.map(canonicalFolder).getOrElse("uncategorized")
    }

  def hasLayerManifest(path: Path): Boolean =
    Files.isRegularFile(path.resolve("core").resolve(ManifestName)) ||
      Files.isRegularFile(path.resolve(ManifestName))
