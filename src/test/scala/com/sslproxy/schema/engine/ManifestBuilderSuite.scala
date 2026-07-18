package com.sslproxy.schema.engine

import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.db.syntax.SqlDialect
import com.sslproxy.schema.discovery.SqlFile
import munit.FunSuite

import java.nio.file.Files

class ManifestBuilderSuite extends FunSuite:
  test("classifies Oracle procedures stored in functions folder as procedures") {
    val dir = Files.createTempDirectory("schema-migrator-manifest")
    val file = dir.resolve("001_demo_procedure.sql")
    Files.writeString(
      file,
      """-- object: demo_procedure
        |-- folder: functions
        |-- depends_on: -
        |
        |CREATE OR REPLACE
        |PROCEDURE DEMO_PROCEDURE AS
        |BEGIN
        |    NULL;
        |END DEMO_PROCEDURE;
        |/
        |""".stripMargin
    )

    try
      val manifest = ManifestBuilder(SqlDialect.Oracle)
        .build(List(SqlFile("functions", file, file.getFileName.toString, "functions/001_demo_procedure.sql")))
        .unsafeRunSync()

      assertEquals(manifest.head.kind, "procedure")
    finally
      deleteIfExists(file)
      deleteIfExists(dir)
  }

  test("classifies Oracle editionable procedures stored in functions folder as procedures") {
    List("EDITIONABLE", "NONEDITIONABLE").foreach { editionability =>
      val dir = Files.createTempDirectory("schema-migrator-manifest")
      val file = dir.resolve(s"001_${editionability.toLowerCase}_procedure.sql")
      Files.writeString(
        file,
        s"""-- object: demo_${editionability.toLowerCase}_procedure
           |-- folder: functions
           |-- depends_on: -
           |
           |CREATE OR REPLACE $editionability PROCEDURE DEMO_PROCEDURE AS
           |BEGIN
           |    NULL;
           |END DEMO_PROCEDURE;
           |/
           |""".stripMargin
      )

      try
        val manifest = ManifestBuilder(SqlDialect.Oracle)
          .build(List(SqlFile("functions", file, file.getFileName.toString, s"functions/${file.getFileName}")))
          .unsafeRunSync()

        assertEquals(manifest.head.kind, "procedure")
      finally
        deleteIfExists(file)
        deleteIfExists(dir)
    }
  }

  private def deleteIfExists(path: java.nio.file.Path): Unit =
    Files.deleteIfExists(path)
    ()
