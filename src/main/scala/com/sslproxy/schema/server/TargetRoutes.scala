package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.ServerConfig
import com.sslproxy.schema.db.postgres.PostgresProvider
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{AuditStore, Models, PatchStore, RunStore, TargetPayload, TargetStore, ValidationStore}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.URI
import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
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
    validationStore: ValidationStore,
    auditStore: AuditStore
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "targets" =>
        store.list.flatMap(targets => RouteJson.ok(Json.obj("targets" -> targets.asJson)))

      case request @ POST -> Root / "targets" =>
        AuthContext.requireRole(request, UserRole.Admin) { claims =>
          withTargetPayload(request) { payload =>
            for
              target <- store.create(payload)
              _ <- auditStore.record(
                claims.subject,
                claims.role,
                "target.create",
                "target",
                target.id,
                Some(target.id),
                Some(Json.obj("label" -> Json.fromString(target.label), "env" -> Json.fromString(target.env)))
              )
              response <- RouteJson.created(target.asJson)
            yield response
          }
        }

      case request @ POST -> Root / "targets" / "test" =>
        AuthContext.requireRole(request, UserRole.Admin) { _ =>
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
        }

      case GET -> Root / "targets" / id =>
        store.get(id).flatMap {
          case Some(target) => RouteJson.ok(target.asJson)
          case None => RouteJson.notFound(s"target '$id' was not found")
        }

      case request @ PUT -> Root / "targets" / id =>
        AuthContext.requireRole(request, UserRole.Admin) { claims =>
          withTargetPayload(request) { payload =>
            store.update(id, payload).flatMap {
              case Some(target) =>
                auditStore.record(
                  claims.subject,
                  claims.role,
                  "target.update",
                  "target",
                  target.id,
                  Some(target.id),
                  Some(Json.obj("label" -> Json.fromString(target.label), "env" -> Json.fromString(target.env)))
                ) *> RouteJson.ok(target.asJson)
              case None => RouteJson.notFound(s"target '$id' was not found")
            }
          }
        }

      case request @ DELETE -> Root / "targets" / id =>
        AuthContext.requireRole(request, UserRole.Admin) { claims =>
          targetDeleteBlocker(id, patchStore, runStore, validationStore).flatMap {
            case Some(message) => RouteJson.conflict(message)
            case None =>
              store.delete(id).flatMap {
                case true =>
                  auditStore.record(claims.subject, claims.role, "target.delete", "target", id, Some(id)).void *> NoContent()
                case false => RouteJson.notFound(s"target '$id' was not found")
              }
          }
        }

      case request @ POST -> Root / "targets" / id / "test" =>
        AuthContext.requireRole(request, UserRole.Admin) { _ =>
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
    }

  private def withTargetPayload(request: Request[IO])(use: TargetPayload => IO[Response[IO]]): IO[Response[IO]] =
    request.as[TargetPayload].attempt.flatMap {
      case Right(payload) =>
        validateTargetPayload(payload).fold(RouteJson.badRequest, use)
      case Left(_) => RouteJson.badRequest(invalidPayloadMessage)
    }

  private[server] def validateTargetPayload(payload: TargetPayload): Either[String, TargetPayload] =
    val jdbcUrl = payload.jdbc_url.trim
    val repoUrl = payload.repo_url.trim
    val repoBranch = Option(payload.repo_branch.trim).filter(_.nonEmpty).getOrElse("main")
    val repoSqlPath = Option(payload.repo_sql_path.trim).filter(_.nonEmpty).getOrElse("sql")
    val trimmedPayload = payload.copy(
      jdbc_url = jdbcUrl,
      repo_url = repoUrl,
      repo_branch = repoBranch,
      repo_sql_path = repoSqlPath
    )
    if jdbcUrl.isEmpty then Left("Database URL is required")
    else if repoUrl.isEmpty then Left("Repository URL is required")
    else if !repoUrl.startsWith("https://") then Left("Repository URL must start with https://")
    else if repoSqlPath.startsWith("/") || repoSqlPath.contains("..") || repoSqlPath.contains("\\") then
      Left("Repository SQL path must be a relative path inside the repository")
    else
      for
        _ <- TargetPayload.rejectInlineRepoCredentials(repoUrl)
        validated <-
          if isPostgresUrl(jdbcUrl) then normalizePostgresPayload(trimmedPayload, jdbcUrl)
          else if jdbcUrl.startsWith("jdbc:postgres://") then
            Left(
              "Postgres JDBC URLs must start with jdbc:postgresql://, for example jdbc:postgresql://host:5432/database?user=username"
            )
          else if isSupportedJdbcUrl(jdbcUrl) then TargetPayload.rejectInlineCredentials(jdbcUrl).as(trimmedPayload)
          else
            Left("unsupported database URL: expected postgres://..., postgresql://..., jdbc:postgresql://..., or jdbc:oracle:thin:...")
      yield validated

  private def normalizePostgresPayload(payload: TargetPayload, rawUrl: String): Either[String, TargetPayload] =
    for
      _ <-
        if rawUrl.startsWith("jdbc:postgresql:") then TargetPayload.rejectInlineCredentials(rawUrl)
        else Right(())
      config <- PostgresProvider.normalize(rawUrl)
      _ <- TargetPayload.rejectInlineCredentials(config.url)
      jdbcUrl <- config.user.fold(Right(config.url))(user => appendUserParameter(config.url, user))
      password <- mergedPassword(payload.password, config.password)
    yield payload.copy(jdbc_url = jdbcUrl, password = password)

  private def mergedPassword(formPassword: Option[String], urlPassword: Option[String]): Either[String, Option[String]] =
    val provided = formPassword.filter(_.nonEmpty)
    (provided, urlPassword) match
      case (Some(value), Some(parsed)) if value != parsed =>
        Left("Postgres URL password does not match the password field")
      case (Some(value), _) => Right(Some(value))
      case (None, parsed) => Right(parsed)

  private def isSupportedJdbcUrl(value: String): Boolean =
    value.startsWith("jdbc:postgresql:") || value.startsWith("jdbc:oracle:thin:")

  private def isPostgresUrl(value: String): Boolean =
    value.startsWith("postgres://") || value.startsWith("postgresql://") || value.startsWith("jdbc:postgresql://")

  private def appendUserParameter(jdbcUrl: String, user: String): Either[String, String] =
    queryParameter(jdbcUrl, "user") match
      case Some(existing) if decode(existing) == user => Right(jdbcUrl)
      case Some(_) => Left("Postgres URL has conflicting usernames in userinfo and query string")
      case None =>
        val separator = if jdbcUrl.contains("?") then "&" else "?"
        Right(s"$jdbcUrl${separator}user=${encode(user)}")

  private def queryParameter(url: String, name: String): Option[String] =
    val queryStart = url.indexOf('?')
    if queryStart < 0 || queryStart == url.length - 1 then None
    else
      url
        .substring(queryStart + 1)
        .split("&")
        .toList
        .collectFirst {
          case part if part.takeWhile(_ != '=') == name && part.contains("=") =>
            part.dropWhile(_ != '=').drop(1)
        }

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def withDbTestAllowed(config: ServerConfig, jdbcUrl: String)(use: => IO[Response[IO]]): IO[Response[IO]] =
    withDbAccessAllowed(config, jdbcUrl, "database connection tests are not allowed for this target")(use)

  private[server] def withDbAccessAllowed(
    config: ServerConfig,
    jdbcUrl: String,
    forbiddenMessage: String
  )(use: => IO[Response[IO]]): IO[Response[IO]] =
    if config.dbTestAllowedHosts.contains("*") then use
    else
      jdbcHost(jdbcUrl).map(_.toLowerCase(Locale.ROOT)) match
        case Some(host) if config.dbTestAllowedHosts.contains(host) => use
        case _ => RouteJson.forbidden(forbiddenMessage)

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

  private[server] def jdbcHost(jdbcUrl: String): Option[String] =
    val value = jdbcUrl.trim
    if value.startsWith("jdbc:postgresql://") then uriHost(value.stripPrefix("jdbc:"))
    else if value.startsWith("jdbc:oracle:thin:@//") then uriHost("oracle:" + value.stripPrefix("jdbc:oracle:thin:@"))
    else oracleDescriptorHost(value)

  private def uriHost(value: String): Option[String] =
    Either.catchNonFatal(URI.create(value).getHost).toOption.flatMap(Option(_)).filter(_.nonEmpty)

  private def oracleDescriptorHost(value: String): Option[String] =
    raw"(?i)\bhost\s*=\s*([^)]+)".r
      .findAllMatchIn(value)
      .map(_.group(1).trim)
      .filter(_.nonEmpty)
      .toList match
      case host :: Nil => Some(host)
      case _ => None

  private val invalidPayloadMessage: String =
    "invalid target payload: expected label, app_name, env, jdbc_url, repo_url, repo_branch, repo_sql_path, and optional password fields"
