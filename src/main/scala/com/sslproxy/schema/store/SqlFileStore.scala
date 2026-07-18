package com.sslproxy.schema.store

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.sslproxy.schema.discovery.SqlFile

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

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
  def inMemory: IO[SqlFileStore] =
    Ref.of[IO, Map[String, StoredSqlFile]](Map.empty).map(InMemorySqlFileStore(_))

  def toSqlFileUnsafe(stored: StoredSqlFile): SqlFile =
    val bytes = Base64.getDecoder.decode(stored.contentBase64)
    val virtualPath = Path.of(stored.path)
    SqlFile(
      folder = stored.folder,
      path = virtualPath,
      name = stored.filename,
      relativePath = stored.path,
      content = Some(new String(bytes, StandardCharsets.UTF_8))
    )

  private def toSqlFile(stored: StoredSqlFile): IO[SqlFile] =
    IO.delay {
      toSqlFileUnsafe(stored)
    }

private final class InMemorySqlFileStore(ref: Ref[IO, Map[String, StoredSqlFile]]) extends SqlFileStore:
  override def list(targetId: String): IO[List[StoredSqlFile]] =
    ref.get.map(
      _.filter { case (key, _) => key.startsWith(s"$targetId:") }.values.toList.sortBy(f => (f.folder, f.filename))
    )

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

