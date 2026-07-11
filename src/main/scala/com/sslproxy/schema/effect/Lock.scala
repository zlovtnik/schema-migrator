package com.sslproxy.schema.effect

import cats.effect.Sync
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** [[Lock]] algebras encapsulate the acquire/use/release lifecycle
  * of a distributed mutex, lifting the pattern used by
  * `MigrationEngine.apply` into a reusable effect.
  */
trait Lock[F[_]]:

  /** Acquire the lock, run `fa`, then unconditionally release. */
  def withLock[A](fa: F[A]): F[A]

object Lock:

  def apply[F[_]](using ev: Lock[F]): Lock[F] = ev

  /** Build a [[Lock]] from separate acquire and release actions.
    *
    * `release` is guaranteed to run after `fa` completes (success,
    * error, or cancellation).  Errors during release are caught and
    * only logged — they are not propagated.
    */
  def fromAcquireRelease[F[_]: Sync](acquire: F[Unit], release: F[Unit]): Lock[F] =
    new Lock[F]:
      private val F = Sync[F]
      private val logger = Slf4jLogger.getLogger[F]

      def withLock[A](fa: F[A]): F[A] =
        F.bracket(acquire)(_ => fa)(_ => release.handleErrorWith(ignore))

      private def ignore(error: Throwable): F[Unit] =
        logger.warn(error)("schema lock release failed")

  /** A no-op lock — the identity effect. */
  def none[F[_]]: Lock[F] =
    new Lock[F]:
      def withLock[A](fa: F[A]): F[A] = fa
