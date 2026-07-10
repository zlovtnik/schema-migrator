package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, ReplaceOptions}
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.sslproxy.schema.config.MongoConfig
import com.sslproxy.schema.discovery.SqlFile
import org.bson.Document

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters.*

final case class StoredSqlFile(
  path: String,
  folder: String,
  filename: String,
  contentBase64: String,
  sha256: String,
  uploadedAt: String
)

trait SqlFileStore:
  /** List all stored SQL files for a specific target, ordered by folder then filename. */
  def list(targetId: String): IO[List[StoredSqlFile]]

  /** List files for a specific folder for a specific target. */
  def listByFolder(targetId: String, folder: String): IO[List[StoredSqlFile]]

  /** Upsert a batch of files for a specific target (replaces all existing content for that target). */
  def replaceAll(targetId: String, files: List[StoredSqlFile]): IO[Unit]

  /** Delete all stored SQL files for a specific target. */
  def clear(targetId: String): IO[Unit]

  /** Check if any SQL files are stored for a specific target. */
  def isEmpty(targetId: String): IO[Boolean]

  /** Convert stored files to discovery SqlFile objects. */
  def toSqlFiles(targetId: String): IO[List[SqlFile]] =
    list(targetId).flatMap(_.traverse(SqlFileStore.toSqlFile))

object SqlFileStore:
  def mongo(config: MongoConfig, collectionName: String): Resource[IO, SqlFileStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .flatMap(client => mongo(config, collectionName, client))

  def mongo(config: MongoConfig, collectionName: String, client: MongoClient): Resource[IO, SqlFileStore] =
    Resource.eval {
      val store = MongoSqlFileStore(
        client.getDatabase(config.database).getCollection(collectionName)
      )
      store.initialize.as(store: SqlFileStore)
    }

  def inMemory: IO[SqlFileStore] =
    Ref.of[IO, Map[String, StoredSqlFile]](Map.empty).map(InMemorySqlFileStore(_))

  private def toSqlFile(stored: StoredSqlFile): IO[SqlFile] =
    IO.delay {
      val bytes = Base64.getDecoder.decode(stored.contentBase64)
      val virtualPath = Path.of(stored.path)
      SqlFile(
        folder = stored.folder,
        path = virtualPath,
        name = stored.filename,
        relativePath = stored.path,
        content = Some(new String(bytes, StandardCharsets.UTF_8))
      )
    }

private final class MongoSqlFileStore(collection: MongoCollection[Document]) extends SqlFileStore:
  override def list(targetId: String): IO[List[StoredSqlFile]] =
    IO.blocking {
      collection
        .find(targetIdFilter(targetId))
        .sort(Indexes.ascending("folder", "filename"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
    }

  override def listByFolder(targetId: String, folder: String): IO[List[StoredSqlFile]] =
    IO.blocking {
      collection
        .find(new Document("target_id", targetId).append("folder", folder))
        .sort(Indexes.ascending("filename"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
    }

  override def replaceAll(targetId: String, files: List[StoredSqlFile]): IO[Unit] =
    IO.blocking {
      val upsert = ReplaceOptions().upsert(true)
      files.foreach { file =>
        collection.replaceOne(
          new Document("_id", s"$targetId:${file.path}"),
          toDocument(targetId, file),
          upsert
        )
      }
      if files.isEmpty then collection.deleteMany(targetIdFilter(targetId))
      else collection.deleteMany(
        new Document("target_id", targetId)
          .append("_id", new Document("$nin", files.map(f => s"$targetId:${f.path}").asJava))
      )
      ()
    }

  override def clear(targetId: String): IO[Unit] =
    IO.blocking(collection.deleteMany(targetIdFilter(targetId))).void

  override def isEmpty(targetId: String): IO[Boolean] =
    IO.blocking(collection.countDocuments(targetIdFilter(targetId)) == 0)

  private[store] def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("target_id", "folder", "filename"))
    }.void

  private def toDocument(targetId: String, file: StoredSqlFile): Document =
    new Document()
      .append("_id", s"$targetId:${file.path}")
      .append("target_id", targetId)
      .append("path", file.path)
      .append("folder", file.folder)
      .append("filename", file.filename)
      .append("content_base64", file.contentBase64)
      .append("sha256", file.sha256)
      .append("uploaded_at", file.uploadedAt)

  private def fromDocument(doc: Document): StoredSqlFile =
    StoredSqlFile(
      path = requiredString(doc, "path"),
      folder = requiredString(doc, "folder"),
      filename = requiredString(doc, "filename"),
      contentBase64 = requiredString(doc, "content_base64"),
      sha256 = requiredString(doc, "sha256"),
      uploadedAt = requiredString(doc, "uploaded_at")
    )

  private def requiredString(doc: Document, field: String): String =
    Option(doc.getString(field))
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"sql_file document is missing required field '$field'"))

  private def targetIdFilter(targetId: String): Document =
    new Document("target_id", targetId)

private final class InMemorySqlFileStore(ref: Ref[IO, Map[String, StoredSqlFile]]) extends SqlFileStore:
  override def list(targetId: String): IO[List[StoredSqlFile]] =
    ref.get.map(_.filter { case (key, _) => key.startsWith(s"$targetId:") }.values.toList.sortBy(f => (f.folder, f.filename)))

  override def listByFolder(targetId: String, folder: String): IO[List[StoredSqlFile]] =
    ref.get.map(_.filter { case (key, _) => key.startsWith(s"$targetId:") }.values.toList.filter(_.folder == folder).sortBy(_.filename))

  override def replaceAll(targetId: String, files: List[StoredSqlFile]): IO[Unit] =
    ref.update { current =>
      val prefixed = files.map(f => s"$targetId:${f.path}" -> f).toMap
      val withoutOld = current.filterNot { case (key, _) => key.startsWith(s"$targetId:") }
      withoutOld ++ prefixed
    }

  override def clear(targetId: String): IO[Unit] =
    ref.update(_.filterNot { case (key, _) => key.startsWith(s"$targetId:") })

  override def isEmpty(targetId: String): IO[Boolean] =
    ref.get.map(!_.keys.exists(_.startsWith(s"$targetId:")))

object StoredSqlFile:
  def fromBytes(
    path: String,
    folder: String,
    filename: String,
    bytes: Array[Byte],
    uploadedAt: String
  ): StoredSqlFile =
    val sha256 = MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    val base64 = Base64.getEncoder.encodeToString(bytes)
    StoredSqlFile(
      path = path,
      folder = folder,
      filename = filename,
      contentBase64 = base64,
      sha256 = sha256,
      uploadedAt = uploadedAt
    )