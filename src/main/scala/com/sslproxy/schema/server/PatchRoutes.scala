package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.discovery.DiscoveryService
import com.sslproxy.schema.store.{
  AuditStore,
  CreatePatchFromSqlFilesPayload,
  Models,
  PatchStore,
  PatchUpload,
  SqlFileStore,
  TargetStore
}
import fs2.text
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.multipart.{Multipart, Part}

import java.util.Base64

object PatchRoutes:
  import Models.given

  private val maxUploadBytes = 10L * 1024L * 1024L
  private final class UploadTooLarge(message: String) extends IllegalArgumentException(message)

  def routes(
    targetStore: TargetStore,
    patchStore: PatchStore,
    sqlFileStore: SqlFileStore,
    auditStore: AuditStore
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ GET -> Root / "patches" =>
        val targetId = request.uri.query.params.get("target_id").filter(_.nonEmpty)
        patchStore.list(targetId).flatMap(patches => RouteJson.ok(Json.obj("patches" -> patches.asJson)))

      case request @ POST -> Root / "patches" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          request
            .as[Multipart[IO]]
            .flatMap { multipart =>
              for
                targetId <- fieldValue(multipart, "target_id")
                response <-
                  targetId match
                    case None => RouteJson.badRequest("target_id is required")
                    case Some(value) =>
                      targetStore.get(value).flatMap {
                        case None => RouteJson.notFound(s"target '$value' was not found")
                        case Some(_) =>
                          fileUploads(multipart).flatMap { uploads =>
                            if uploads.isEmpty then RouteJson.badRequest("at least one SQL file is required")
                            else
                              patchStore.create(value, uploads).flatMap { patch =>
                                auditStore.record(
                                  claims.subject,
                                  claims.role,
                                  "patch.upload",
                                  "patch",
                                  patch.id,
                                  Some(value),
                                  Some(Json.obj("script_count" -> Json.fromInt(patch.scripts.length)))
                                ) *> RouteJson.created(patch.asJson)
                              }
                          }
                      }
              yield response
            }
            .recoverWith { case error: UploadTooLarge =>
              RouteJson.payloadTooLarge(error.getMessage)
            }
        }

      case request @ POST -> Root / "patches" / "from-sql-files" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          RouteJson.withJson[CreatePatchFromSqlFilesPayload](request, "invalid patch payload") { payload =>
            targetStore.get(payload.target_id).flatMap {
              case None => RouteJson.notFound(s"target '${payload.target_id}' was not found")
              case Some(target) =>
                TargetDatabase.dbKindFor(target.jdbc_url) match
                  case Left(message) => RouteJson.badRequest(message)
                  case Right(dbKind) =>
                    patchUploadsFromSqlFiles(payload, sqlFileStore, dbKind).flatMap {
                      case Left(message) => RouteJson.badRequest(message)
                      case Right(uploads) =>
                        patchStore.create(payload.target_id, uploads).flatMap { patch =>
                          auditStore.record(
                            claims.subject,
                            claims.role,
                            "patch.create_from_sql_files",
                            "patch",
                            patch.id,
                            Some(payload.target_id),
                            Some(
                              Json.obj(
                                "script_count" -> Json.fromInt(patch.scripts.length),
                                "source_files" -> payload.source_files.asJson
                              )
                            )
                          ) *> RouteJson.created(patch.asJson)
                        }
                    }
            }
          }
        }

      case GET -> Root / "patches" / id =>
        patchStore.get(id).flatMap {
          case Some(patch) => RouteJson.ok(patch.asJson)
          case None => RouteJson.notFound(s"patch '$id' was not found")
        }

      case request @ DELETE -> Root / "patches" / id =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          patchStore.get(id).flatMap {
            case None => RouteJson.notFound(s"patch '$id' was not found")
            case Some(patch) if patch.status != "pending" =>
              RouteJson.conflict(s"patch '$id' is not pending and cannot be deleted")
            case Some(patch) =>
              patchStore.delete(id).flatMap {
                case true =>
                  auditStore
                    .record(claims.subject, claims.role, "patch.delete", "patch", id, Some(patch.target_id))
                    .void *>
                    NoContent()
                case false => RouteJson.notFound(s"patch '$id' was not found")
              }
          }
        }
    }

  private def patchUploadsFromSqlFiles(
    payload: CreatePatchFromSqlFilesPayload,
    sqlFileStore: SqlFileStore,
    dbKind: com.sslproxy.schema.config.DbKind
  ): IO[Either[String, List[PatchUpload]]] =
    val selected = payload.source_files.map(_.trim).filter(_.nonEmpty).distinct
    if selected.isEmpty then IO.pure(Left("at least one source file is required"))
    else
      sqlFileStore.list(payload.target_id).map { storedFiles =>
        val byPath = storedFiles.map(file => file.path -> file).toMap
        val missing = selected.filterNot(byPath.contains)
        if missing.nonEmpty then Left(s"source_files were not found: ${missing.mkString(", ")}")
        else
          val selectedSet = selected.toSet
          val selectedFiles = storedFiles.filter(file => selectedSet.contains(file.path))
          val discovery = DiscoveryService[IO]().discoverFromFiles(selectedFiles.map(SqlFileStore.toSqlFileUnsafe), dbKind)
          val orderedStored = discovery.files.flatMap(file => byPath.get(file.relativePath))
          val fallbackStored = selectedFiles.filterNot(file => orderedStored.exists(_.path == file.path)).sortBy(_.path)
          Right((orderedStored ++ fallbackStored).zipWithIndex.map { case (file, index) =>
            PatchUpload(file.path, Base64.getDecoder.decode(file.contentBase64), index + 1)
          })
      }

  private def fieldValue(multipart: Multipart[IO], name: String): IO[Option[String]] =
    multipart.parts
      .find(_.name.contains(name))
      .traverse(part => part.body.through(text.utf8.decode).compile.string.map(_.trim))
      .map(_.filter(_.nonEmpty))

  private def fileUploads(multipart: Multipart[IO]): IO[List[PatchUpload]] =
    multipart.parts
      .filter(_.name.contains("files"))
      .zipWithIndex
      .traverse { case (part, index) => readUpload(part, index + 1) }
      .map(_.toList)

  private def readUpload(part: Part[IO], order: Int): IO[PatchUpload] =
    part.body.take(maxUploadBytes + 1).compile.toVector.flatMap { bytes =>
      if bytes.length.toLong > maxUploadBytes then
        IO.raiseError(UploadTooLarge("uploaded SQL file exceeds 10 MiB limit"))
      else
        IO.pure(
          PatchUpload(
            filename = safeFilename(part.filename, order),
            bytes = bytes.toArray,
            order = order
          )
        )
    }

  private def safeFilename(filename: Option[String], order: Int): String =
    filename
      .flatMap { value =>
        value.replace('\\', '/').split('/').toList.lastOption.map(_.trim)
      }
      .map(_.replaceAll("[^A-Za-z0-9._-]", "_"))
      .filter(name => name.nonEmpty && name != "." && name != "..")
      .getOrElse(s"script-$order.sql")
