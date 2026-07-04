package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{AuditStore, Models, PatchStore, PatchUpload, TargetStore}
import fs2.text
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.multipart.{Multipart, Part}

object PatchRoutes:
  import Models.given

  private val maxUploadBytes = 10L * 1024L * 1024L
  private final class UploadTooLarge(message: String) extends IllegalArgumentException(message)

  def routes(targetStore: TargetStore, patchStore: PatchStore, auditStore: AuditStore): HttpRoutes[IO] =
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
                  auditStore.record(claims.subject, claims.role, "patch.delete", "patch", id, Some(patch.target_id)).void *>
                    NoContent()
                case false => RouteJson.notFound(s"patch '$id' was not found")
              }
          }
        }
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
