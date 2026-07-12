package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, ReplaceOptions, Sorts}
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.sslproxy.schema.config.MongoConfig
import org.bson.Document

import java.util.Base64
import java.util.UUID
import scala.jdk.CollectionConverters.*

trait SnapshotStore:
  def list(targetId: Option[String]): IO[List[Snapshot]]
  def create(targetId: String, label: Option[String], files: List[StoredSqlFile], createdBy: String): IO[Snapshot]
  def get(id: String): IO[Option[Snapshot]]

object SnapshotStore:
  def mongo(config: MongoConfig, collectionName: String): Resource[IO, SnapshotStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .flatMap(client => mongo(config, collectionName, client))

  def mongo(config: MongoConfig, collectionName: String, client: MongoClient): Resource[IO, SnapshotStore] =
    Resource.eval {
      val store = MongoSnapshotStore(client.getDatabase(config.database).getCollection(collectionName))
      store.initialize.as(store: SnapshotStore)
    }

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

private final class MongoSnapshotStore(collection: MongoCollection[Document]) extends SnapshotStore:
  override def list(targetId: Option[String]): IO[List[Snapshot]] =
    IO.blocking {
      val filter = targetId.fold(new Document())(id => new Document("target_id", id))
      collection
        .find(filter)
        .sort(Sorts.descending("created_at"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
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
      _ <- IO.blocking(collection.replaceOne(idFilter(id), toDocument(snapshot), ReplaceOptions().upsert(true))).void
    yield snapshot

  override def get(id: String): IO[Option[Snapshot]] =
    IO.blocking(Option(collection.find(idFilter(id)).first()).map(fromDocument))

  private[store] def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("target_id", "created_at"))
    }.void

  private def toDocument(snapshot: Snapshot): Document =
    new Document()
      .append("_id", snapshot.id)
      .append("target_id", snapshot.target_id)
      .append("label", snapshot.label)
      .append("created_at", snapshot.created_at)
      .append("created_by", snapshot.created_by)
      .append("file_count", snapshot.file_count)
      .append("files", snapshot.files.map(fileDocument).asJava)

  private def fileDocument(file: SnapshotFile): Document =
    new Document()
      .append("path", file.path)
      .append("folder", file.folder)
      .append("filename", file.filename)
      .append("sha256", file.sha256)
      .append("content_base64", file.content_base64.getOrElse(""))
      .append("uploaded_at", file.uploaded_at)
      .append("size_bytes", Long.box(file.size_bytes))

  private def fromDocument(document: Document): Snapshot =
    val files = fileDocuments(document).map(fileFromDocument)
    Snapshot(
      id = requiredString(document, "_id"),
      target_id = requiredString(document, "target_id"),
      label = requiredString(document, "label"),
      created_at = requiredString(document, "created_at"),
      created_by = requiredString(document, "created_by"),
      file_count = intValue(document, "file_count").filter(_ > 0).getOrElse(files.length),
      files = files
    )

  private def fileFromDocument(document: Document): SnapshotFile =
    SnapshotFile(
      path = requiredString(document, "path"),
      folder = requiredString(document, "folder"),
      filename = requiredString(document, "filename"),
      sha256 = requiredString(document, "sha256"),
      content_base64 = Some(optionalRawString(document, "content_base64").getOrElse("")),
      uploaded_at = requiredString(document, "uploaded_at"),
      size_bytes = longValue(document, "size_bytes").getOrElse(0L)
    )

  private def fileDocuments(document: Document): List[Document] =
    MongoDocument.documentList(document, "files")

  private def idFilter(id: String): Document =
    new Document("_id", id)

  private def requiredString(document: Document, field: String): String =
    MongoDocument.requiredString(document, field, "snapshot")

  private def optionalRawString(document: Document, field: String): Option[String] =
    MongoDocument.optionalRawString(document, field)

  private def intValue(document: Document, field: String): Option[Int] =
    MongoDocument.optionalInt(document, field)

  private def longValue(document: Document, field: String): Option[Long] =
    MongoDocument.optionalLong(document, field)
