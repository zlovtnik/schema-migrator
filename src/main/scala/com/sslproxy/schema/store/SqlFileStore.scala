package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, ReplaceOptions}
import com.mongodb.client.{MongoClients, MongoCollection}
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
  /** List all stored SQL files, ordered by folder then filename. */
  def list: IO[List[StoredSqlFile]]

  /** List files for a specific folder. */
  def listByFolder(folder: String): IO[List[StoredSqlFile]]

  /** Upsert a batch of files (replaces all existing content). */
  def replaceAll(files: List[StoredSqlFile]): IO[Unit]

  /** Delete all stored SQL files. */
  def clear: IO[Unit]

  /** Check if any SQL files are stored. */
  def isEmpty: IO[Boolean]

  /** Convert stored files to discovery SqlFile objects. */
  def toSqlFiles: IO[List[SqlFile]] =
    list.flatMap(_.traverse(SqlFileStore.toSqlFile))

object SqlFileStore:
  def mongo(config: MongoConfig, collectionName: String): Resource[IO, SqlFileStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .evalMap { client =>
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
  override def list: IO[List[StoredSqlFile]] =
    IO.blocking {
      collection
        .find()
        .sort(Indexes.ascending("folder", "filename"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
    }

  override def listByFolder(folder: String): IO[List[StoredSqlFile]] =
    IO.blocking {
      collection
        .find(new Document("folder", folder))
        .sort(Indexes.ascending("filename"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
    }

  override def replaceAll(files: List[StoredSqlFile]): IO[Unit] =
    IO.blocking {
      val upsert = ReplaceOptions().upsert(true)
      files.foreach { file =>
        collection.replaceOne(new Document("_id", file.path), toDocument(file), upsert)
      }
      if files.isEmpty then collection.deleteMany(new Document())
      else collection.deleteMany(new Document("_id", new Document("$nin", files.map(_.path).asJava)))
      ()
    }

  override def clear: IO[Unit] =
    IO.blocking(collection.deleteMany(new Document())).void

  override def isEmpty: IO[Boolean] =
    IO.blocking(collection.countDocuments() == 0)

  private[store] def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("folder", "filename"))
    }.void

  private def toDocument(file: StoredSqlFile): Document =
    new Document()
      .append("_id", file.path)
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

private final class InMemorySqlFileStore(ref: Ref[IO, Map[String, StoredSqlFile]]) extends SqlFileStore:
  override def list: IO[List[StoredSqlFile]] =
    ref.get.map(_.values.toList.sortBy(f => (f.folder, f.filename)))

  override def listByFolder(folder: String): IO[List[StoredSqlFile]] =
    ref.get.map(_.values.toList.filter(_.folder == folder).sortBy(_.filename))

  override def replaceAll(files: List[StoredSqlFile]): IO[Unit] =
    ref.set(files.map(f => f.path -> f).toMap)

  override def clear: IO[Unit] =
    ref.set(Map.empty)

  override def isEmpty: IO[Boolean] =
    ref.get.map(_.isEmpty)

object StoredSqlFile:
  def fromBytes(
    path: String,
    folder: String,
    filename: String,
    bytes: Array[Byte],
    uploadedAt: String
  ): StoredSqlFile =
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
      .map(b => f"${b & 0xff}%02x").mkString
    val base64 = Base64.getEncoder.encodeToString(bytes)
    StoredSqlFile(
      path = path,
      folder = folder,
      filename = filename,
      contentBase64 = base64,
      sha256 = sha256,
      uploadedAt = uploadedAt
    )
