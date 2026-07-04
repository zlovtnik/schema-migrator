package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.discovery.SqlPathNormalizer
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{SqlFileStore, StoredSqlFile}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.multipart.{Multipart, Part}

object SqlFileRoutes:
  private[server] final case class UploadLimits(
    maxUploadBytes: Long,
    maxZipBytes: Long,
    maxZipEntryBytes: Long,
    maxZipUncompressedBytes: Long
  )

  private[server] object UploadLimits:
    val default: UploadLimits =
      UploadLimits(
        maxUploadBytes = 50L * 1024L * 1024L,
        maxZipBytes = 100L * 1024L * 1024L,
        maxZipEntryBytes = 50L * 1024L * 1024L,
        maxZipUncompressedBytes = 100L * 1024L * 1024L
      )

  private final class UploadTooLarge(message: String) extends IllegalArgumentException(message)
  private final class UploadRejected(message: String) extends IllegalArgumentException(message)

  def routes(sqlFileStore: SqlFileStore): HttpRoutes[IO] =
    routes(sqlFileStore, UploadLimits.default)

  private[server] def routes(sqlFileStore: SqlFileStore, limits: UploadLimits): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      // GET /sql-files — list all stored SQL files
      case GET -> Root / "sql-files" =>
        sqlFileStore.list.flatMap { files =>
          val json = files.map { f =>
            Json.obj(
              "path" -> Json.fromString(f.path),
              "folder" -> Json.fromString(f.folder),
              "filename" -> Json.fromString(f.filename),
              "sha256" -> Json.fromString(f.sha256),
              "content_base64" -> Json.fromString(f.contentBase64),
              "uploaded_at" -> Json.fromString(f.uploadedAt)
            )
          }
          RouteJson.ok(Json.obj("files" -> Json.fromValues(json)))
        }

      // GET /sql-files/status — check if files are loaded
      case GET -> Root / "sql-files" / "status" =>
        sqlFileStore.isEmpty.flatMap { empty =>
          sqlFileStore.list.flatMap { files =>
            val folders = files.map(_.folder).distinct.sorted
            RouteJson.ok(Json.obj(
              "loaded" -> Json.fromBoolean(!empty),
              "file_count" -> Json.fromInt(files.size),
              "folders" -> Json.fromValues(folders.map(Json.fromString))
            ))
          }
        }

      // POST /sql-files/upload — upload individual SQL files
      case request @ POST -> Root / "sql-files" / "upload" =>
        AuthContext.requireRole(request, UserRole.Operator) { _ =>
          val result =
            request.as[Multipart[IO]].flatMap { multipart =>
              fileUploads(multipart, limits).flatMap {
                case Nil =>
                  RouteJson.badRequest("No .sql files found in upload")
                case files =>
                  val now = java.time.Clock.systemUTC.instant().toString
                  val stored = files.map { upload =>
                    StoredSqlFile.fromBytes(
                      path = upload.path,
                      folder = upload.folder,
                      filename = upload.filename,
                      bytes = upload.bytes,
                      uploadedAt = now
                    )
                  }
                  sqlFileStore.replaceAll(stored) *>
                    IO.println(s"Uploaded ${stored.size} SQL files to store") *>
                    RouteJson.created(Json.obj(
                      "uploaded" -> Json.fromInt(stored.size),
                      "folders" -> Json.fromValues(stored.map(_.folder).distinct.sorted.map(Json.fromString))
                    ))
              }
            }
          result.handleErrorWith {
            case error: UploadTooLarge => RouteJson.payloadTooLarge(error.getMessage)
            case error: UploadRejected => RouteJson.badRequest(error.getMessage)
          }
        }

      // POST /sql-files/upload-zip — upload a zip containing SQL directory tree
      case request @ POST -> Root / "sql-files" / "upload-zip" =>
        AuthContext.requireRole(request, UserRole.Operator) { _ =>
          val result =
            request
              .as[Multipart[IO]]
              .flatMap { multipart =>
                zipPart(multipart, limits).flatMap {
                  case None => RouteJson.badRequest("A zip file part named 'file' is required")
                  case Some(zipBytes) =>
                    extractZip(zipBytes, limits).flatMap { extracted =>
                      if extracted.isEmpty then
                        RouteJson.badRequest("No .sql files found in zip archive")
                      else
                        val now = java.time.Clock.systemUTC.instant().toString
                        val stored = extracted.map { item =>
                          StoredSqlFile.fromBytes(
                            path = item.path,
                            folder = item.folder,
                            filename = item.filename,
                            bytes = item.bytes,
                            uploadedAt = now
                          )
                        }
                        sqlFileStore.replaceAll(stored) *>
                          IO.println(s"Extracted ${stored.size} SQL files from zip") *>
                          RouteJson.created(Json.obj(
                            "uploaded" -> Json.fromInt(stored.size),
                            "folders" -> Json.fromValues(stored.map(_.folder).distinct.sorted.map(Json.fromString))
                          ))
                    }
                }
              }
          result.handleErrorWith { case error: UploadTooLarge =>
            RouteJson.payloadTooLarge(error.getMessage)
          }
        }

      // DELETE /sql-files — clear all stored SQL files
      case request @ DELETE -> Root / "sql-files" =>
        AuthContext.requireRole(request, UserRole.Operator) { _ =>
          sqlFileStore.clear *> NoContent()
        }
    }

  private final case class FileUpload(
    path: String,
    folder: String,
    filename: String,
    bytes: Array[Byte]
  )

  private final case class ZipEntry(
    path: String,
    folder: String,
    filename: String,
    bytes: Array[Byte]
  )

  private def fileUploads(multipart: Multipart[IO], limits: UploadLimits): IO[List[FileUpload]] =
    multipart.parts
      .filter(_.name.exists(_.split("/").contains("files")))
      .zipWithIndex
      .toList
      .traverse { case (part, index) =>
        for
          folder <- IO.fromOption(part.name.flatMap(n => extractFolderFromName(n)))(UploadTooLarge("folder is required"))
          bytes <- part.body.take(limits.maxUploadBytes + 1).compile.toVector
          _ <- IO.raiseWhen(bytes.length.toLong > limits.maxUploadBytes)(
            UploadTooLarge(s"uploaded file exceeds ${limitLabel(limits.maxUploadBytes)} limit")
          )
          rawFilename <- IO.fromEither(sqlFilename(part.filename))
          filename = safeFilename(Some(rawFilename), index)
          normalized = SqlPathNormalizer.normalizeUploadPath(s"$folder/$filename")
        yield FileUpload(normalized.path, normalized.folder, normalized.filename, bytes.toArray)
      }

  private def sqlFilename(filename: Option[String]): Either[Throwable, String] =
    filename
      .map(_.trim)
      .filter(_.nonEmpty)
      .filter(_.toLowerCase.endsWith(".sql"))
      .toRight(UploadRejected("Only .sql file uploads are accepted"))

  private def zipPart(multipart: Multipart[IO], limits: UploadLimits): IO[Option[Array[Byte]]] =
    multipart.parts
      .find(_.name.contains("file"))
      .traverse { part =>
        part.body.take(limits.maxZipBytes + 1).compile.toVector.flatMap { bytes =>
          if bytes.length.toLong > limits.maxZipBytes then
            IO.raiseError(UploadTooLarge(s"uploaded zip exceeds ${limitLabel(limits.maxZipBytes)} limit"))
          else
            IO.pure(bytes.toArray)
        }
      }

  private def extractZip(zipBytes: Array[Byte], limits: UploadLimits): IO[List[ZipEntry]] =
    IO.blocking {
      val stream = new java.io.ByteArrayInputStream(zipBytes)
      val zipIn = new java.util.zip.ZipInputStream(stream)
      try
        val entries = List.newBuilder[ZipEntry]
        var totalUncompressedBytes = 0L
        var entry = zipIn.getNextEntry
        while entry != null do
          if !entry.isDirectory && entry.getName.toLowerCase.endsWith(".sql") then
            val name = entry.getName
            // Normalize path separators
            val normalized = name.replace('\\', '/')
            // Read entry bytes
            val buf = new java.io.ByteArrayOutputStream()
            val buffer = new Array[Byte](8192)
            var entryUncompressedBytes = 0L
            var len = zipIn.read(buffer)
            while len >= 0 do
              entryUncompressedBytes += len.toLong
              totalUncompressedBytes += len.toLong
              if entryUncompressedBytes > limits.maxZipEntryBytes then
                throw UploadTooLarge(
                  s"zip entry '$normalized' exceeds ${limitLabel(limits.maxZipEntryBytes)} uncompressed limit"
                )
              if totalUncompressedBytes > limits.maxZipUncompressedBytes then
                throw UploadTooLarge(
                  s"zip archive exceeds ${limitLabel(limits.maxZipUncompressedBytes)} uncompressed SQL limit"
                )
              buf.write(buffer, 0, len)
              len = zipIn.read(buffer)
            val bytes = buf.toByteArray
            // Strip the selected sql/ root if present and keep dialect subfolders explicit.
            val normalizedPath = SqlPathNormalizer.normalizeUploadPath(normalized)
            entries += ZipEntry(normalizedPath.path, normalizedPath.folder, normalizedPath.filename, bytes)
          entry = zipIn.getNextEntry
        entries.result()
      finally zipIn.close()
    }

  private def limitLabel(bytes: Long): String =
    val mib = 1024L * 1024L
    if bytes % mib == 0 then s"${bytes / mib} MiB" else s"$bytes bytes"

  private def extractFolderFromName(name: String): Option[String] =
    val parts = name.split("/").toList
    parts match
      case folder :: _ :: Nil => Some(folder) // "foldername/filename.ext"
      case folder :: _ => Some(folder)
      case _ => None

  private def safeFilename(filename: Option[String], order: Int): String =
    filename
      .flatMap { value =>
        value.replace('\\', '/').split('/').toList.lastOption.map(_.trim)
      }
      .map(_.replaceAll("[^A-Za-z0-9._-]", "_"))
      .filter(name => name.nonEmpty && name != "." && name != "..")
      .getOrElse(s"script-$order.sql")
