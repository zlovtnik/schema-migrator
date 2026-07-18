package com.sslproxy.schema.server

import cats.effect.{Deferred, IO}
import cats.effect.std.Supervisor
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.store.{Patch, Run, StoredTarget, Target}
import munit.FunSuite

class RunExecutorSupervisorSuite extends FunSuite:
  test("cancels managed run fibers when the owning resource closes") {
    val canceled = (for
      started <- Deferred[IO, Unit]
      canceled <- Deferred[IO, Unit]
      delegate = new RunExecutor:
        override def run(target: StoredTarget, run: Run, patch: Patch): IO[Unit] =
          started.complete(()).void *> IO.never.onCancel(canceled.complete(()).void)
      _ <- Supervisor[IO].use { supervisor =>
        RunExecutor.supervised(delegate, supervisor).submit(storedTarget, runRecord, patchRecord) *> started.get
      }
      wasCanceled <- canceled.tryGet
    yield wasCanceled.nonEmpty).unsafeRunSync()

    assert(canceled)
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

  private val runRecord = Run(
    id = "run-1",
    target_id = "target-1",
    patch_id = "patch-1",
    status = "pending",
    scripts = Nil,
    started_at = "2026-07-18T00:00:00Z",
    ended_at = None,
    triggered_by = "test"
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
