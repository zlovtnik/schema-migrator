package com.sslproxy.schema.discovery

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.store.StoredSqlFile
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId

import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*

final class GitRepoLoader(transportTimeoutSeconds: Int = 60):
  private val maxFileCount = 10000
  private val maxFileSizeBytes = 10L * 1024 * 1024 // 10 MB
  private val maxTotalSizeBytes = 500L * 1024 * 1024 // 500 MB

  def cloned[A](repoUrl: String, branch: String, cacheDir: Path)(use: Path => IO[A]): IO[A] =
    cloneResource(repoUrl, branch, cacheDir).use(use)

  def resolveSqlRoot(repoRoot: Path, repoSqlPath: String): Either[String, Path] =
    val trimmed = repoSqlPath.trim
    if trimmed.isEmpty then Left("Repository SQL path is required")
    else
      val relative = Path.of(trimmed)
      val repoRootNormalized = repoRoot.toAbsolutePath.normalize()
      val candidate = repoRootNormalized.resolve(relative).normalize()
      if relative.isAbsolute || !candidate.startsWith(repoRootNormalized) then
        Left("Repository SQL path must stay inside the repository")
      else if traversesSymlink(repoRootNormalized, candidate) then
        Left("Repository SQL path must not traverse symlinks")
      else if Files.notExists(candidate) then Left(s"repository SQL path '$repoSqlPath' does not exist")
      else if !Files.isDirectory(candidate) then Left(s"repository SQL path '$repoSqlPath' is not a directory")
      else Right(candidate)

  def headCommit(repoRoot: Path): IO[String] =
    IO.blocking {
      val git = Git.open(repoRoot.toFile)
      try
        val head = Option(git.getRepository.resolve("HEAD")).getOrElse(ObjectId.zeroId())
        head.name()
      finally git.close()
    }

  def remoteHead(repoUrl: String, branch: String): IO[Option[String]] =
    IO.blocking {
      val refs = Git
        .lsRemoteRepository()
        .setRemote(repoUrl)
        .setHeads(true)
        .setTimeout(transportTimeoutSeconds)
        .call()
        .asScala
        .toList
      refs.find(_.getName == s"refs/heads/$branch").map(_.getObjectId.name())
    }

  def loadFiles(sqlRoot: Path): IO[List[StoredSqlFile]] =
    Clock[IO].realTimeInstant.flatMap { now =>
      IO.blocking {
        scala.util.Using.resource(Files.walk(sqlRoot)) { stream =>
          val paths = stream
            .iterator()
            .asScala
            .filter(path => Files.isRegularFile(path) && path.getFileName.toString.toLowerCase.endsWith(".sql"))
            .toList
            .sortBy(path => sqlRoot.relativize(path).toString)

          if paths.size > maxFileCount then
            throw IllegalStateException(
              s"SQL file count ${paths.size} exceeds maximum of $maxFileCount"
            )

          var totalSize = 0L
          paths.foreach { path =>
            val relative = sqlRoot.relativize(path).toString.replace('\\', '/')
            val normalized = sqlRoot.toAbsolutePath.normalize()
            val resolved = normalized.resolve(relative).normalize()
            if !resolved.startsWith(normalized) then
              throw IllegalStateException(s"File '$relative' escapes the repository root")
            if isSymlink(path) then
              throw IllegalStateException(s"SQL file '$relative' is a symlink; symlinks are not allowed")

            val size = Files.size(path)
            if size > maxFileSizeBytes then
              throw IllegalStateException(
                s"SQL file '$relative' size ${size} bytes exceeds maximum of $maxFileSizeBytes bytes"
              )
            totalSize += size
          }

          if totalSize > maxTotalSizeBytes then
            throw IllegalStateException(
              s"Total SQL file size $totalSize bytes exceeds maximum of $maxTotalSizeBytes bytes"
            )

          paths.map { path =>
            val relativePath = sqlRoot.relativize(path).toString.replace('\\', '/')
            val normalized = SqlPathNormalizer.normalizeUploadPath(relativePath)
            StoredSqlFile.fromBytes(
              path = normalized.path,
              folder = normalized.folder,
              filename = normalized.filename,
              bytes = Files.readAllBytes(path),
              uploadedAt = now.toString
            )
          }
        }
      }
    }

  private def cloneResource(repoUrl: String, branch: String, cacheDir: Path): Resource[IO, Path] =
    for
      _ <- Resource.make(IO.blocking(Files.createDirectories(cacheDir)))(_ => IO.unit)
      workDir <- Resource.make(IO.blocking(Files.createTempDirectory(cacheDir, "repo-")))(deleteRecursively)
      _ <- Resource.eval(clone(repoUrl, branch, workDir))
    yield workDir

  private def clone(repoUrl: String, branch: String, workDir: Path): IO[Unit] =
    IO.blocking {
      val git = Git
        .cloneRepository()
        .setURI(repoUrl)
        .setBranch(branch)
        .setDepth(1)
        .setDirectory(workDir.toFile)
        .setTimeout(transportTimeoutSeconds)
        .call()
      git.close()
    }

  private def deleteRecursively(path: Path): IO[Unit] =
    IO.blocking {
      if Files.exists(path) then
        scala.util.Using.resource(Files.walk(path)) { stream =>
          stream.iterator().asScala.toList.sortBy(_.toString).reverse.foreach(Files.deleteIfExists)
        }
    }

  private def traversesSymlink(repoRoot: Path, candidate: Path): Boolean =
    if Files.isSymbolicLink(repoRoot) then true
    else
      var current = candidate
      while current != null && current != repoRoot do
        if Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current) then return true
        current = current.getParent
      false

  private def isSymlink(path: Path): Boolean =
    Files.isSymbolicLink(path)
