package com.sslproxy.schema.server

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.{DiscoveryService, SqlFile}
import com.sslproxy.schema.engine.ManifestBuilder
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{
  AuditStore,
  DriftResponse,
  DriftRunPayload,
  Models,
  PatchStore,
  PatchUpload,
  RunStore,
  SchemaCatalogResponse,
  SqlFileStore,
  StoredSqlFile,
  StoredTarget,
  TargetStore
}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.control.NonFatal

object SchemaRoutes:
  import Models.given
  import PostgresDriftAnalyzer.*

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val log = LoggerFactory[IO].getLogger

  def routes(
    config: MigratorConfig,
    targetStore: TargetStore,
    sqlFileStore: SqlFileStore,
    patchStore: PatchStore,
    runStore: RunStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ GET -> Root / "schema" =>
        targetId(request).fold(RouteJson.badRequest("target_id is required")) { id =>
          targetStore.getStored(id).flatMap {
            case None => RouteJson.notFound(s"target '$id' was not found")
            case Some(target) =>
              TargetRoutes.withDbAccessAllowed(
                config.server,
                target.target.jdbc_url,
                "database schema access is not allowed for this target"
              ) {
                catalog(config, target, sqlFileStore).flatMap(response => RouteJson.ok(response.asJson))
              }
          }
        }

      case request @ GET -> Root / "drift" =>
        targetId(request).fold(RouteJson.badRequest("target_id is required")) { id =>
          targetStore.getStored(id).flatMap {
            case None => RouteJson.notFound(s"target '$id' was not found")
            case Some(target) =>
              TargetRoutes.withDbAccessAllowed(
                config.server,
                target.target.jdbc_url,
                "database drift access is not allowed for this target"
              ) {
                drift(config, target, sqlFileStore).flatMap(response => RouteJson.ok(response.asJson))
              }
          }
        }

      case request @ POST -> Root / "drift" / "runs" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          request.as[DriftRunPayload].flatMap { payload =>
            targetStore.getStored(payload.target_id).flatMap {
              case None => RouteJson.notFound(s"target '${payload.target_id}' was not found")
              case Some(target) =>
                TargetRoutes.withDbAccessAllowed(
                  config.server,
                  target.target.jdbc_url,
                  "database drift execution is not allowed for this target"
                ) {
                  triggerDriftRun(config, target, payload, claims.subject, claims.role, sqlFileStore, patchStore, runStore, auditStore, runExecutor)
                }
            }
          }
        }
    }

  private def catalog(config: MigratorConfig, target: StoredTarget, sqlFileStore: SqlFileStore): IO[SchemaCatalogResponse] =
    for
      now <- nowString
      kind = dbKindFor(target.target.jdbc_url)
      expected <- expectedObjects(config, kind, now, target.target.id, sqlFileStore)
      response <- kind match
        case DbKind.Oracle =>
          IO.pure(
            SchemaCatalogResponse(
              target_id = target.target.id,
              db_kind = "oracle",
              supported = false,
              checked_at = now,
              objects = Nil,
              warnings = expected.warnings :+
                "Oracle schema catalog introspection is not implemented; use target connection tests for Oracle validation."
            )
          )
        case DbKind.Postgres =>
          postgresSnapshot(target).attempt.map {
            case Right(snapshot) =>
              val objects = mergeCatalog(now, expected.objects, snapshot.objects, snapshot.control)
              SchemaCatalogResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                objects = objects,
                warnings = expected.warnings ++ snapshot.control.warnings
              )
            case Left(error) =>
              val objects = expected.objects.map(_.toCatalog(now, actual = None, status = "defined"))
              SchemaCatalogResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                objects = objects,
                warnings = expected.warnings :+ s"live Postgres introspection failed: ${error.getMessage}"
              )
          }
      _ <- log.info(Json.obj("event" -> Json.fromString("schema_catalog"), "target_id" -> Json.fromString(target.target.id), "db_kind" -> Json.fromString(response.db_kind), "object_count" -> Json.fromInt(response.objects.size), "supported" -> Json.fromBoolean(response.supported)).noSpaces)
    yield response

  private def drift(config: MigratorConfig, target: StoredTarget, sqlFileStore: SqlFileStore): IO[DriftResponse] =
    for
      now <- nowString
      kind = dbKindFor(target.target.jdbc_url)
      expected <- expectedObjects(config, kind, now, target.target.id, sqlFileStore)
      response <- kind match
        case DbKind.Oracle =>
          IO.pure(
            DriftResponse(
              target_id = target.target.id,
              db_kind = "oracle",
              supported = false,
              checked_at = now,
              control_summary = None,
              control_objects = Nil,
              items = Nil,
              warnings = expected.warnings :+
                "Oracle drift introspection is not implemented; use target connection tests for Oracle validation."
            )
          )
        case DbKind.Postgres =>
          postgresSnapshot(target).attempt.map {
            case Right(snapshot) =>
              DriftResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                control_summary = snapshot.control.summary,
                control_objects = snapshot.control.rows,
                items = driftItems(now, expected.objects, snapshot.objects, snapshot.control),
                warnings = expected.warnings ++ snapshot.control.warnings
              )
            case Left(error) =>
              DriftResponse(
                target_id = target.target.id,
                db_kind = "postgres",
                supported = true,
                checked_at = now,
                control_summary = None,
                control_objects = Nil,
                items = Nil,
                warnings = expected.warnings :+ s"live Postgres drift introspection failed: ${error.getMessage}"
              )
          }
      _ <- log.info(Json.obj("event" -> Json.fromString("schema_drift"), "target_id" -> Json.fromString(target.target.id), "db_kind" -> Json.fromString(response.db_kind), "drift_item_count" -> Json.fromInt(response.items.size), "supported" -> Json.fromBoolean(response.supported)).noSpaces)
    yield response

  private def triggerDriftRun(
    config: MigratorConfig,
    target: StoredTarget,
    payload: DriftRunPayload,
    actor: String,
    role: String,
    sqlFileStore: SqlFileStore,
    patchStore: PatchStore,
    runStore: RunStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ) =
    val targetId = target.target.id
    for
      now <- nowString
      kind = dbKindFor(target.target.jdbc_url)
      response <-
        kind match
          case DbKind.Oracle =>
            RouteJson.badRequest("Oracle drift execution is not implemented")
          case DbKind.Postgres =>
            (for
              expected <- expectedObjects(config, kind, now, targetId, sqlFileStore)
              snapshot <- postgresSnapshot(target)
              drift = driftItems(now, expected.objects, snapshot.objects, snapshot.control)
              runnableSourceFiles = orderedRunnableSources(expected.objects, drift)
              selectedSourceFiles = selectedRunnableSources(payload.source_files, runnableSourceFiles)
              result <-
                if runnableSourceFiles.isEmpty then RouteJson.badRequest("no executable drift items were found")
                else selectedSourceFiles match
                  case Left(message) => RouteJson.badRequest(message)
                  case Right(Nil) => RouteJson.badRequest("at least one executable drift source file is required")
                  case Right(sourceFiles) =>
                    driftUploads(config, targetId, sqlFileStore, sourceFiles).flatMap {
                      case Left(message) => RouteJson.badRequest(message)
                      case Right(uploads) =>
                        (for
                          patch <- patchStore.create(targetId, uploads)
                          run <- runStore.create(com.sslproxy.schema.store.TriggerRunPayload(patch.id, targetId), patch, actor)
                          _ <- auditStore
                            .record(
                              actor,
                              role,
                              "drift.run",
                              "run",
                              run.id,
                              Some(targetId),
                              Some(Json.obj("patch_id" -> Json.fromString(patch.id), "script_count" -> Json.fromInt(uploads.length)))
                            )
                            .handleErrorWith(error => log.warn(error)(s"failed to record audit event for drift run ${run.id}"))
                          _ <- log.info(
                            Json
                              .obj(
                                "event" -> Json.fromString("drift_run_triggered"),
                                "run_id" -> Json.fromString(run.id),
                                "target_id" -> Json.fromString(targetId),
                                "patch_id" -> Json.fromString(patch.id),
                                "script_count" -> Json.fromInt(uploads.length)
                              )
                              .noSpaces
                          )
                          _ <- runExecutor.run(target, run, patch).start.void
                          created <- RouteJson.created(run.asJson)
                        yield created).recoverWith { case _: RunStore.ConcurrentRun =>
                          RouteJson.conflict(s"target '$targetId' already has an active run")
                        }
                    }
            yield result).handleErrorWith { case NonFatal(error) =>
              RouteJson.badRequest(s"drift run could not be prepared: ${error.getMessage}")
            }
    yield response

  private def orderedRunnableSources(expected: List[ExpectedObject], drift: List[com.sslproxy.schema.store.DriftItem]): List[String] =
    val driftSources = drift.flatMap(_.source_file).toSet
    expected.map(_.sourceFile).distinct.filter(driftSources.contains)

  private def selectedRunnableSources(requested: Option[List[String]], runnable: List[String]): Either[String, List[String]] =
    requested match
      case None => Right(runnable)
      case Some(values) =>
        val selected = values.map(_.trim).filter(_.nonEmpty).distinct
        val runnableSet = runnable.toSet
        val invalid = selected.filterNot(runnableSet.contains)
        if invalid.nonEmpty then Left(s"source_files are not executable drift items: ${invalid.mkString(", ")}")
        else Right(runnable.filter(selected.toSet.contains))

  private def driftUploads(
    config: MigratorConfig,
    targetId: String,
    sqlFileStore: SqlFileStore,
    sourceFiles: List[String]
  ): IO[Either[String, List[PatchUpload]]] =
    sqlFileStore.isEmpty(targetId).flatMap { empty =>
      if !empty then
        sqlFileStore.list(targetId).map { storedFiles =>
          val byPath = storedFiles.map(file => file.path -> file).toMap
          sourceFiles.traverse { sourceFile =>
            byPath.get(sourceFile).toRight(s"source file '$sourceFile' is not available in the SQL file store").map { file =>
              PatchUpload(sourceFile, Base64.getDecoder.decode(file.contentBase64), sourceFiles.indexOf(sourceFile) + 1)
            }
          }
        }
      else
        sourceFiles.zipWithIndex.traverse { case (sourceFile, index) =>
          readSourceFile(config.sqlDir, sourceFile).map(_.map(bytes => PatchUpload(sourceFile, bytes, index + 1)))
        }.map(_.sequence)
    }

  private def readSourceFile(sqlDir: Path, sourceFile: String): IO[Either[String, Array[Byte]]] =
    IO.blocking {
      val base = sqlDir.toAbsolutePath.normalize()
      val path = base.resolve(sourceFile).normalize()
      if !path.startsWith(base) then Left(s"source file '$sourceFile' is outside the SQL directory")
      else if Files.notExists(path) || !Files.isRegularFile(path) then Left(s"source file '$sourceFile' was not found")
      else Right(Files.readAllBytes(path))
    }

  private def targetId(request: org.http4s.Request[IO]): Option[String] =
    request.uri.query.params.get("target_id").map(_.trim).filter(_.nonEmpty)

  private def expectedObjects(config: MigratorConfig, kind: DbKind, now: String, targetId: String, sqlFileStore: SqlFileStore): IO[ExpectedSnapshot] =
    if kind != DbKind.Postgres then IO.pure(ExpectedSnapshot(Nil, Nil))
    else
      // Try store-based discovery first, fall back to filesystem
      sqlFileStore.isEmpty(targetId).flatMap { empty =>
        if !empty then
          (for
            files <- storedSqlFiles(targetId, sqlFileStore)
            discovery = DiscoveryService[IO]().discoverFromFiles(files, DbKind.Postgres)
            objects <- ManifestBuilder[IO](SqlDialect.Postgres).build(discovery.files)
            expected = objects.flatMap(expectedFromManifest)
          yield ExpectedSnapshot(expected, discovery.warnings)).handleError { case NonFatal(error) =>
            ExpectedSnapshot(Nil, List(s"SQL manifest from store could not be loaded: ${error.getClass.getName}: ${error.getMessage}"))
          }
        else if Files.notExists(config.sqlDir) || !Files.isDirectory(config.sqlDir) then
          IO.pure(ExpectedSnapshot(Nil, List(s"sql directory '${config.sqlDir}' is unavailable; no files in store either")))
        else
          (for
            discovery <- DiscoveryService[IO]().discover(config.sqlDir, DbKind.Postgres, config.customer)
            objects <- ManifestBuilder[IO](SqlDialect.Postgres).build(discovery.files)
            expected = objects.flatMap(expectedFromManifest)
          yield ExpectedSnapshot(expected, discovery.warnings)).handleError { case NonFatal(error) =>
            ExpectedSnapshot(Nil, List(s"SQL manifest could not be loaded: ${error.getMessage}"))
          }
      }

  private def storedSqlFiles(targetId: String, sqlFileStore: SqlFileStore): IO[List[SqlFile]] =
    sqlFileStore.list(targetId).map(_.map(storedSqlFile))

  private def storedSqlFile(stored: StoredSqlFile): SqlFile =
    val bytes = Base64.getDecoder.decode(stored.contentBase64)
    SqlFile(
      folder = stored.folder,
      path = java.nio.file.Path.of(stored.path),
      name = stored.filename,
      relativePath = stored.path,
      content = Some(String(bytes, StandardCharsets.UTF_8))
    )

  private def postgresSnapshot(target: StoredTarget): IO[PostgresCatalogReader.Snapshot] =
    IO.blocking {
      Class.forName("org.postgresql.Driver")
      val connection = connect(target)
      try
        PostgresCatalogReader.readSnapshot(connection)
      finally connection.close()
    }

  private def connect(target: StoredTarget): Connection =
    val settings = JdbcConnectionProperties.normalize(target.target.jdbc_url, target.password)
    DriverManager.getConnection(
      settings.jdbcUrl,
      JdbcConnectionProperties.withTimeouts(settings.jdbcUrl, settings.password, 5.seconds, user = settings.user)
    )

  private def dbKindFor(jdbcUrl: String): DbKind =
    if jdbcUrl.trim.startsWith("jdbc:oracle:thin:") then DbKind.Oracle else DbKind.Postgres

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)
