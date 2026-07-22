package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.config.{DbKind, MigratorConfig}
import com.sslproxy.schema.discovery.{DiscoveryService, SqlFile, SqlPathNormalizer}
import com.sslproxy.schema.parser.HeaderParser
import com.sslproxy.schema.server.auth.{AuthContext, Claims, UserRole}
import com.sslproxy.schema.store.{
  AuditStore,
  CreateSnapshotPayload,
  Models,
  PatchStore,
  PatchUpload,
  RollbackToSnapshotPayload,
  RunStore,
  Snapshot,
  SnapshotFile,
  SnapshotStore,
  SqlFileStore,
  StoredSqlFile,
  TargetStore,
  TriggerRunPayload
}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64

object SnapshotRoutes:
  import Models.given

  def routes(
    config: MigratorConfig,
    targetStore: TargetStore,
    patchStore: PatchStore,
    runStore: RunStore,
    sqlFileStore: SqlFileStore,
    snapshotStore: SnapshotStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case request @ GET -> Root / "snapshots" =>
        val targetId = request.uri.query.params.get("target_id").filter(_.nonEmpty)
        snapshotStore
          .list(targetId)
          .map(_.map(SnapshotStore.publicView))
          .flatMap(snapshots => RouteJson.ok(Json.obj("snapshots" -> snapshots.asJson)))

      case request @ POST -> Root / "snapshots" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          RouteJson.withJson[CreateSnapshotPayload](request, "invalid snapshot payload") { payload =>
            targetStore.get(payload.target_id).flatMap {
              case None => RouteJson.notFound(s"target '${payload.target_id}' was not found")
              case Some(_) =>
                for
                  files <- sqlFileStore.list(payload.target_id)
                  snapshot <- snapshotStore.create(payload.target_id, payload.label, files, claims.subject)
                  _ <- auditStore.record(
                    claims.subject,
                    claims.role,
                    "snapshot.create",
                    "snapshot",
                    snapshot.id,
                    Some(snapshot.target_id),
                    Some(Json.obj("file_count" -> Json.fromInt(snapshot.file_count)))
                  )
                  response <- RouteJson.created(SnapshotStore.publicView(snapshot).asJson)
                yield response
            }
          }
        }

      case GET -> Root / "snapshots" / id =>
        snapshotStore.get(id).flatMap { snapshot =>
          RouteJson.fromOption(snapshot, s"snapshot '$id' was not found")(snapshot =>
            RouteJson.ok(SnapshotStore.publicView(snapshot).asJson)
          )
        }

      case GET -> Root / "snapshots" / id / "diff" / otherId =>
        (snapshotStore.get(id), snapshotStore.get(otherId), nowString).tupled.flatMap {
          case (None, _, _) => RouteJson.notFound(s"snapshot '$id' was not found")
          case (_, None, _) => RouteJson.notFound(s"snapshot '$otherId' was not found")
          case (Some(base), Some(compare), generatedAt) =>
            RouteJson.ok(SnapshotStore.diff(base, compare, generatedAt).asJson)
        }

      case request @ POST -> Root / "snapshots" / id / "rollback" =>
        AuthContext.requireRole(request, UserRole.Operator) { claims =>
          RouteJson.withJson[RollbackToSnapshotPayload](request, "invalid rollback payload") { payload =>
            (targetStore.getStored(payload.target_id), snapshotStore.get(id)).tupled.flatMap {
              case (None, _) => RouteJson.notFound(s"target '${payload.target_id}' was not found")
              case (_, None) => RouteJson.notFound(s"snapshot '$id' was not found")
              case (Some(_), Some(snapshot)) if snapshot.target_id != payload.target_id =>
                RouteJson.badRequest("snapshot does not belong to target")
              case (Some(target), Some(snapshot)) =>
                rollbackToSnapshot(
                  config,
                  claims,
                  payload,
                  snapshot,
                  target,
                  patchStore,
                  runStore,
                  sqlFileStore,
                  auditStore,
                  runExecutor
                )
            }
          }
        }
    }

  private def rollbackToSnapshot(
    config: MigratorConfig,
    claims: Claims,
    payload: RollbackToSnapshotPayload,
    snapshot: Snapshot,
    target: com.sslproxy.schema.store.StoredTarget,
    patchStore: PatchStore,
    runStore: RunStore,
    sqlFileStore: SqlFileStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ) =
    TargetRoutes.withDbAccessAllowed(
      config.server,
      target.target.jdbc_url,
      "database migration execution is not allowed for this target"
    ) {
      targetHasActiveRun(runStore, payload.target_id).flatMap {
        case true => RouteJson.conflict(s"target '${payload.target_id}' already has an active run")
        case false =>
          TargetDatabase.dbKindFor(target.target.jdbc_url) match
            case Left(message) => RouteJson.badRequest(message)
            case Right(dbKind) =>
              sqlFileStore.list(payload.target_id).flatMap { currentFiles =>
                rollbackUploads(snapshot, currentFiles, dbKind) match
                  case Left(errors) =>
                    RouteJson.unprocessableEntity(
                      Json.obj(
                        "error" -> Json.fromString("rollback validation failed"),
                        "details" -> errors.asJson
                      )
                    )
                  case Right(Nil) =>
                    RouteJson.unprocessableEntity("snapshot already matches current SQL files")
                  case Right(uploads) =>
                    createRollbackRun(
                      claims,
                      payload,
                      snapshot,
                      uploads,
                      target,
                      patchStore,
                      runStore,
                      auditStore,
                      runExecutor
                    )
              }
      }
    }

  private def createRollbackRun(
    claims: Claims,
    payload: RollbackToSnapshotPayload,
    snapshot: Snapshot,
    uploads: List[PatchUpload],
    target: com.sslproxy.schema.store.StoredTarget,
    patchStore: PatchStore,
    runStore: RunStore,
    auditStore: AuditStore,
    runExecutor: RunExecutor
  ) =
    for
      patch <- patchStore.create(payload.target_id, uploads, Some(snapshot.id))
      runResult <- runStore.create(TriggerRunPayload(patch.id, payload.target_id), patch, claims.subject).attempt
      response <- runResult match
        case Left(_: RunStore.ConcurrentRun) =>
          patchStore.delete(patch.id) *> RouteJson.conflict(s"target '${payload.target_id}' already has an active run")
        case Left(error) =>
          patchStore.delete(patch.id) *> IO.raiseError(error)
        case Right(run) =>
          val metadata = Json.obj(
            "snapshot_id" -> Json.fromString(snapshot.id),
            "patch_id" -> Json.fromString(patch.id),
            "source_type" -> payload.source_type.fold(Json.Null)(Json.fromString),
            "source_id" -> payload.source_id.fold(Json.Null)(Json.fromString)
          )
          auditStore.record(
            claims.subject,
            claims.role,
            "rollback.trigger",
            "snapshot",
            snapshot.id,
            Some(payload.target_id),
            Some(metadata)
          ) *>
            auditStore.record(
              claims.subject,
              claims.role,
              "run.trigger",
              "run",
              run.id,
              Some(payload.target_id),
              Some(
                Json.obj("patch_id" -> Json.fromString(patch.id), "source_snapshot_id" -> Json.fromString(snapshot.id))
              )
            ) *>
            runExecutor.submit(target, run, patch) *>
            RouteJson.created(run.asJson)
    yield response

  private def rollbackUploads(
    snapshot: Snapshot,
    currentFiles: List[StoredSqlFile],
    dbKind: DbKind
  ): Either[List[String], List[PatchUpload]] =
    val currentByPath = currentFiles.map(file => file.path -> file).toMap
    val snapshotByPath = snapshot.files.map(file => file.path -> file).toMap
    val currentOnly = currentByPath.keySet.diff(snapshotByPath.keySet)
    val restorePaths = snapshotByPath.collect {
      case (path, snapshotFile) if currentByPath.get(path).forall(_.sha256 != snapshotFile.sha256) => path
    }.toSet

    val currentOrder = orderedCurrentPaths(currentFiles, dbKind)
    val snapshotOrder = orderedSnapshotPaths(snapshot.files, dbKind)
    val currentOnlyOrder = orderedSubset(currentOnly, currentOrder).reverse
    val restoreOrder = orderedSubset(restorePaths, snapshotOrder)

    val rollbackResults = currentOnlyOrder.zipWithIndex.map { case (path, index) =>
      currentByPath.get(path) match
        case None => Left(s"$path: current file was not found")
        case Some(file) => rollbackUpload(file, currentByPath, index + 1)
    }

    val restoreStartOrder = rollbackResults.length + 1
    val restoreResults = restoreOrder.zipWithIndex.map { case (path, index) =>
      snapshotByPath.get(path) match
        case None => Left(s"$path: snapshot file was not found")
        case Some(file) => restoreUpload(file, restoreStartOrder + index)
    }

    val results = rollbackResults ++ restoreResults
    val errors = results.collect { case Left(error) => error }
    if errors.nonEmpty then Left(errors)
    else Right(results.collect { case Right(upload) => upload })

  private def rollbackUpload(
    file: StoredSqlFile,
    currentByPath: Map[String, StoredSqlFile],
    order: Int
  ): Either[String, PatchUpload] =
    for
      sql <- decodeBase64(file.contentBase64).leftMap(error => s"${file.path}: current SQL is unreadable ($error)")
      rollbackRef <- HeaderParser
        .value(sql, "rollback")
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(s"${file.path}: missing -- rollback: reference")
      rollbackFile <- resolveStoredRollback(file.path, rollbackRef, currentByPath)
        .toRight(s"${file.path}: rollback file '$rollbackRef' does not exist")
      rollbackSql <- decodeBase64(rollbackFile.contentBase64)
        .leftMap(error => s"${file.path}: rollback file '${rollbackFile.path}' is unreadable ($error)")
      _ <- Either.cond(
        rollbackSql.trim.nonEmpty,
        (),
        s"${file.path}: rollback file '${rollbackFile.path}' is empty"
      )
    yield PatchUpload(
      generatedFilename("rollback", file.folder, file.filename),
      rollbackSql.getBytes(StandardCharsets.UTF_8),
      order
    )

  private def restoreUpload(file: SnapshotFile, order: Int): Either[String, PatchUpload] =
    for
      contentBase64 <- file.content_base64.toRight(s"${file.path}: snapshot file content is missing")
      sql <- decodeBase64(contentBase64).leftMap(error => s"${file.path}: snapshot SQL is unreadable ($error)")
      _ <- Either.cond(sql.trim.nonEmpty, (), s"${file.path}: snapshot SQL is empty")
    yield PatchUpload(
      generatedFilename("restore", file.folder, file.filename),
      sql.getBytes(StandardCharsets.UTF_8),
      order
    )

  private def resolveStoredRollback(
    currentPath: String,
    rollback: String,
    currentByPath: Map[String, StoredSqlFile]
  ): Option[StoredSqlFile] =
    rollbackCandidates(currentPath, rollback).collectFirst(Function.unlift(currentByPath.get))

  private def rollbackCandidates(currentPath: String, rollback: String): List[String] =
    val normalizedRollback = rollback.replace('\\', '/').trim
    val direct = SqlPathNormalizer.normalizeUploadPath(normalizedRollback).path
    val parent = Option(Path.of(currentPath).getParent).map(_.toString.replace('\\', '/'))
    val fileRelative = parent.map(folder => SqlPathNormalizer.normalizeUploadPath(s"$folder/$normalizedRollback").path)
    List(Some(direct), fileRelative).flatten.distinct

  private def generatedFilename(prefix: String, folder: String, filename: String): String =
    val safePrefix = prefix.replaceAll("[^A-Za-z0-9._-]", "_")
    val safeFilename = filename.replaceAll("[^A-Za-z0-9._-]", "_")
    val normalizedFolder = folder.replace('\\', '/').trim
    if normalizedFolder.nonEmpty then s"$normalizedFolder/${safePrefix}_$safeFilename"
    else s"${safePrefix}_$safeFilename"

  private def orderedCurrentPaths(files: List[StoredSqlFile], dbKind: DbKind): List[String] =
    val sqlFiles = files.map(storedToSqlFile)
    DiscoveryService().discoverFromFiles(sqlFiles, dbKind).files.map(_.relativePath)

  private def orderedSnapshotPaths(files: List[SnapshotFile], dbKind: DbKind): List[String] =
    val sqlFiles = files.flatMap(snapshotToSqlFile)
    DiscoveryService().discoverFromFiles(sqlFiles, dbKind).files.map(_.relativePath)

  private def orderedSubset(paths: Set[String], ordered: List[String]): List[String] =
    val orderedMatches = ordered.filter(paths.contains)
    orderedMatches ::: paths.diff(orderedMatches.toSet).toList.sorted

  private def storedToSqlFile(file: StoredSqlFile): SqlFile =
    SqlFile(file.folder, Path.of(file.path), file.filename, file.path, decodeBase64(file.contentBase64).toOption)

  private def snapshotToSqlFile(file: SnapshotFile): Option[SqlFile] =
    file.content_base64.map(content =>
      SqlFile(file.folder, Path.of(file.path), file.filename, file.path, decodeBase64(content).toOption)
    )

  private def decodeBase64(value: String): Either[String, String] =
    Either
      .catchNonFatal(new String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8))
      .leftMap(_.getMessage)

  private def targetHasActiveRun(runStore: RunStore, targetId: String): IO[Boolean] =
    runStore.list(Some(targetId)).map(_.exists(run => !Set("completed", "failed", "aborted").contains(run.status)))

  private def nowString: IO[String] =
    cats.effect.Clock[IO].realTimeInstant.map(_.toString)
