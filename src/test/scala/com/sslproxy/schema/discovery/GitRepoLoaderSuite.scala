package com.sslproxy.schema.discovery

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.eclipse.jgit.api.Git

import java.nio.file.{Files, Path}

class GitRepoLoaderSuite extends FunSuite:
  test("resolveSqlRoot rejects missing sql folder") {
    val root = Files.createTempDirectory("schema-migrator-loader")
    try
      val result = GitRepoLoader().resolveSqlRoot(root, "sql")
      assertEquals(result.left.map(_.contains("does not exist")), Left(true))
    finally deleteRecursively(root)
  }

  test("loadFiles reads sql files with normalized folders and hashes") {
    val root = Files.createTempDirectory("schema-migrator-loader")
    try
      val sqlRoot = root.resolve("sql")
      Files.createDirectories(sqlRoot.resolve("tables"))
      Files.writeString(sqlRoot.resolve("tables").resolve("001_devices.sql"), "select 1;")
      Files.writeString(sqlRoot.resolve("README.md"), "ignored")

      val loader = GitRepoLoader()
      val result = for
        resolved <- cats.effect.IO.fromEither(loader.resolveSqlRoot(root, "sql").left.map(IllegalArgumentException(_)))
        files <- loader.loadFiles(resolved)
      yield files

      val files = result.unsafeRunSync()
      assertEquals(files.map(_.path), List("tables/001_devices.sql"))
      assertEquals(files.map(_.folder), List("tables"))
      assert(files.head.sha256.nonEmpty)
    finally deleteRecursively(root)
  }

  test("clone reads head commit from a local git repository") {
    val repo = Files.createTempDirectory("schema-migrator-loader-repo")
    val cache = Files.createTempDirectory("schema-migrator-loader-cache")
    try
      initRepo(repo, "select 1;")
      val loader = GitRepoLoader()
      val commit = loader
        .cloned(repo.toString, "main", cache)(root => loader.headCommit(root))
        .unsafeRunSync()

      assertEquals(commit.length, 40)
      assertEquals(scala.util.Using.resource(Files.list(cache))(_.count()), 0L)
    finally
      deleteRecursively(repo)
      deleteRecursively(cache)
  }

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
