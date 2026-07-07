package com.sslproxy.schema.server

import cats.effect.IO
import cats.syntax.all.*
import com.sslproxy.schema.discovery.{GitRepoLoader, RepoSyncService, SyncResult}
import com.sslproxy.schema.server.auth.{AuthContext, UserRole}
import com.sslproxy.schema.store.{RepoSyncState, RepoSyncStore, SqlFileStore, Target, TargetStore}
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

object SqlFileRoutes:
  def routes(
    sqlFileStore: SqlFileStore,
    targetStore: TargetStore,
    repoSyncStore: RepoSyncStore,
    repoSyncService: RepoSyncService,
    gitRepoLoader: GitRepoLoader
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "sql-files" =>
        sqlFileStore.list.flatMap { files =>
          val json = files.map { file =>
            Json.obj(
              "path" -> Json.fromString(file.path),
              "folder" -> Json.fromString(file.folder),
              "filename" -> Json.fromString(file.filename),
              "sha256" -> Json.fromString(file.sha256),
              "content_base64" -> Json.fromString(file.contentBase64),
              "uploaded_at" -> Json.fromString(file.uploadedAt)
            )
          }
          RouteJson.ok(Json.obj("files" -> Json.fromValues(json)))
        }

      case GET -> Root / "sql-files" / "status" =>
        sqlFileStore.list.flatMap { files =>
          val folders = files.map(_.folder).distinct.sorted
          RouteJson.ok(Json.obj(
            "loaded" -> Json.fromBoolean(files.nonEmpty),
            "file_count" -> Json.fromInt(files.size),
            "folders" -> Json.fromValues(folders.map(Json.fromString))
          ))
        }

      case request @ DELETE -> Root / "sql-files" =>
        AuthContext.requireRole(request, UserRole.Operator) { _ =>
          sqlFileStore.clear *> NoContent()
        }

      case request @ POST -> Root / "targets" / id / "repo-sync" =>
        AuthContext.requireRole(request, UserRole.Operator) { _ =>
          targetStore.get(id).flatMap {
            case Some(target) =>
              repoSyncService
                .sync(id, target)
                .flatMap(result => targetStore.recordRepoSync(id, result.commitSha, result.syncedAt).as(result))
                .flatMap(result => RouteJson.created(syncResultJson(result)))
                .handleErrorWith(error => RouteJson.badRequest(error.getMessage))
            case None => RouteJson.notFound(s"target '$id' was not found")
          }
        }

      case GET -> Root / "targets" / id / "repo-sync" / "status" =>
        targetStore.get(id).flatMap {
          case Some(target) =>
            for
              state <- repoSyncStore.getSyncState(id)
              remote <- gitRepoLoader.remoteHead(target.repo_url, target.repo_branch).attempt
              response <- RouteJson.ok(syncStatusJson(target, state, remote))
            yield response
          case None => RouteJson.notFound(s"target '$id' was not found")
        }
    }

  private def syncResultJson(result: SyncResult): Json =
    Json.obj(
      "added" -> Json.fromInt(result.added),
      "removed" -> Json.fromInt(result.removed),
      "changed" -> Json.fromInt(result.changed),
      "unchanged" -> Json.fromInt(result.unchanged),
      "commit_sha" -> Json.fromString(result.commitSha),
      "synced_at" -> Json.fromString(result.syncedAt)
    )

  private def syncStatusJson(
    target: Target,
    state: Option[RepoSyncState],
    remote: Either[Throwable, Option[String]]
  ): Json =
    val remoteHead = remote.toOption.flatten
    val drift = remoteHead.exists(head => state.forall(_.commit_sha != head))
    Json.obj(
      "target_id" -> Json.fromString(target.id),
      "repo_url" -> Json.fromString(target.repo_url),
      "repo_branch" -> Json.fromString(target.repo_branch),
      "repo_sql_path" -> Json.fromString(target.repo_sql_path),
      "last_synced_commit" -> state.map(_.commit_sha).fold(Json.Null)(Json.fromString),
      "last_synced_at" -> state.map(_.synced_at).fold(Json.Null)(Json.fromString),
      "remote_head_commit" -> remoteHead.fold(Json.Null)(Json.fromString),
      "drift" -> Json.fromBoolean(drift),
      "remote_error" -> remote.swap.toOption.map(_.getMessage).fold(Json.Null)(Json.fromString)
    )
