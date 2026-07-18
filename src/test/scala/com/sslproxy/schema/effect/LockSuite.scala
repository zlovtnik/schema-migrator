package com.sslproxy.schema.effect

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite

class LockSuite extends FunSuite:
  test("releases after the protected action fails") {
    val result = (for
      held <- Ref.of[IO, Boolean](false)
      acquire = held.modify {
        case false => true -> IO.unit
        case true => true -> IO.raiseError(IllegalStateException("lock already held"))
      }.flatten
      failure <- Lock.use(acquire, held.set(false))(IO.raiseError[Unit](RuntimeException("boom"))).attempt
      stillHeld <- held.get
    yield (failure.left.map(_.getMessage), stillHeld)).unsafeRunSync()

    assertEquals(result, (Left("boom"), false))
  }

  test("does not release when acquisition fails") {
    val result = (for
      held <- Ref.of[IO, Boolean](true)
      acquire = held.modify {
        case false => true -> IO.unit
        case true => true -> IO.raiseError(IllegalStateException("lock already held"))
      }.flatten
      failure <- Lock.use(acquire, held.set(false))(IO.unit).attempt
      stillHeld <- held.get
    yield (failure.left.map(_.getMessage), stillHeld)).unsafeRunSync()

    assertEquals(result, (Left("lock already held"), true))
  }
