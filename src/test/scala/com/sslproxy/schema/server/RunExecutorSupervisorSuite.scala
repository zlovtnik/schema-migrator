package com.sslproxy.schema.server

import cats.effect.{Deferred, IO}
import cats.effect.std.Supervisor
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.store.{Patch, RunStore, StoredTarget, Target, TriggerRunPayload}
import munit.FunSuite

class RunExecutorSupervisorSuite extends FunSuite:
  test("cancels managed fibers and aborts a started run when the owning resource closes") {
    val result = (for
      runStore <- RunStore.inMemory
      run <- runStore.create(TriggerRunPayload(patchRecord.id, storedTarget.target.id), patchRecord, "test")
      started <- Deferred[IO, Unit]
      canceled <- Deferred[IO, Unit]
      delegate = new RunExecutor:
        override def run(target: StoredTarget, runRecord: com.sslproxy.schema.store.Run, patch: Patch): IO[Unit] =
          runStore.startRun(runRecord.id).flatMap {
            case true => started.complete(()).void *> IO.never.onCancel(canceled.complete(()).void)
            case false => IO.unit
          }
      _ <- Supervisor[IO].use { supervisor =>
        RunExecutor.supervised(delegate, supervisor, runStore).submit(storedTarget, run, patchRecord) *> started.get
      }
      wasCanceled <- canceled.tryGet
      stored <- runStore.get(run.id)
      retry <- runStore.create(TriggerRunPayload(patchRecord.id, storedTarget.target.id), patchRecord, "retry")
    yield (wasCanceled.nonEmpty, stored.map(_.status), retry.target_id)).unsafeRunSync()

    assertEquals(result, (true, Some("aborted"), storedTarget.target.id))
  }

  private val storedTarget = StoredTarget(
    Target(
      id = "target-1",
      label = "Target",
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:postgresql://localhost:5432/app",
      created_at = "2026-07-18T00:00:00Z",
      repo_url = "https://example.com/repo.git",
      repo_branch = "main",
      repo_sql_path = "sql",
      last_synced_commit = None,
      last_synced_at = None,
      db_kind = "postgres"
    ),
    None
  )

  private val patchRecord = Patch(
    id = "patch-1",
    target_id = "target-1",
    version = "1",
    label = "Patch",
    scripts = Nil,
    status = "pending",
    applied_at = None
  )
