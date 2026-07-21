package com.sslproxy.schema.store

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sslproxy.schema.server.RunExecutor
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class RunStoreSuite extends FunSuite:
  test("creating a second active run for the same target is rejected") {
    val result = cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-run-store")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { stageDir =>
        for
          patchStore <- PatchStore.inMemory(stageDir)
          runStore <- RunStore.inMemory
          patch <- patchStore.create(
            "target-1",
            List(PatchUpload("001_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), 1))
          )
          first <- runStore.create(TriggerRunPayload(patch.id, "target-1"), patch, "test")
          second <- runStore.create(TriggerRunPayload(patch.id, "target-1"), patch, "test").attempt
        yield first -> second
      }
      .unsafeRunSync()

    val (_, second) = result
    assert(second.swap.exists(_.isInstanceOf[RunStore.ConcurrentRun]))
  }

  test("aborting a running run finalizes scripts and leaves patch retryable") {
    val result = cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-run-store")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { stageDir =>
        for
          patchStore <- PatchStore.inMemory(stageDir)
          runStore <- RunStore.inMemory
          validationStore <- ValidationStore.inMemory
          executor = RunExecutor.simulated(patchStore, runStore, validationStore, 75.millis)
          patch <- patchStore.create(
            "target-1",
            (1 to 20).toList.map(index =>
              PatchUpload(f"$index%03d_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), index)
            )
          )
          run <- runStore.create(TriggerRunPayload(patch.id, "target-1"), patch, "test")
          target = StoredTarget(
            Target(
              "target-1",
              "Target",
              "app",
              "dev",
              "jdbc:postgresql://localhost:5432/app",
              "now",
              "https://example.com/schema-migrator.git",
              "main",
              "sql",
              None,
              None
            ),
            None
          )
          fiber <- executor.run(target, run, patch).start
          _ <- waitUntil(runStore.get(run.id).map(_.exists(_.status == "running")), 100)
          _ <- runStore.abort(run.id)
          _ <- fiber.joinWithNever.timeout(1.second)
          storedRun <- runStore.get(run.id)
          storedPatch <- patchStore.get(patch.id)
        yield storedRun -> storedPatch
      }
      .unsafeRunSync()

    val (storedRun, storedPatch) = result
    assertEquals(storedRun.map(_.status), Some("aborted"))
    assertEquals(storedRun.map(_.scripts.map(_.status).distinct), Some(List("skipped")))
    assertEquals(storedPatch.map(_.status), Some("pending"))
  }

  test("resolving a failed run clears the failed gate without changing non-failed runs") {
    val result = cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-run-store")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { stageDir =>
        for
          patchStore <- PatchStore.inMemory(stageDir)
          runStore <- RunStore.inMemory
          patch <- patchStore.create(
            "target-1",
            List(PatchUpload("001_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), 1))
          )
          failedRun <- runStore.create(TriggerRunPayload(patch.id, "target-1"), patch, "test")
          _ <- runStore.startRun(failedRun.id)
          _ <- runStore.failRun(failedRun.id, "2026-07-11T12:00:00Z", patch.scripts.head.id, "failed")
          resolved <- runStore.resolveFailed(failedRun.id)
          pendingRun <- runStore.create(TriggerRunPayload(patch.id, "target-1"), patch, "test")
          pendingResolve <- runStore.resolveFailed(pendingRun.id)
        yield (resolved, pendingResolve)
      }
      .unsafeRunSync()

    val (resolved, pendingResolve) = result
    assertEquals(resolved.map(_.status), Some("aborted"))
    assertEquals(resolved.flatMap(_.ended_at), Some("2026-07-11T12:00:00Z"))
    assertEquals(pendingResolve, None)
  }

  test("run claims have one winner and stale tokens cannot mutate state") {
    val result = cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-run-store")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { stageDir =>
        for
          patchStore <- PatchStore.inMemory(stageDir)
          runStore <- RunStore.inMemory
          patch <- patchStore.create(
            "target-1",
            List(PatchUpload("001_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), 1))
          )
          run <- runStore.create(TriggerRunPayload(patch.id, "target-1"), patch, "test")
          claims <- List(
            runStore.claim(run.id, "owner-a", 40.millis),
            runStore.claim(run.id, "owner-b", 40.millis)
          ).parSequence
          first = claims.flatten.head
          _ <- IO.sleep(60.millis)
          second <- runStore.claim(run.id, "owner-b", 1.second).map(_.get)
          staleStart <- runStore.startRun(first)
          currentStart <- runStore.startRun(second)
          staleComplete <- runStore.completeRun(first, "2026-07-21T12:00:00Z", validationTriggered = true)
          currentComplete <- runStore.completeRun(second, "2026-07-21T12:00:01Z", validationTriggered = true)
        yield (claims.flatten.size, first.fence, second.fence, staleStart, currentStart, staleComplete, currentComplete)
      }
      .unsafeRunSync()

    assertEquals(result._1, 1)
    assert(result._3 > result._2)
    assertEquals(result._4, false)
    assertEquals(result._5, true)
    assertEquals(result._6, None)
    assertEquals(result._7.map(_.status), Some("completed"))
  }

  private def waitUntil(check: IO[Boolean], attempts: Int): IO[Unit] =
    check.flatMap {
      case true => IO.unit
      case false if attempts <= 0 => IO.raiseError(AssertionError("run did not become active"))
      case false => IO.sleep(10.millis) *> waitUntil(check, attempts - 1)
    }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
      ()
