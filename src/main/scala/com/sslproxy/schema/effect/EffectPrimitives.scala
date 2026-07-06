package com.sslproxy.schema.effect

import cats.effect.{Async, Sync}

/** Small wrappers for bringing non-functional APIs into Cats Effect.
  *
  * These helpers are intentionally thin aliases around Cats Effect
  * primitives. They give the codebase one vocabulary for custom
  * synchronous, blocking, and callback-based side effects without
  * inventing a new runtime effect.
  */
object EffectPrimitives:

  /** Lift a fast, non-blocking side effect into `F`. */
  def synchronous[F[_]: Sync, A](thunk: => A): F[A] =
    Sync[F].delay(thunk)

  /** Lift a blocking side effect onto Cats Effect's blocking pool. */
  def blocking[F[_]: Sync, A](thunk: => A): F[A] =
    Sync[F].blocking(thunk)

  /** Lift a callback-style API into `F`.
    *
    * The callback must be invoked exactly once by the registered API.
    * For cancellable callback APIs, prefer `Async.async` with a
    * finalizer instead.
    */
  def asyncCallback[F[_]: Async, A](register: (Either[Throwable, A] => Unit) => Unit): F[A] =
    Async[F].async_[A](callback => register(callback))
