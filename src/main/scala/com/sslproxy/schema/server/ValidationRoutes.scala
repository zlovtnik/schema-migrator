package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{AuditStore, Models, PatchStore, RunStore, TargetStore, ValidationStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

object ValidationRoutes:
  import Models.given

  def routes(
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore,
    auditStore: AuditStore
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "validation" / runId =>
        validationStore.get(runId).flatMap {
          case Some(result) => RouteJson.ok(result.asJson)
          case None => RouteJson.notFound(s"validation for run '$runId' was not found")
        }

      case request @ POST -> Root / "validation" / runId / "rerun" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          runStore.get(runId).flatMap {
            case Some(run) =>
              (targetStore.get(run.target_id), patchStore.get(run.patch_id)).tupled.flatMap {
                case (None, _) => RouteJson.notFound(s"target '${run.target_id}' was not found")
                case (_, None) => RouteJson.notFound(s"patch '${run.patch_id}' was not found")
                case (Some(target), Some(patch)) =>
                  TargetDatabase.dbKindFor(target.jdbc_url) match
                    case Left(message) => RouteJson.badRequest(message)
                    case Right(dbKind) =>
                      validationStore.validateRun(run, patch, patchStore, dbKind).flatMap { result =>
                        auditStore.record(
                          claims.subject,
                          claims.role,
                          "validation.rerun",
                          "validation",
                          runId,
                          Some(run.target_id),
                          Some(Json.obj("status" -> Json.fromString(result.status)))
                        ) *> RouteJson.ok(result.asJson)
                      }
              }
            case None => RouteJson.notFound(s"run '$runId' was not found")
            }
        }
    }
