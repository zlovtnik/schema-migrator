package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*

import java.util.Base64
import java.util.UUID

trait SnapshotStore:
  def list(targetId: Option[String]): IO[List[Snapshot]]
  def create(targetId: String, label: Option[String], files: List[StoredSqlFile], createdBy: String): IO[Snapshot]
  def get(id: String): IO[Option[Snapshot]]

object SnapshotStore:
  def inMemory: IO[SnapshotStore] =
    Ref.of[IO, Map[String, Snapshot]](Map.empty).map(InMemorySnapshotStore.apply)

  def publicView(snapshot: Snapshot): Snapshot =
    snapshot.copy(files = snapshot.files.map(publicFile))

  def publicFile(file: SnapshotFile): SnapshotFile =
    file.copy(content_base64 = None)

  def diff(base: Snapshot, compare: Snapshot, generatedAt: String): SnapshotDiff =
    val baseByPath = base.files.map(file => file.path -> file).toMap
    val compareByPath = compare.files.map(file => file.path -> file).toMap
    val paths = (baseByPath.keySet ++ compareByPath.keySet).toList.sorted
    val items = paths.flatMap { path =>
      (baseByPath.get(path), compareByPath.get(path)) match
        case (None, Some(after)) =>
          Some(SnapshotDiffItem(path, "added", None, Some(after.sha256)))
        case (Some(before), None) =>
          Some(SnapshotDiffItem(path, "removed", Some(before.sha256), None))
        case (Some(before), Some(after)) if before.sha256 != after.sha256 =>
          Some(SnapshotDiffItem(path, "changed", Some(before.sha256), Some(after.sha256)))
        case _ => None
    }
    SnapshotDiff(base.id, compare.id, generatedAt, items)

  private[store] def snapshotFromFiles(
    id: String,
    targetId: String,
    label: Option[String],
    createdAt: String,
    createdBy: String,
    files: List[StoredSqlFile]
  ): Snapshot =
    val snapshotFiles = files.sortBy(file => (file.folder, file.filename)).map { file =>
      SnapshotFile(
        path = file.path,
        folder = file.folder,
        filename = file.filename,
        sha256 = file.sha256,
        content_base64 = Some(file.contentBase64),
        uploaded_at = file.uploadedAt,
        size_bytes = decodedSize(file.contentBase64)
      )
    }
    Snapshot(
      id = id,
      target_id = targetId,
      label = label.map(_.trim).filter(_.nonEmpty).getOrElse("Snapshot"),
      created_at = createdAt,
      created_by = createdBy,
      file_count = snapshotFiles.length,
      files = snapshotFiles
    )

  private def decodedSize(contentBase64: String): Long =
    Either.catchNonFatal(Base64.getDecoder.decode(contentBase64).length.toLong).getOrElse(0L)

private final class InMemorySnapshotStore(ref: Ref[IO, Map[String, Snapshot]]) extends SnapshotStore:
  override def list(targetId: Option[String]): IO[List[Snapshot]] =
    ref.get.map { snapshots =>
      snapshots.values.toList
        .filter(snapshot => targetId.forall(_ == snapshot.target_id))
        .sortBy(_.created_at)
        .reverse
    }

  override def create(
    targetId: String,
    label: Option[String],
    files: List[StoredSqlFile],
    createdBy: String
  ): IO[Snapshot] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- Clock[IO].realTimeInstant.map(_.toString)
      snapshot = SnapshotStore.snapshotFromFiles(id, targetId, label, now, createdBy, files)
      _ <- ref.update(_ + (id -> snapshot))
    yield snapshot

  override def get(id: String): IO[Option[Snapshot]] =
    ref.get.map(_.get(id))


