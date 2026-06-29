package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.ServerConfig
import com.sslproxy.schema.store.{Models, PatchStore, RunStore, TargetPayload, TargetStore, ValidationStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.URI
import java.util.Locale

object TargetRoutes:
  import Models.given

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val log = LoggerFactory[IO].getLogger

  def routes(
    config: ServerConfig,
    store: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "targets" =>
        store.list.flatMap(targets => RouteJson.ok(Json.obj("targets" -> targets.asJson)))

      case request @ POST -> Root / "targets" =>
        withTargetPayload(request) { payload =>
          store.create(payload).flatMap(target => RouteJson.created(target.asJson))
        }

      case request @ POST -> Root / "targets" / "test" =>
        withTargetPayload(request) { payload =>
          withDbTestAllowed(config, payload.jdbc_url) {
            for
              _ <- log.info(
                Json
                  .obj(
                    "event" -> Json.fromString("connection_test_requested"),
                    "host" -> Json.fromString(jdbcHost(payload.jdbc_url).getOrElse("unknown"))
                  )
                  .noSpaces
              )
              result <- DbPing.test(payload)
              response <- RouteJson.ok(result.asJson)
            yield response
          }
        }

      case GET -> Root / "targets" / id =>
        store.get(id).flatMap {
          case Some(target) => RouteJson.ok(target.asJson)
          case None => RouteJson.notFound(s"target '$id' was not found")
        }

      case request @ PUT -> Root / "targets" / id =>
        withTargetPayload(request) { payload =>
          store.update(id, payload).flatMap {
            case Some(target) => RouteJson.ok(target.asJson)
            case None => RouteJson.notFound(s"target '$id' was not found")
          }
        }

      case DELETE -> Root / "targets" / id =>
        targetDeleteBlocker(id, patchStore, runStore, validationStore).flatMap {
          case Some(message) => RouteJson.conflict(message)
          case None =>
            store.delete(id).flatMap {
              case true => NoContent()
              case false => RouteJson.notFound(s"target '$id' was not found")
            }
        }

      case POST -> Root / "targets" / id / "test" =>
        store.getStored(id).flatMap {
          case Some(target) =>
            withDbTestAllowed(config, target.target.jdbc_url) {
              for
                _ <- log.info(
                  Json
                    .obj(
                      "event" -> Json.fromString("stored_connection_test_requested"),
                      "target_id" -> Json.fromString(id)
                    )
                    .noSpaces
                )
                result <- DbPing.test(target)
                response <- RouteJson.ok(result.asJson)
              yield response
            }
          case None =>
            for
              _ <- log.info(
                Json
                  .obj(
                    "event" -> Json.fromString("stored_connection_test_not_found"),
                    "target_id" -> Json.fromString(id)
                  )
                  .noSpaces
              )
              response <- RouteJson.notFound(s"target '$id' was not found")
            yield response
        }
    }

  private def withTargetPayload(request: Request[IO])(use: TargetPayload => IO[Response[IO]]): IO[Response[IO]] =
    request.as[TargetPayload].attempt.flatMap {
      case Right(payload) =>
        validateTargetPayload(payload).fold(RouteJson.badRequest, use)
      case Left(_) => RouteJson.badRequest(invalidPayloadMessage)
    }

  private def validateTargetPayload(payload: TargetPayload): Either[String, TargetPayload] =
    val jdbcUrl = payload.jdbc_url.trim
    if jdbcUrl.isEmpty then Left("JDBC URL is required")
    else if jdbcUrl.startsWith("jdbc:postgres://") then
      Left(
        "Postgres JDBC URLs must start with jdbc:postgresql://, for example jdbc:postgresql://host:5432/database?user=username"
      )
    else if isSupportedJdbcUrl(jdbcUrl) then Right(payload.copy(jdbc_url = jdbcUrl))
    else Left("unsupported JDBC URL: expected jdbc:postgresql://... or jdbc:oracle:thin:...")

  private def isSupportedJdbcUrl(value: String): Boolean =
    value.startsWith("jdbc:postgresql:") || value.startsWith("jdbc:oracle:thin:")

  private def withDbTestAllowed(config: ServerConfig, jdbcUrl: String)(use: => IO[Response[IO]]): IO[Response[IO]] =
    jdbcHost(jdbcUrl).map(_.toLowerCase(Locale.ROOT)) match
      case Some(host) if config.dbTestAllowedHosts.contains("*") || config.dbTestAllowedHosts.contains(host) => use
      case _ => RouteJson.forbidden("database connection tests are not allowed for this target")

  private def targetDeleteBlocker(
    id: String,
    patchStore: PatchStore,
    runStore: RunStore,
    validationStore: ValidationStore
  ): IO[Option[String]] =
    (patchStore.list(Some(id)), runStore.list(Some(id)), validationStore.list(Some(id))).tupled.map {
      case (patches, _, _) if patches.nonEmpty =>
        Some(s"target '$id' has patch records and cannot be deleted")
      case (_, runs, _) if runs.nonEmpty =>
        Some(s"target '$id' has run records and cannot be deleted")
      case (_, _, validations) if validations.nonEmpty =>
        Some(s"target '$id' has validation records and cannot be deleted")
      case _ => None
    }

  private def jdbcHost(jdbcUrl: String): Option[String] =
    val value = jdbcUrl.trim
    if value.startsWith("jdbc:postgresql://") then uriHost(value.stripPrefix("jdbc:"))
    else if value.startsWith("jdbc:oracle:thin:@//") then uriHost("oracle:" + value.stripPrefix("jdbc:oracle:thin:@"))
    else oracleDescriptorHost(value)

  private def uriHost(value: String): Option[String] =
    Either.catchNonFatal(URI.create(value).getHost).toOption.flatMap(Option(_)).filter(_.nonEmpty)

  private def oracleDescriptorHost(value: String): Option[String] =
    raw"(?i)\bhost\s*=\s*([^)]+)".r.findFirstMatchIn(value).map(_.group(1).trim).filter(_.nonEmpty)

  private val invalidPayloadMessage: String =
    "invalid target payload: expected label, app_name, env, jdbc_url, and optional password fields"
