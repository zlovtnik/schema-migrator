package com.sslproxy.schema.discovery

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.store.{SqlFileStore, StoredSqlFile, Target, TargetPayload, TargetStore}
import munit.FunSuite
import org.eclipse.jgit.api.Git

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

class RepoSyncServiceSuite extends FunSuite:
  test("sync loads repo files and records commit, then no-diff sync preserves counts") {
    val repo = Files.createTempDirectory("schema-migrator-sync-repo")
    val cache = Files.createTempDirectory("schema-migrator-sync-cache")
    try
      initRepo(repo, "select 1;")
      val payload = TargetPayload(
        label = "Target",
        app_name = "app",
        env = "dev",
        jdbc_url = "jdbc:postgresql://localhost:5432/app?user=app",
        password = None,
        repo_url = repo.toString,
        repo_branch = "main",
        repo_sql_path = "sql"
      )
      val io = SqlFileStore.inMemory.flatMap { sqlStore =>
        TargetStore.inMemory.flatMap { targetStore =>
          targetStore.create(payload).flatMap { createdTarget =>
            val storedTarget = target(repo).copy(id = createdTarget.id)
            val service = RepoSyncService(sqlStore, GitRepoLoader(), cache, 30, targetStore)
            service.sync(createdTarget.id, storedTarget).flatMap { first =>
              service.sync(createdTarget.id, storedTarget).flatMap { second =>
                sqlStore.list(createdTarget.id).flatMap { files =>
                  targetStore.get(createdTarget.id).map { targetAfter =>
                    (first, second, files, targetAfter)
                  }
                }
              }
            }
          }
        }
      }
      val (first, second, files, targetAfter) = io.unsafeRunSync()
      assertEquals(first.added, 1)
      assertEquals(first.changed, 0)
      assertEquals(second.added, 0)
      assertEquals(second.changed, 0)
      assertEquals(second.unchanged, 1)
      assertEquals[List[String], List[String]](files.map(_.path), List("tables/001_devices.sql"))
      targetAfter match
        case Some(t) =>
          assertEquals[Option[String], Option[String]](t.last_synced_commit, Some(first.commitSha))
          assert(t.last_synced_at.isDefined)
        case None => fail("target should exist after sync")
    finally
      deleteRecursively(repo)
      deleteRecursively(cache)
  }

  test("failed sync leaves existing SQL files untouched") {
    val cache = Files.createTempDirectory("schema-migrator-sync-cache")
    try
      val existing = StoredSqlFile.fromBytes(
        "tables/001_existing.sql",
        "tables",
        "001_existing.sql",
        "select 1;".getBytes(StandardCharsets.UTF_8),
        "2026-07-02T12:00:00Z"
      )
      val io = SqlFileStore.inMemory.flatMap { sqlStore =>
        sqlStore.replaceAll("target-1", List(existing)).flatMap { _ =>
          TargetStore.inMemory.flatMap { targetStore =>
            val service = RepoSyncService(sqlStore, GitRepoLoader(), cache, 1, targetStore)
            service.sync("target-1", target(Path.of("/does/not/exist"))).attempt.flatMap { failed =>
              sqlStore.list("target-1").map { files =>
                (failed, files)
              }
            }
          }
        }
      }
      val (failed, files) = io.unsafeRunSync()
      assert(failed.isLeft)
      assertEquals[List[String], List[String]](files.map(_.path), List("tables/001_existing.sql"))
    finally deleteRecursively(cache)
  }

  test("canceled metadata persistence restores the previous SQL files") {
    val repo = Files.createTempDirectory("schema-migrator-sync-repo")
    val cache = Files.createTempDirectory("schema-migrator-sync-cache")
    try
      initRepo(repo, "select 2;")
      val existing = StoredSqlFile.fromBytes(
        "tables/001_existing.sql",
        "tables",
        "001_existing.sql",
        "select 1;".getBytes(StandardCharsets.UTF_8),
        "2026-07-02T12:00:00Z"
      )
      val result = (for
        sqlStore <- SqlFileStore.inMemory
        targetStore <- TargetStore.inMemory
        created <- targetStore.create(targetPayload(repo))
        _ <- sqlStore.replaceAll(created.id, List(existing))
        metadataStarted <- Deferred[IO, Unit]
        blockingTargetStore = new TargetStore:
          override def list = targetStore.list
          override def create(payload: TargetPayload) = targetStore.create(payload)
          override def get(id: String) = targetStore.get(id)
          override def getStored(id: String) = targetStore.getStored(id)
          override def update(id: String, payload: TargetPayload) = targetStore.update(id, payload)
          override def recordRepoSync(id: String, commitSha: String, syncedAt: String) =
            metadataStarted.complete(()).void *> IO.never
          override def clearRepoSync(id: String) = targetStore.clearRepoSync(id)
          override def delete(id: String) = targetStore.delete(id)
        service = RepoSyncService(sqlStore, GitRepoLoader(), cache, 30, blockingTargetStore)
        fiber <- service.sync(created.id, target(repo).copy(id = created.id)).start
        _ <- metadataStarted.get.timeout(5.seconds)
        _ <- fiber.cancel
        files <- sqlStore.list(created.id)
        storedTarget <- targetStore.get(created.id)
      yield (files.map(_.path), storedTarget.flatMap(_.last_synced_commit))).unsafeRunSync()

      assertEquals(result, (List("tables/001_existing.sql"), None))
    finally
      deleteRecursively(repo)
      deleteRecursively(cache)
  }

  private def targetPayload(repo: Path): TargetPayload =
    TargetPayload(
      label = "Target",
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:postgresql://localhost:5432/app?user=app",
      password = None,
      repo_url = repo.toString,
      repo_branch = "main",
      repo_sql_path = "sql"
    )

  private def target(repo: Path): Target =
    Target(
      id = "target-1",
      label = "Target",
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:postgresql://localhost:5432/app?user=app",
      created_at = "2026-07-02T12:00:00Z",
      repo_url = repo.toString,
      repo_branch = "main",
      repo_sql_path = "sql",
      last_synced_commit = None,
      last_synced_at = None
    )

  private def initRepo(root: Path, sql: String): Unit =
    Files.createDirectories(root.resolve("sql").resolve("tables"))
    Files.writeString(root.resolve("sql").resolve("tables").resolve("001_devices.sql"), sql)
    val git = Git.init().setInitialBranch("main").setDirectory(root.toFile).call()
    try
      git.add().addFilepattern(".").call()
      git.commit().setMessage("initial").setAuthor("Test", "test@example.com").call()
      ()
    finally git.close()

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      scala.util.Using.resource(Files.walk(path)) { stream =>
        import scala.jdk.CollectionConverters.*
        stream.iterator().asScala.toList.sortBy(_.toString).reverse.foreach(Files.deleteIfExists)
      }
