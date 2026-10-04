package com.sslproxy.schema.validation

import com.sslproxy.schema.discovery.SqlFile
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

class RollbackValidatorSuite extends FunSuite:
  private def withRoot(test: Path => Unit): Unit =
    val root = Files.createTempDirectory("rollback-validation-")
    try test(root)
    finally
      scala.util.Using.resource(Files.walk(root)) { paths =>
        paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      }

  private def source(root: Path, rollback: String, content: Boolean = false): SqlFile =
    val path = root.resolve("tables/create.sql")
    Files.createDirectories(path.getParent)
    val sql = s"-- rollback: $rollback\nselect 1;"
    Files.writeString(path, sql)
    SqlFile("tables", path, "create.sql", "tables/create.sql", Option.when(content)(sql))

  test("valid root-relative and file-relative rollback SQL stays available") {
    withRoot { root =>
      Files.createDirectories(root.resolve("rollbacks"))
      Files.writeString(root.resolve("rollbacks/drop.sql"), "drop table example;")
      val file = source(root, "rollbacks/drop.sql")
      assertEquals(RollbackValidator.validate(List(file)), Nil)
      assertEquals(RollbackValidator.readRollback(file, "../rollbacks/drop.sql", root), "drop table example;")
      assertEquals(RollbackValidator.readRollback(file, "sql/rollbacks/drop.sql", root), "drop table example;")
    }
  }

  test("absolute, escaping, symlink, directory and special-file paths are rejected") {
    withRoot { root =>
      val file = source(root, "/dev/zero")
      Files.createSymbolicLink(root.resolve("escape.sql"), Path.of("/dev/zero"))
      List("/dev/zero", "../../../dev/zero", "escape.sql", "tables", "C:\\secret.sql").foreach { ref =>
        assertEquals(RollbackValidator.resolveExistingRollbackPath(file, ref, root), None, ref)
      }
      assert(RollbackValidator.validate(List(file)).nonEmpty)
    }
  }

  test("oversized rollback files are bounded and empty files remain invalid") {
    withRoot { root =>
      val file = source(root, "big.sql")
      val big = root.resolve("big.sql")
      scala.util.Using.resource(new java.io.RandomAccessFile(big.toFile, "rw")) { handle =>
        handle.setLength(RollbackValidator.MaxRollbackBytes.toLong + 1)
      }
      intercept[IllegalArgumentException](RollbackValidator.readRollback(file, "big.sql", root))
      Files.writeString(big, "")
      assert(RollbackValidator.validate(List(file)).exists(_.contains("is empty")))
    }
  }

  test("stored validation uses supplied content and never falls back to local files") {
    withRoot { root =>
      val file = source(root, "rollbacks/drop.sql", content = true)
      val rollback = SqlFile("rollbacks", root.resolve("rollbacks/drop.sql"), "drop.sql", "rollbacks/drop.sql", Some("drop table example;"))
      assertEquals(RollbackValidator.validate(List(file), List(file, rollback)), Nil)
      assert(RollbackValidator.validate(List(file)).nonEmpty)
      val absolute = file.copy(content = Some("-- rollback: /dev/zero\nselect 1;"))
      assert(RollbackValidator.validate(List(absolute), List(absolute, rollback)).nonEmpty)
    }
  }
