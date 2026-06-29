package com.sslproxy.schema.server

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sslproxy.schema.store.{
  PatchStore,
  PatchUpload,
  Run,
  RunEvent,
  RunStore,
  TargetPayload,
  TargetStore,
  TriggerRunPayload,
  ValidationStore
}
import fs2.text
import fs2.Stream
import org.http4s.*
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

class RunStreamSuite extends FunSuite:
  test("run stream emits named script completion and run events") {
    val result = cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-stream")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { stageDir =>
        for
          targetStore <- TargetStore.inMemory
          patchStore <- PatchStore.inMemory(stageDir)
          runStore <- RunStore.inMemory
          validationStore <- ValidationStore.inMemory
          target <- targetStore.create(targetPayload)
          patch <- patchStore.create(
            target.id,
            List(PatchUpload("001_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), 1))
          )
          run <- runStore.create(TriggerRunPayload(patch.id, target.id), patch, "test")
          subscribed <- Deferred[IO, Unit]
          streamStore = signalingRunStore(runStore, subscribed)
          routes = RunRoutes.routes(targetStore, patchStore, streamStore, validationStore).orNotFound
          response <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/runs/${run.id}/stream")))
          collect = response.body
            .through(text.utf8.decode)
            .scan("")(_ + _)
            .dropWhile(!_.contains("event: run:complete"))
            .take(1)
            .compile
            .lastOrError
            .timeout(2.seconds)
          body <- (collect, subscribed.get *> IO.cede *> runStore.runPatch(run, patchStore, validationStore)).parTupled
            .map(_._1)
        yield response.status -> body
      }
      .unsafeRunSync()

    val (status, body) = result
    assertEquals(status, Status.Ok)
    assert(body.contains("event: script:complete"), body)
    assert(body.contains("event: run:complete"), body)
  }

  test("run stream replays terminal state for late subscribers") {
    val result = cats.effect.Resource
      .make(IO.blocking(Files.createTempDirectory("schema-migrator-stream")))(path =>
        IO.blocking(deleteRecursively(path))
      )
      .use { stageDir =>
        for
          targetStore <- TargetStore.inMemory
          patchStore <- PatchStore.inMemory(stageDir)
          runStore <- RunStore.inMemory
          validationStore <- ValidationStore.inMemory
          target <- targetStore.create(targetPayload)
          patch <- patchStore.create(
            target.id,
            List(PatchUpload("001_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), 1))
          )
          run <- runStore.create(TriggerRunPayload(patch.id, target.id), patch, "test")
          _ <- runStore.runPatch(run, patchStore, validationStore)
          routes = RunRoutes.routes(targetStore, patchStore, runStore, validationStore).orNotFound
          response <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/runs/${run.id}/stream")))
          body <- response.body
            .through(text.utf8.decode)
            .scan("")(_ + _)
            .dropWhile(!_.contains("event: run:complete"))
            .take(1)
            .compile
            .lastOrError
            .timeout(2.seconds)
        yield response.status -> body
      }
      .unsafeRunSync()

    val (status, body) = result
    assertEquals(status, Status.Ok)
    assert(body.contains("event: run:complete"), body)
  }

  private def targetPayload: TargetPayload =
    TargetPayload(
      label = "Target",
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:postgresql://localhost:5432/app?user=app&sslmode=disable",
      password = Some("secret")
    )

  private def signalingRunStore(delegate: RunStore, subscribed: Deferred[IO, Unit]): RunStore =
    new RunStore:
      override def list(targetId: Option[String]): IO[List[Run]] =
        delegate.list(targetId)

      override def create(
        payload: TriggerRunPayload,
        patch: com.sslproxy.schema.store.Patch,
        triggeredBy: String
      ): IO[Run] =
        delegate.create(payload, patch, triggeredBy)

      override def get(id: String): IO[Option[Run]] =
        delegate.get(id)

      override def abort(id: String): IO[Option[Run]] =
        delegate.abort(id)

      override def runPatch(run: Run, patchStore: PatchStore, validationStore: ValidationStore): IO[Unit] =
        delegate.runPatch(run, patchStore, validationStore)

      override def events: Stream[IO, RunEvent] =
        Stream.eval_(subscribed.complete(()).void) ++ delegate.events

      override def runEvents(id: String): Resource[IO, Stream[IO, RunEvent]] =
        delegate.runEvents(id).evalTap(_ => subscribed.complete(()).void)

  private def deleteRecursively(path: java.nio.file.Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
