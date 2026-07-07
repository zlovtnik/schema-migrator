package com.sslproxy.schema.discovery

import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.store.{RepoSyncStore, SqlFileStore, StoredSqlFile, Target}
import munit.FunSuite
import org.eclipse.jgit.api.Git

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class RepoSyncServiceSuite extends FunSuite:
  test("sync loads repo files and records commit, then no-diff sync preserves counts") {
    val repo = Files.createTempDirectory("schema-migrator-sync-repo")
    val cache = Files.createTempDirectory("schema-migrator-sync-cache")
    try
      initRepo(repo, "select 1;")
      val result =
        (for
          sqlStore <- SqlFileStore.inMemory
          syncStore <- RepoSyncStore.inMemory
          service = RepoSyncService(sqlStore, syncStore, GitRepoLoader(), cache, 30)
          first <- service.sync("target-1", target(repo))
          second <- service.sync("target-1", target(repo))
          files <- sqlStore.list
          state <- syncStore.getSyncState("target-1")
        yield (first, second, files, state)).unsafeRunSync()

      val (first, second, files, state) = result
      assertEquals(first.added, 1)
      assertEquals(first.changed, 0)
      assertEquals(second.added, 0)
      assertEquals(second.changed, 0)
      assertEquals(second.unchanged, 1)
      assertEquals(files.map(_.path), List("tables/001_devices.sql"))
      assertEquals(state.map(_.commit_sha), Some(first.commitSha))
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
      val result =
        (for
          sqlStore <- SqlFileStore.inMemory
          _ <- sqlStore.replaceAll(List(existing))
          syncStore <- RepoSyncStore.inMemory
          service = RepoSyncService(sqlStore, syncStore, GitRepoLoader(), cache, 1)
          failed <- service.sync("target-1", target(Path.of("/does/not/exist"))).attempt
          files <- sqlStore.list
          state <- syncStore.getSyncState("target-1")
        yield (failed, files, state)).unsafeRunSync()

      val (failed, files, state) = result
      assert(failed.isLeft)
      assertEquals(files.map(_.path), List("tables/001_existing.sql"))
      assertEquals(state, None)
    finally deleteRecursively(cache)
  }

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
    finally git.close()

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      scala.util.Using.resource(Files.walk(path)) { stream =>
        import scala.jdk.CollectionConverters.*
        stream.iterator().asScala.toList.sortBy(_.toString).reverse.foreach(Files.deleteIfExists)
      }
