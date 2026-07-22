package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.{DiscoveryService, SqlFile}
import com.sslproxy.schema.engine.MigrationPlan
import com.sslproxy.schema.validation.ValidationReport
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{
  AuditStore,
  Models,
  PatchStore,
  RunStore,
  SqlFileStore,
  SqlFilesValidationResult,
  TargetStore,
  ValidationStore
}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.nio.file.{Files, InvalidPathException, Path}

object ValidationRoutes:
  import Models.given

  def routes(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    sqlFileStore: SqlFileStore,
    validationStore: ValidationStore,
    auditStore: AuditStore
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "validation" / runId =>
        validationStore.get(runId).flatMap {
          case Some(result) => RouteJson.ok(result.asJson)
          case None => RouteJson.notFound(s"validation for run '$runId' was not found")
        }

      case request @ GET -> Root / "validate" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          val payload = ValidateSqlPayload(
            sql_dir = request.uri.query.params.getOrElse("sql_dir", config.sqlDir.toString),
            db_kind = request.uri.query.params.getOrElse("db_kind", config.dbKind.toString.toLowerCase),
            customer = request.uri.query.params.get("customer").filter(_.nonEmpty)
          )
          validateSqlDirWithinConfiguredRoot(payload.sql_dir, config.sqlDir) match
            case Left(message) => RouteJson.badRequest(message)
            case Right(()) => validateSqlDirectory(payload, auditStore, claims)
        }

      case request @ POST -> Root / "validate" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          RouteJson.withJson[ValidateSqlPayload](request, "invalid validation payload") { payload =>
            validateSqlDirWithinConfiguredRoot(payload.sql_dir, config.sqlDir) match
              case Left(message) => RouteJson.badRequest(message)
              case Right(()) => validateSqlDirectory(payload, auditStore, claims)
          }
        }

      case request @ POST -> Root / "validation" / "sql-files" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          RouteJson.withJson[SqlFilesValidationPayload](request, "invalid validation payload") { payload =>
            targetStore.get(payload.target_id).flatMap {
              case None => RouteJson.notFound(s"target '${payload.target_id}' was not found")
              case Some(target) =>
                TargetDatabase.dbKindFor(target.jdbc_url) match
                  case Left(message) => RouteJson.badRequest(message)
                  case Right(dbKind) =>
                    sqlFileStore.toSqlFiles(payload.target_id).flatMap {
                      case Nil => RouteJson.badRequest("no synced SQL files are available for validation")
                      case files =>
                        selectSourceFiles(payload.source_files, files) match
                          case Left(message) => RouteJson.badRequest(message)
                          case Right(selectedFiles) =>
                            val discovery = DiscoveryService().discoverFromFiles(selectedFiles, dbKind)
                            MigrationPlan.inspect(dbKind, SqlDialect.forDbKind(dbKind), discovery).flatMap { plan =>
                              for
                                checkedAt <- Clock[IO].realTimeInstant.map(_.toString)
                                result = sqlFilesValidationResult(
                                  payload.target_id,
                                  dbKind.toString.toLowerCase,
                                  checkedAt,
                                  discovery.files.length,
                                  plan.validation
                                )
                                _ <- auditStore.record(
                                  claims.subject,
                                  claims.role,
                                  "validation.sql_files",
                                  "validation",
                                  payload.target_id,
                                  Some(payload.target_id),
                                  Some(Json.obj("status" -> Json.fromString(result.status)))
                                )
                                response <- RouteJson.ok(result.asJson)
                              yield response
                            }
                    }
            }
          }
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

  final case class SqlFilesValidationPayload(target_id: String, source_files: Option[List[String]])
  final case class ValidateSqlPayload(sql_dir: String, db_kind: String, customer: Option[String])

  private given io.circe.Decoder[SqlFilesValidationPayload] = io.circe.generic.semiauto.deriveDecoder
  private given io.circe.Decoder[ValidateSqlPayload] = io.circe.generic.semiauto.deriveDecoder

  private def selectSourceFiles(requested: Option[List[String]], files: List[SqlFile]): Either[String, List[SqlFile]] =
    requested match
      case None => Right(files)
      case Some(sourceFiles) =>
        val selected = sourceFiles.map(_.trim).filter(_.nonEmpty).distinct
        if selected.isEmpty then Left("at least one source file is required")
        else
          val available = files.map(file => file.relativePath -> file).toMap
          val missing = selected.filterNot(available.contains)
          if missing.nonEmpty then Left(s"source_files were not found: ${missing.mkString(", ")}")
          else
            val selectedSet = selected.toSet
            Right(files.filter(file => selectedSet.contains(file.relativePath)))

  private def validateSqlDirectory(
    payload: ValidateSqlPayload,
    auditStore: AuditStore,
    claims: com.sslproxy.schema.server.auth.Claims
  ): IO[org.http4s.Response[IO]] =
    val sqlDir = payload.sql_dir.trim
    val customer = payload.customer.map(_.trim).filter(_.nonEmpty)
    DbKind.parse(payload.db_kind) match
      case Left(message) => RouteJson.badRequest(message)
      case Right(dbKind) =>
        validateSqlDir(sqlDir) match
          case Left(message) => RouteJson.badRequest(message)
          case Right(path) =>
            validateCustomer(customer) match
              case Left(message) => RouteJson.badRequest(message)
              case Right(()) =>
                for
                  discovery <- DiscoveryService().discover(path, dbKind, customer)
                  plan <- MigrationPlan.inspect(dbKind, SqlDialect.forDbKind(dbKind), discovery)
                  checkedAt <- Clock[IO].realTimeInstant.map(_.toString)
                  result = sqlFilesValidationResult(
                    targetId = "filesystem",
                    dbKind = dbKind.toString.toLowerCase,
                    checkedAt = checkedAt,
                    fileCount = discovery.files.length,
                    report = plan.validation
                  )
                  _ <- auditStore.record(
                    claims.subject,
                    claims.role,
                    "validation.validate",
                    "validation",
                    sqlDir,
                    None,
                    Some(
                      Json.obj(
                        "db_kind" -> Json.fromString(result.db_kind),
                        "status" -> Json.fromString(result.status),
                        "file_count" -> Json.fromInt(result.file_count)
                      )
                    )
                  )
                  response <- RouteJson.ok(result.asJson)
                yield response
  private def sqlFilesValidationResult(
    targetId: String,
    dbKind: String,
    checkedAt: String,
    fileCount: Int,
    report: ValidationReport
  ): SqlFilesValidationResult =
    val (invalid, status) = ValidationStore.invalidObjectsAndStatus(report)
    SqlFilesValidationResult(
      target_id = targetId,
      db_kind = dbKind,
      checked_at = checkedAt,
      file_count = fileCount,
      invalid = invalid,
      status = status
    )

  private def validateSqlDirWithinConfiguredRoot(value: String, configuredRoot: Path): Either[String, Unit] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left("sql_dir is required")
    else
      parseSqlPath(trimmed).flatMap { path =>
        val root = configuredRoot.toAbsolutePath.normalize()
        val requested = path.toAbsolutePath.normalize()
        Either.cond(
          requested.startsWith(root),
          (),
          s"sql_dir must be within configured sql directory '$root'"
        )
      }

  private def validateSqlDir(value: String): Either[String, Path] =
    if value.isEmpty then Left("sql_dir is required")
    else
      parseSqlPath(value).flatMap { path =>
        if Files.notExists(path) then Left(s"sql directory '$path' does not exist or is not accessible")
        else if !Files.isDirectory(path) then Left(s"path '$path' is not a directory")
        else Right(path)
      }

  private def parseSqlPath(value: String): Either[String, Path] =
    try Right(Path.of(value))
    catch case error: InvalidPathException => Left(s"invalid sql_dir '$value': ${error.getReason}")

  private def validateCustomer(value: Option[String]): Either[String, Unit] =
    value match
      case None => Right(())
      case Some(customer) if customer.isEmpty => Left("customer must not be empty")
      case Some(customer) if customer.contains("/") || customer.contains("\\") || customer == "." || customer == ".." =>
        Left("customer must be a single directory name")
      case Some(_) => Right(())
