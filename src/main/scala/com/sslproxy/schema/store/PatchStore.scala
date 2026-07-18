package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import com.sslproxy.schema.config.DbKind
import com.sslproxy.schema.discovery.{SqlFile, SqlPathNormalizer}
import com.sslproxy.schema.parser.HeaderParser

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import scala.jdk.CollectionConverters.*

final case class PatchUpload(filename: String, bytes: Array[Byte], order: Int)
final case class PatchSqlFile(script: Script, sqlFile: SqlFile)

trait PatchStore:
  def list(targetId: Option[String]): IO[List[Patch]]
  def create(targetId: String, uploads: List[PatchUpload]): IO[Patch] =
    create(targetId, uploads, None)
  def create(targetId: String, uploads: List[PatchUpload], sourceSnapshotId: Option[String]): IO[Patch]
  def get(id: String): IO[Option[Patch]]
  def delete(id: String): IO[Boolean]
  def markApplied(id: String, appliedAt: String): IO[Unit]
  def markFailed(id: String): IO[Unit]
  def sqlFiles(patch: Patch, dbKind: DbKind): IO[List[PatchSqlFile]]

object PatchStore:
  private[store] val versionFormatter =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

  def inMemory(stageDir: Path): IO[PatchStore] =
    Ref.of[IO, Map[String, Patch]](Map.empty).map(InMemoryPatchStore(stageDir, _))

  private[store] def scriptId(patchId: String, order: Int): String =
    s"$patchId-$order"

  private[store] def safeName(filename: String): String =
    filename.replaceAll("[^A-Za-z0-9._-]", "_")

  private[store] def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString

  private[store] def scriptFromUpload(patchId: String, upload: PatchUpload): Script =
    Script(
      id = scriptId(patchId, upload.order),
      patch_id = patchId,
      order = upload.order,
      filename = upload.filename,
      checksum = sha256(upload.bytes),
      status = "pending",
      error = None,
      duration_ms = None
    )

  private[store] def sqlFile(script: Script, path: Path, content: String, dbKind: DbKind): PatchSqlFile =
    val headerFolder = HeaderParser.value(content, "folder").map(_.replace('\\', '/').trim).filter(_.nonEmpty)
    val uploadPath = SqlPathNormalizer.normalizeUploadPath(script.filename)
    val folder = headerFolder.getOrElse(uploadPath.folder)
    val filename = uploadPath.filename
    val uniqueFilename = f"${script.order}%04d_$filename"
    val relativePath =
      if folder == "uncategorized" then uniqueFilename
      else s"$folder/$uniqueFilename"
    val normalized = SqlFile(folder, path, filename, relativePath, Some(content))
    PatchSqlFile(script, SqlPathNormalizer.normalizeForDb(normalized, dbKind).toOption.flatten.getOrElse(normalized))

private final class InMemoryPatchStore(stageDir: Path, ref: Ref[IO, Map[String, Patch]]) extends PatchStore:

  override def list(targetId: Option[String]): IO[List[Patch]] =
    ref.get.map { patches =>
      patches.values.toList
        .filter(patch => targetId.forall(_ == patch.target_id))
        .sortBy(_.version)
    }

  override def create(targetId: String, uploads: List[PatchUpload], sourceSnapshotId: Option[String]): IO[Patch] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      patch <- createWithId(id, targetId, uploads, sourceSnapshotId).onError { case _ => cleanupPatch(id) }
    yield patch

  override def get(id: String): IO[Option[Patch]] =
    ref.get.map(_.get(id))

  override def delete(id: String): IO[Boolean] =
    ref.get.map(_.contains(id)).flatMap {
      case false => IO.pure(false)
      case true =>
        moveStageDirAside(id).flatMap { moved =>
          deleteStageDir(moved)
            .flatMap { _ =>
              ref.modify { values =>
                if values.contains(id) then (values - id) -> true else values -> false
              }
            }
            .handleErrorWith { error =>
              restoreStageDir(id, moved) *> IO.raiseError(error)
            }
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

  override def sqlFiles(patch: Patch, dbKind: DbKind): IO[List[PatchSqlFile]] =
    patch.scripts.sortBy(_.order).traverse { script =>
      val path = stagedPath(patch.id, script)
      IO.blocking(Files.readString(path)).map(content => PatchStore.sqlFile(script, path, content, dbKind))
    }

  private def createWithId(
    id: String,
    targetId: String,
    uploads: List[PatchUpload],
    sourceSnapshotId: Option[String]
  ): IO[Patch] =
    for
      now <- Clock[IO].realTimeInstant
      scripts <- uploads.sortBy(_.order).traverse(upload => stageUpload(id, upload))
      patch = Patch(
        id = id,
        target_id = targetId,
        version = PatchStore.versionFormatter.format(now),
        label = scripts.map(_.filename).mkString(", "),
        scripts = scripts,
        status = "pending",
        applied_at = None,
        source_snapshot_id = sourceSnapshotId
      )
      _ <- ref.update(_ + (id -> patch))
    yield patch

  private def stageUpload(patchId: String, upload: PatchUpload): IO[Script] =
    val targetPath = stagedPath(patchId, upload.order, upload.filename)
    for
      _ <- IO.blocking(Files.createDirectories(targetPath.getParent))
      _ <- IO.blocking(Files.write(targetPath, upload.bytes)).void
    yield PatchStore.scriptFromUpload(patchId, upload)

  private def cleanupPatch(patchId: String): IO[Unit] =
    ref.update(_ - patchId) *> deleteStageDir(patchId).handleErrorWith(_ => IO.unit)

  private def deleteStageDir(patchId: String): IO[Unit] =
    IO.blocking(deleteRecursively(stageDir.resolve(patchId)))

  private def deleteStageDir(path: Option[Path]): IO[Unit] =
    path.traverse_(target => IO.blocking(deleteRecursively(target)))

  private def moveStageDirAside(patchId: String): IO[Option[Path]] =
    IO.blocking {
      val source = stageDir.resolve(patchId)
      if Files.exists(source) then
        val target = stageDir.resolve(s".$patchId.delete-${UUID.randomUUID()}")
        Some(Files.move(source, target, StandardCopyOption.ATOMIC_MOVE))
      else None
    }

  private def restoreStageDir(patchId: String, moved: Option[Path]): IO[Unit] =
    moved.traverse_ { target =>
      IO.blocking {
        val original = stageDir.resolve(patchId)
        if Files.exists(target) && Files.notExists(original) then
          Files.move(target, original, StandardCopyOption.ATOMIC_MOVE)
          ()
        ()
      }.handleErrorWith(_ => IO.unit)
    }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
      ()

  private def stagedPath(patchId: String, script: Script): Path =
    stagedPath(patchId, script.order, script.filename)

  private def stagedPath(patchId: String, order: Int, filename: String): Path =
    stageDir.resolve(patchId).resolve(f"$order%04d_${PatchStore.safeName(filename)}")

