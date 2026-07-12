package com.sslproxy.schema.discovery

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import com.sslproxy.schema.store.{RepoSyncStore, SqlFileStore, Target, TargetStore}

import java.nio.file.Path
import java.util.concurrent.Semaphore
import scala.concurrent.duration.*

final case class SyncResult(
  added: Int,
  removed: Int,
  changed: Int,
  unchanged: Int,
  commitSha: String,
  syncedAt: String
)

final class RepoSyncService(
  sqlFileStore: SqlFileStore,
  repoSyncStore: RepoSyncStore,
  loader: GitRepoLoader,
  cacheDir: Path,
  cloneTimeoutSeconds: Int,
  targetStore: TargetStore,
  syncLock: Semaphore = new Semaphore(1)
):
  def sync(targetId: String, target: Target): IO[SyncResult] =
    lock.use { _ =>
      syncUnlocked(targetId, target).timeout(cloneTimeoutSeconds.seconds)
    }

  private def lock: Resource[IO, Unit] =
    Resource.make(IO.blocking(syncLock.acquire()))(_ => IO.blocking(syncLock.release()))

  private def syncUnlocked(targetId: String, target: Target): IO[SyncResult] =
    loader.cloned(target.repo_url, target.repo_branch, cacheDir) { repoRoot =>
      for
        sqlRoot <- IO.fromEither(
          loader.resolveSqlRoot(repoRoot, target.repo_sql_path).leftMap(IllegalArgumentException(_))
        )
        newFiles <- loader.loadFiles(sqlRoot)
        commitSha <- loader.headCommit(repoRoot)
        currentFiles <- sqlFileStore.list(targetId)
        result <- diff(
          currentFiles.map(file => file.path -> file.sha256).toMap,
          newFiles.map(file => file.path -> file.sha256).toMap
        )
        syncedAt <- Clock[IO].realTimeInstant.map(_.toString)
        hasChanges = result.added > 0 || result.removed > 0 || result.changed > 0
        _ <-
          if hasChanges then
            sqlFileStore.replaceAll(targetId, newFiles) *>
              (repoSyncStore.recordSync(targetId, commitSha, syncedAt) *>
                targetStore.recordRepoSync(targetId, commitSha, syncedAt))
                .flatMap {
                  case true => IO.unit
                  case false =>
                    // targetStore.recordRepoSync returned false — compensate by restoring old files
                    sqlFileStore.replaceAll(targetId, currentFiles) *>
                      IO.raiseError(IllegalStateException(s"targetStore.recordRepoSync failed for target $targetId"))
                }
                .onError { case _ =>
                  // Compensate: restore previous files if any metadata write fails
                  sqlFileStore.replaceAll(targetId, currentFiles).void
                }
          else
            repoSyncStore.recordSync(targetId, commitSha, syncedAt) *>
              targetStore.recordRepoSync(targetId, commitSha, syncedAt).flatMap {
                case true => IO.unit
                case false =>
                  IO.raiseError(IllegalStateException(s"targetStore.recordRepoSync failed for target $targetId"))
              }
      yield result.copy(commitSha = commitSha, syncedAt = syncedAt)
    }

  private def diff(current: Map[String, String], next: Map[String, String]): IO[SyncResult] =
    IO.delay {
      val currentPaths = current.keySet
      val nextPaths = next.keySet
      val shared = currentPaths.intersect(nextPaths)
      val changed = shared.count(path => current(path) != next(path))
      val unchanged = shared.size - changed
      SyncResult(
        added = nextPaths.diff(currentPaths).size,
        removed = currentPaths.diff(nextPaths).size,
        changed = changed,
        unchanged = unchanged,
        commitSha = "",
        syncedAt = ""
      )
    }

object RepoSyncService:
  def apply(
    sqlFileStore: SqlFileStore,
    repoSyncStore: RepoSyncStore,
    loader: GitRepoLoader,
    cacheDir: Path,
    cloneTimeoutSeconds: Int,
    targetStore: TargetStore
  ): RepoSyncService =
    new RepoSyncService(sqlFileStore, repoSyncStore, loader, cacheDir, cloneTimeoutSeconds, targetStore)
