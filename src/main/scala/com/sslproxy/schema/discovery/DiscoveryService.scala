package com.sslproxy.schema.discovery

import cats.effect.Sync
import cats.syntax.all.*
import com.sslproxy.schema.config.DbKind

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class DiscoveryService[F[_]: Sync]:
  /** Discover SQL files from the filesystem. */
  def discover(sqlDir: Path, dbKind: DbKind, customer: Option[String] = None): F[DiscoveryResult] =
    Sync[F].blocking(discoverUnsafe(sqlDir, dbKind, customer))

  /** Discover SQL files from a pre-loaded list of SqlFile objects
    * (e.g. from MongoDB store). The caller is responsible for
    * obtaining the list from the store.
    */
  def discoverFromFiles(files: List[SqlFile], dbKind: DbKind): DiscoveryResult =
    val ordered = FolderOrder.forDb(dbKind)
    val normalized = files.map(file => SqlPathNormalizer.normalizeForDb(file, dbKind))
    val filesByFolder = normalized.collect { case Right(Some(file)) => file }.groupBy(_.folder)

    val extraFolderWarnings = normalized.collect { case Left(folder) => folder }.distinct.sorted
      .filterNot(folder => folder == "uncategorized")
      .map(folder => s"unrecognized sql folder '$folder' contains .sql files but is not part of $dbKind folder order")

    dbKind match
      case DbKind.Postgres =>
        val cronFiles = filesByFolder.getOrElse("cron", Nil).sortBy(_.name)
        val (preApplyHooks, cronJobs) = cronFiles.partition(_.name.startsWith("000_"))
        val discovered =
          ordered
            .filterNot(folder => folder == "cron" || folder == "materialized_views")
            .flatMap(folder => filesByFolder.getOrElse(folder, Nil).sortBy(_.name)) :::
            preApplyHooks :::
            filesByFolder.getOrElse("materialized_views", Nil).sortBy(_.name) :::
            cronJobs
        DiscoveryResult(discovered, extraFolderWarnings)

      case DbKind.Oracle =>
        val baseline = filesByFolder.getOrElse("baseline", Nil).sortBy(_.name)
        val discovered = baseline ::: ordered.flatMap(folder => filesByFolder.getOrElse(folder, Nil).sortBy(_.name))
        DiscoveryResult(discovered, extraFolderWarnings)

  private def discoverUnsafe(sqlDir: Path, dbKind: DbKind, customer: Option[String]): DiscoveryResult =
    val engineRoot = SqlLayout.resolveEngineRoot(sqlDir, dbKind)
    if Files.isRegularFile(engineRoot.resolve("core").resolve(SqlLayout.ManifestName)) then
      discoverManifestLayout(sqlDir, engineRoot, dbKind, customer)
    else discoverFolderOrder(sqlDir, dbKind)

  private def discoverManifestLayout(
    requestedRoot: Path,
    engineRoot: Path,
    dbKind: DbKind,
    customer: Option[String]
  ): DiscoveryResult =
    val layerManifests =
      List(
        "core" -> engineRoot.resolve("core").resolve(SqlLayout.ManifestName),
        "contracts" -> engineRoot.resolve("contracts").resolve(SqlLayout.ManifestName)
      ) ++ customer.toList.map(name => "customer" -> engineRoot.resolve("customers").resolve(name).resolve(SqlLayout.ManifestName))

    val manifestResults = layerManifests.map { case (layer, manifestPath) =>
      if Files.isRegularFile(manifestPath) then
        ApplyOrderManifest.read(manifestPath) match
          case Right(manifest) => Some(layer -> manifest) -> ApplyOrderManifest.validate(manifest, dbKind, layer)
          case Left(error) => None -> List(error)
      else None -> List(s"manifest '$manifestPath' is missing; skipping $layer layer")
    }

    val manifests = manifestResults.flatMap(_._1)
    val manifestWarnings = manifestResults.flatMap(_._2)
    val manifestFiles = manifests.flatMap { case (layer, manifest) =>
      filesFromManifest(requestedRoot, layer, manifest, dbKind)
    }
    val files = manifestFiles.collect { case Right(file) => file }
    val fileWarnings = manifestFiles.collect { case Left(warning) => warning }
    val unlistedWarnings = manifests.flatMap { case (_, manifest) =>
      unlistedSqlWarnings(requestedRoot, manifest, files)
    }

    DiscoveryResult(files, manifestWarnings ++ fileWarnings ++ unlistedWarnings)

  private def filesFromManifest(
    requestedRoot: Path,
    layer: String,
    manifest: ApplyOrderManifest,
    dbKind: DbKind
  ): List[Either[String, SqlFile]] =
    val manifestDir = manifest.path.getParent
    manifest.applyOrder.map { entry =>
      val relative = Path.of(entry)
      if relative.isAbsolute then Left(s"${manifest.path}: absolute apply_order entry '$entry' is not allowed")
      else
        val path = manifestDir.resolve(relative).normalize()
        if !path.startsWith(manifestDir.normalize()) then
          Left(s"${manifest.path}: apply_order entry '$entry' escapes the $layer layer")
        else if !Files.isRegularFile(path) then Left(s"${manifest.path}: apply_order entry '$entry' does not exist")
        else if !path.getFileName.toString.endsWith(".sql") then
          Left(s"${manifest.path}: apply_order entry '$entry' is not a .sql file")
        else
          val relativePath = requestedRoot.normalize().relativize(path).toString
          val folder = SqlLayout.folderFromPath(relativePath, dbKind)
          Right(SqlFile(folder, path, path.getFileName.toString, relativePath))
    }

  private def unlistedSqlWarnings(
    requestedRoot: Path,
    manifest: ApplyOrderManifest,
    discovered: List[SqlFile]
  ): List[String] =
    val manifestDir = manifest.path.getParent
    val listed = discovered.map(_.path.normalize()).toSet
    scala.util.Using.resource(Files.walk(manifestDir)) { stream =>
      stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sql"))
        .filterNot(path => listed.contains(path.normalize()))
        .map(path => s"${requestedRoot.normalize().relativize(path).toString}: SQL file is not listed in ${manifest.path}")
        .toList
        .sorted
    }

  private def discoverFolderOrder(sqlDir: Path, dbKind: DbKind): DiscoveryResult =
    val ordered = FolderOrder.forDb(dbKind)
    val allowed = ordered.toSet
    val collected = ordered.map(folder => folder -> collectFolder(sqlDir, folder))
    val warnings = collected.flatMap(_._2._2)
    val filesByFolder = collected.map { case (folder, (files, _)) => folder -> files }.toMap

    val extraFolderWarnings =
      if Files.isDirectory(sqlDir) then
        scala.util.Using.resource(Files.list(sqlDir)) { stream =>
          stream
            .iterator()
            .asScala
            .filter(Files.isDirectory(_))
            .flatMap { dir =>
              val name = dir.getFileName.toString
              if !allowed
                  .contains(name) && !Files.list(dir).iterator().asScala.exists(_.getFileName.toString.endsWith(".sql"))
              then Nil
              else if !allowed.contains(name) then
                List(
                  s"unrecognized sql subdirectory '$name' contains .sql files but is not part of $dbKind folder order"
                )
              else Nil
            }
            .toList
        }
      else Nil

    val allWarnings = warnings ++ extraFolderWarnings

    dbKind match
      case DbKind.Postgres =>
        val cronFiles = filesByFolder.getOrElse("cron", Nil)
        val (preApplyHooks, cronJobs) = cronFiles.partition(_.name.startsWith("000_"))
        val files =
          ordered
            .filterNot(folder => folder == "cron" || folder == "materialized_views")
            .flatMap(folder => filesByFolder.getOrElse(folder, Nil)) :::
            preApplyHooks :::
            filesByFolder.getOrElse("materialized_views", Nil) :::
            cronJobs
        DiscoveryResult(files, allWarnings)

      case DbKind.Oracle =>
        val baseline = collectOracleBaseline(sqlDir)
        val files = baseline ::: ordered.flatMap(folder => filesByFolder.getOrElse(folder, Nil))
        DiscoveryResult(files, allWarnings)

  private def collectFolder(sqlDir: Path, folder: String): (List[SqlFile], List[String]) =
    val folderPath = sqlDir.resolve(folder)
    if !Files.exists(folderPath) then Nil -> List(s"folder '$folderPath' is missing; skipping")
    else if !Files.isDirectory(folderPath) then Nil -> List(s"path '$folderPath' is not a directory; skipping")
    else
      val files =
        scala.util.Using.resource(Files.list(folderPath)) { stream =>
          stream
            .iterator()
            .asScala
            .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".sql"))
            .toList
            .sortBy(_.getFileName.toString)
            .map(path => SqlFile(folder, path, path.getFileName.toString, sqlDir.relativize(path).toString))
        }
      files -> Nil

  private def collectOracleBaseline(sqlDir: Path): List[SqlFile] =
    val baseline = sqlDir.resolve("000_baseline.sql")
    if Files.isRegularFile(baseline) then
      List(SqlFile("baseline", baseline, "000_baseline.sql", sqlDir.relativize(baseline).toString))
    else Nil
