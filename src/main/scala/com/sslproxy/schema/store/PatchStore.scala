package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.jdk.CollectionConverters.*

final case class PatchUpload(filename: String, bytes: Array[Byte], order: Int)

trait PatchStore:
  def list(targetId: Option[String]): IO[List[Patch]]
  def create(targetId: String, uploads: List[PatchUpload]): IO[Patch]
  def get(id: String): IO[Option[Patch]]
  def delete(id: String): IO[Boolean]
  def markApplied(id: String, appliedAt: String): IO[Unit]
  def markFailed(id: String): IO[Unit]

object PatchStore:
  def inMemory(stageDir: Path): IO[PatchStore] =
    Ref.of[IO, Map[String, Patch]](Map.empty).map(InMemoryPatchStore(stageDir, _))

private final class InMemoryPatchStore(stageDir: Path, ref: Ref[IO, Map[String, Patch]]) extends PatchStore:
  private val versionFormatter =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

  override def list(targetId: Option[String]): IO[List[Patch]] =
    ref.get.map { patches =>
      patches.values.toList
        .filter(patch => targetId.forall(_ == patch.target_id))
        .sortBy(_.version)
    }

  override def create(targetId: String, uploads: List[PatchUpload]): IO[Patch] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      patch <- createWithId(id, targetId, uploads).onError { case _ => cleanupPatch(id) }
    yield patch

  override def get(id: String): IO[Option[Patch]] =
    ref.get.map(_.get(id))

  override def delete(id: String): IO[Boolean] =
    ref
      .modify { values =>
        values.get(id) match
          case Some(patch) => (values - id) -> Some(patch)
          case None        => values -> None
      }
      .flatMap {
        case None => IO.pure(false)
        case Some(patch) =>
          deleteStageDir(id).as(true).handleErrorWith { error =>
            ref.update(_ + (id -> patch)) *> IO.raiseError(error)
          }
    }

  override def markApplied(id: String, appliedAt: String): IO[Unit] =
    ref.update { values =>
      values.updatedWith(id) {
        _.map { patch =>
          val scripts = patch.scripts.map(script =>
            script.copy(status = "completed", duration_ms = script.duration_ms.orElse(Some(0L)))
          )
          patch.copy(status = "applied", scripts = scripts, applied_at = Some(appliedAt))
        }
      }
    }

  override def markFailed(id: String): IO[Unit] =
    ref.update { values =>
      values.updatedWith(id)(_.map(_.copy(status = "failed")))
    }

  private def createWithId(id: String, targetId: String, uploads: List[PatchUpload]): IO[Patch] =
    for
      now <- Clock[IO].realTimeInstant
      scripts <- uploads.sortBy(_.order).traverse(upload => stageUpload(id, upload))
      patch = Patch(
        id = id,
        target_id = targetId,
        version = versionFormatter.format(now),
        label = scripts.map(_.filename).mkString(", "),
        scripts = scripts,
        status = "pending",
        applied_at = None
      )
      _ <- ref.update(_ + (id -> patch))
    yield patch

  private def stageUpload(patchId: String, upload: PatchUpload): IO[Script] =
    val scriptId = s"$patchId-${upload.order}"
    val safeName = upload.filename.replaceAll("[^A-Za-z0-9._-]", "_")
    val targetPath = stageDir.resolve(patchId).resolve(f"${upload.order}%04d_$safeName")
    for
      _ <- IO.blocking(Files.createDirectories(targetPath.getParent))
      _ <- IO.blocking(Files.write(targetPath, upload.bytes)).void
      checksum <- IO.delay(sha256(upload.bytes))
    yield Script(
      id = scriptId,
      patch_id = patchId,
      order = upload.order,
      filename = upload.filename,
      checksum = checksum,
      status = "pending",
      error = None,
      duration_ms = None
    )

  private def cleanupPatch(patchId: String): IO[Unit] =
    ref.update(_ - patchId) *> deleteStageDir(patchId).handleErrorWith(_ => IO.unit)

  private def deleteStageDir(patchId: String): IO[Unit] =
    IO.blocking(deleteRecursively(stageDir.resolve(patchId)))

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)

  private def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString
