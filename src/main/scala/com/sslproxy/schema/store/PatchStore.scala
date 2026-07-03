package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, ReplaceOptions}
import com.mongodb.client.{MongoClients, MongoCollection}
import com.sslproxy.schema.config.{DbKind, MongoConfig}
import com.sslproxy.schema.discovery.{SqlFile, SqlPathNormalizer}
import com.sslproxy.schema.parser.HeaderParser
import org.bson.Document

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import scala.jdk.CollectionConverters.*

final case class PatchUpload(filename: String, bytes: Array[Byte], order: Int)
final case class PatchSqlFile(script: Script, sqlFile: SqlFile)

trait PatchStore:
  def list(targetId: Option[String]): IO[List[Patch]]
  def create(targetId: String, uploads: List[PatchUpload]): IO[Patch]
  def get(id: String): IO[Option[Patch]]
  def delete(id: String): IO[Boolean]
  def markApplied(id: String, appliedAt: String): IO[Unit]
  def markFailed(id: String): IO[Unit]
  def sqlFiles(patch: Patch, dbKind: DbKind): IO[List[PatchSqlFile]]

object PatchStore:
  private[store] val versionFormatter =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

  def mongo(config: MongoConfig, collectionName: String): Resource[IO, PatchStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .evalMap { client =>
        val store = MongoPatchStore(client.getDatabase(config.database).getCollection(collectionName))
        store.initialize.as(store: PatchStore)
      }

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

  override def create(targetId: String, uploads: List[PatchUpload]): IO[Patch] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      patch <- createWithId(id, targetId, uploads).onError { case _ => cleanupPatch(id) }
    yield patch

  override def get(id: String): IO[Option[Patch]] =
    ref.get.map(_.get(id))

  override def delete(id: String): IO[Boolean] =
    ref.get.map(_.contains(id)).flatMap {
      case false => IO.pure(false)
      case true =>
        moveStageDirAside(id).flatMap { moved =>
          deleteStageDir(moved).flatMap { _ =>
            ref.modify { values =>
              if values.contains(id) then (values - id) -> true else values -> false
            }
          }.handleErrorWith { error =>
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

  private def createWithId(id: String, targetId: String, uploads: List[PatchUpload]): IO[Patch] =
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
        applied_at = None
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
      }.handleErrorWith(_ => IO.unit)
    }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)

  private def stagedPath(patchId: String, script: Script): Path =
    stagedPath(patchId, script.order, script.filename)

  private def stagedPath(patchId: String, order: Int, filename: String): Path =
    stageDir.resolve(patchId).resolve(f"$order%04d_${PatchStore.safeName(filename)}")

private final case class StoredPatch(patch: Patch, contentByScriptId: Map[String, String])

private final class MongoPatchStore(collection: MongoCollection[Document]) extends PatchStore:
  override def list(targetId: Option[String]): IO[List[Patch]] =
    IO.blocking {
      val filter = targetId.fold(new Document())(id => new Document("target_id", id))
      collection
        .find(filter)
        .sort(Indexes.ascending("version"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument(_).patch)
    }

  override def create(targetId: String, uploads: List[PatchUpload]): IO[Patch] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- Clock[IO].realTimeInstant
      scripts = uploads.sortBy(_.order).map(upload => PatchStore.scriptFromUpload(id, upload))
      contentByScriptId = uploads.sortBy(_.order).map(upload =>
        PatchStore.scriptId(id, upload.order) -> Base64.getEncoder.encodeToString(upload.bytes)
      ).toMap
      patch = Patch(
        id = id,
        target_id = targetId,
        version = PatchStore.versionFormatter.format(now),
        label = scripts.map(_.filename).mkString(", "),
        scripts = scripts,
        status = "pending",
        applied_at = None
      )
      _ <- IO.blocking(collection.insertOne(toDocument(StoredPatch(patch, contentByScriptId)))).void
    yield patch

  override def get(id: String): IO[Option[Patch]] =
    IO.blocking(Option(collection.find(idFilter(id)).first()).map(fromDocument(_).patch))

  override def delete(id: String): IO[Boolean] =
    IO.blocking(collection.deleteOne(idFilter(id)).getDeletedCount > 0)

  override def markApplied(id: String, appliedAt: String): IO[Unit] =
    updateStored(id) { stored =>
      val scripts = stored.patch.scripts.map(script =>
        script.copy(status = "completed", duration_ms = script.duration_ms.orElse(Some(0L)))
      )
      stored.copy(patch = stored.patch.copy(status = "applied", scripts = scripts, applied_at = Some(appliedAt)))
    }

  override def markFailed(id: String): IO[Unit] =
    updateStored(id)(stored => stored.copy(patch = stored.patch.copy(status = "failed")))

  override def sqlFiles(patch: Patch, dbKind: DbKind): IO[List[PatchSqlFile]] =
    IO.blocking(Option(collection.find(idFilter(patch.id)).first()).map(fromDocument)).flatMap {
      case None => IO.raiseError(IllegalStateException(s"patch '${patch.id}' was not found"))
      case Some(stored) =>
        stored.patch.scripts.sortBy(_.order).traverse { script =>
          stored.contentByScriptId.get(script.id) match
            case None => IO.raiseError(IllegalStateException(s"patch '${patch.id}' is missing content for script '${script.id}'"))
            case Some(base64) =>
              IO.delay {
                val content = String(Base64.getDecoder.decode(base64), java.nio.charset.StandardCharsets.UTF_8)
                PatchStore.sqlFile(script, Path.of(script.filename), content, dbKind)
              }
        }
    }

  private[store] def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("target_id", "version"))
      collection.createIndex(Indexes.ascending("status"))
    }.void

  private def updateStored(id: String)(f: StoredPatch => StoredPatch): IO[Unit] =
    IO.blocking(Option(collection.find(idFilter(id)).first()).map(fromDocument)).flatMap {
      case None => IO.unit
      case Some(stored) =>
        val next = f(stored)
        IO.blocking(collection.replaceOne(idFilter(id), toDocument(next), ReplaceOptions().upsert(false))).void
    }

  private def toDocument(stored: StoredPatch): Document =
    val patch = stored.patch
    new Document()
      .append("_id", patch.id)
      .append("target_id", patch.target_id)
      .append("version", patch.version)
      .append("label", patch.label)
      .append("status", patch.status)
      .append("applied_at", patch.applied_at.orNull)
      .append(
        "scripts",
        patch.scripts.sortBy(_.order).map(scriptDocument(_, stored.contentByScriptId)).asJava
      )

  private def scriptDocument(script: Script, contentByScriptId: Map[String, String]): Document =
    val document = new Document()
      .append("id", script.id)
      .append("patch_id", script.patch_id)
      .append("order", script.order)
      .append("filename", script.filename)
      .append("checksum", script.checksum)
      .append("status", script.status)
      .append("duration_ms", script.duration_ms.map(Long.box).orNull)
      .append("content_base64", contentByScriptId.getOrElse(script.id, ""))
    script.error.foreach(error => document.append("error", scriptErrorDocument(error)))
    document

  private def scriptErrorDocument(error: ScriptError): Document =
    new Document()
      .append("db_code", error.db_code)
      .append("message", error.message)
      .append("hint", error.hint.orNull)
      .append("context", error.context.orNull)
      .append("line", error.line.map(Int.box).orNull)

  private def fromDocument(document: Document): StoredPatch =
    val scripts = scriptDocuments(document).map(scriptFromDocument)
    StoredPatch(
      patch = Patch(
        id = requiredString(document, "_id"),
        target_id = requiredString(document, "target_id"),
        version = requiredString(document, "version"),
        label = requiredString(document, "label"),
        scripts = scripts.sortBy(_.order),
        status = requiredString(document, "status"),
        applied_at = optionalString(document, "applied_at")
      ),
      contentByScriptId = scriptDocuments(document).map(doc =>
        requiredString(doc, "id") -> requiredString(doc, "content_base64")
      ).toMap
    )

  private def scriptFromDocument(document: Document): Script =
    Script(
      id = requiredString(document, "id"),
      patch_id = requiredString(document, "patch_id"),
      order = intValue(document, "order"),
      filename = requiredString(document, "filename"),
      checksum = requiredString(document, "checksum"),
      status = requiredString(document, "status"),
      error = optionalDocument(document, "error").map(scriptErrorFromDocument),
      duration_ms = optionalLong(document, "duration_ms")
    )

  private def scriptErrorFromDocument(document: Document): ScriptError =
    ScriptError(
      db_code = requiredString(document, "db_code"),
      message = requiredString(document, "message"),
      hint = optionalString(document, "hint"),
      context = optionalString(document, "context"),
      line = optionalInt(document, "line")
    )

  private def scriptDocuments(document: Document): List[Document] =
    Option(document.get("scripts")) match
      case Some(values: java.util.List[?]) =>
        values.asScala.toList.collect { case doc: Document => doc }
      case _ => Nil

  private def idFilter(id: String): Document =
    new Document("_id", id)

  private def requiredString(document: Document, field: String): String =
    optionalString(document, field)
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"patch document is missing required field '$field'"))

  private def optionalString(document: Document, field: String): Option[String] =
    Option(document.getString(field)).filter(_.nonEmpty)

  private def optionalDocument(document: Document, field: String): Option[Document] =
    Option(document.get(field)).collect { case doc: Document => doc }

  private def intValue(document: Document, field: String): Int =
    Option(document.get(field)).collect { case number: java.lang.Number => number.intValue() }.getOrElse(0)

  private def optionalInt(document: Document, field: String): Option[Int] =
    Option(document.get(field)).collect { case number: java.lang.Number => number.intValue() }

  private def optionalLong(document: Document, field: String): Option[Long] =
    Option(document.get(field)).collect { case number: java.lang.Number => number.longValue() }
