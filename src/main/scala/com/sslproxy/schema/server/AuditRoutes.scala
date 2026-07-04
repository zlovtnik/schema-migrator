package com.sslproxy.schema.server

import cats.effect.IO
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{AuditFilters, AuditStore, Models}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

object AuditRoutes:
  import Models.given

  def routes(auditStore: AuditStore): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case request @ GET -> Root / "audit" =>
      AuthContext.requireRole(request, UserRole.Admin) { _ =>
        val params = request.uri.query.params
        val filters = AuditFilters(
          actor = params.get("actor").filter(_.nonEmpty),
          entityType = params.get("entity_type").filter(_.nonEmpty),
          entityId = params.get("entity_id").filter(_.nonEmpty),
          targetId = params.get("target_id").filter(_.nonEmpty),
          limit = AuditStore.boundedLimit(params.get("limit"))
        )
        auditStore.list(filters).flatMap(events => RouteJson.ok(Json.obj("events" -> events.asJson)))
      }
    }
