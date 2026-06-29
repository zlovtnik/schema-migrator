package com.sslproxy.schema.store

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class RunStoreSuite extends FunSuite:
  test("aborting a running run finalizes scripts and leaves patch retryable") {
    val result = cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-run-store")))(path => IO.blocking(deleteRecursively(path)))
      .use { stageDir =>
        for
          patchStore <- PatchStore.inMemory(stageDir)
          runStore <- RunStore.inMemory
          validationStore <- ValidationStore.inMemory
          patch <- patchStore.create(
            "target-1",
            (1 to 20).toList.map(index =>
              PatchUpload(f"$index%03d_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), index)
            )
          )
          run <- runStore.create(TriggerRunPayload(patch.id, "target-1"), patch, "test")
          fiber <- runStore.runPatch(run, patchStore, validationStore).start
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
