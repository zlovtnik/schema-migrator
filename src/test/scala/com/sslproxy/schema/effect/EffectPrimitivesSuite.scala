package com.sslproxy.schema.effect

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import com.sslproxy.schema.config.DbKind
import munit.FunSuite

class EffectPrimitivesSuite extends FunSuite:
  test("lifts fast synchronous effects") {
    val result = EffectPrimitives.synchronous[IO, String]("tracking-id").unsafeRunSync()
    assertEquals(result, "tracking-id")
  }

  test("lifts blocking effects") {
    val result = EffectPrimitives.blocking[IO, Int](40 + 2).unsafeRunSync()
    assertEquals(result, 42)
  }

  test("lifts callback effects") {
    val result = EffectPrimitives
      .asyncCallback[IO, String](callback => callback(Right("delivered")))
      .unsafeRunSync()

    assertEquals(result, "delivered")
  }

  test("raises callback failures") {
    val failure = RuntimeException("callback failed")
    val thrown = intercept[RuntimeException] {
      EffectPrimitives
        .asyncCallback[IO, String](callback => callback(Left(failure)))
        .unsafeRunSync()
    }

    assertEquals(thrown.getMessage, "callback failed")
  }

  test("reads migration context through cats-mtl Ask") {
    type App[A] = Kleisli[IO, MigrationRunContext, A]

    given Ask[App, MigrationRunContext] = Ask.askForKleisli[IO, MigrationRunContext]
    val program = MigrationContext[App].current.map(_.targetId)
    val context = MigrationRunContext(
      runId = Some("run-1"),
      targetId = Some("target-1"),
      dbKind = DbKind.Postgres,
      customer = Some("fixture"),
      dryRun = false
    )

    assertEquals(program.run(context).unsafeRunSync(), Some("target-1"))
  }
