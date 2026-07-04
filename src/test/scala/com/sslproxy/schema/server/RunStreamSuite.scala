package com.sslproxy.schema.server

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.sslproxy.schema.TestSqlSupport
import com.sslproxy.schema.config.{DbKind, MigratorConfig, ServerConfig}
import com.sslproxy.schema.store.{
  AuditStore,
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
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

class RunStreamSuite extends FunSuite with TestSqlSupport:
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
          auditStore <- AuditStore.inMemory
          executor = RunExecutor.simulated(patchStore, runStore, validationStore)
          target <- targetStore.create(targetPayload)
          patch <- patchStore.create(
            target.id,
            List(PatchUpload("001_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), 1))
          )
          run <- runStore.create(TriggerRunPayload(patch.id, target.id), patch, "test")
          subscribed <- Deferred[IO, Unit]
          streamStore = signalingRunStore(runStore, subscribed)
          routes = RunRoutes.routes(routeConfig(stageDir), targetStore, patchStore, streamStore, validationStore, auditStore, executor).orNotFound
          response <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/runs/${run.id}/stream")))
          collect = response.body
            .through(text.utf8.decode)
            .scan("")(_ + _)
            .dropWhile(!_.contains("event: run:complete"))
            .take(1)
            .compile
            .lastOrError
            .timeout(2.seconds)
          storedTarget <- targetStore.getStored(target.id).map(_.get)
          body <- (collect, subscribed.get *> IO.cede *> executor.run(storedTarget, run, patch)).parTupled
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
          auditStore <- AuditStore.inMemory
          executor = RunExecutor.simulated(patchStore, runStore, validationStore)
          target <- targetStore.create(targetPayload)
          patch <- patchStore.create(
            target.id,
            List(PatchUpload("001_test.sql", "select 1;".getBytes(StandardCharsets.UTF_8), 1))
          )
          run <- runStore.create(TriggerRunPayload(patch.id, target.id), patch, "test")
          storedTarget <- targetStore.getStored(target.id).map(_.get)
          _ <- executor.run(storedTarget, run, patch)
          routes = RunRoutes.routes(routeConfig(stageDir), targetStore, patchStore, runStore, validationStore, auditStore, executor).orNotFound
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

      override def startRun(id: String): IO[Boolean] =
        delegate.startRun(id)

      override def completeRun(id: String, endedAt: String, validationTriggered: Boolean): IO[Option[Run]] =
        delegate.completeRun(id, endedAt, validationTriggered)

      override def failRun(id: String, endedAt: String, failedScriptId: String, reason: String): IO[Option[Run]] =
        delegate.failRun(id, endedAt, failedScriptId, reason)

      override def currentStatus(id: String): IO[Option[String]] =
        delegate.currentStatus(id)

      override def scriptStarted(id: String, scriptId: String, filename: String, order: Int, total: Int): IO[Boolean] =
        delegate.scriptStarted(id, scriptId, filename, order, total)

      override def scriptCompleted(id: String, scriptId: String, filename: String, durationMs: Long): IO[Boolean] =
        delegate.scriptCompleted(id, scriptId, filename, durationMs)

      override def scriptFailed(
        id: String,
        scriptId: String,
        filename: String,
        error: com.sslproxy.schema.store.ScriptError,
        durationMs: Long
      ): IO[Boolean] =
        delegate.scriptFailed(id, scriptId, filename, error, durationMs)

      override def log(runId: String, level: String, message: String): IO[Unit] =
        delegate.log(runId, level, message)

      override def events: Stream[IO, RunEvent] =
        Stream.eval_(subscribed.complete(()).void) ++ delegate.events

      override def runEvents(id: String): Resource[IO, Stream[IO, RunEvent]] =
        delegate.runEvents(id).evalTap(_ => subscribed.complete(()).void)

  private def routeConfig(stageDir: Path): MigratorConfig =
    MigratorConfig(
      dbKind = DbKind.Postgres,
      databaseUrl = None,
      sqlDir = stageDir.resolve("sql"),
      dryRun = false,
      verbose = false,
      continueOnError = false,
      connectRetries = 0,
      connectRetryBackoff = 1.second,
      oracleWallet = None,
      oracleTnsAlias = None,
      oracleUser = None,
      oraclePasswordFile = None,
      json = false,
      server = ServerConfig(
        host = "127.0.0.1",
        port = 8080,
        corsOrigins = Set.empty,
        encryptKeyBase64 = None,
        jwtSecret = "jwt",
        devAuthSecret = "dev",
        dbTestAllowedHosts = Set("*"),
        patchStageDir = stageDir
      )
    )
