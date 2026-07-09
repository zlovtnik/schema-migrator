package com.sslproxy.schema.discovery

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.store.StoredSqlFile
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class GitRepoLoader(transportTimeoutSeconds: Int = 60):
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
          stream
            .iterator()
            .asScala
            .filter(path => Files.isRegularFile(path) && path.getFileName.toString.toLowerCase.endsWith(".sql"))
            .toList
            .sortBy(path => sqlRoot.relativize(path).toString)
            .map { path =>
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
    Resource.make {
      for
        _ <- IO.blocking(Files.createDirectories(cacheDir))
        workDir <- IO.blocking(Files.createTempDirectory(cacheDir, "repo-"))
        _ <- clone(repoUrl, branch, workDir)
      yield workDir
    }(deleteRecursively)

  private def clone(repoUrl: String, branch: String, workDir: Path): IO[Unit] =
    IO.blocking {
      val git = Git
        .cloneRepository()
        .setURI(repoUrl)
        .setBranch(branch)
        .setDepth(1)
        .setDirectory(workDir.toFile)
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
