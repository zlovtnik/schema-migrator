package com.sslproxy.schema.effect

import cats.effect.Sync
import cats.syntax.all.*
import com.sslproxy.schema.error.MigratorError

import java.sql.SQLException
import scala.concurrent.duration.FiniteDuration

/** Algebra for JDBC-related blocking operations.
  *
  * Encapsulates `Sync[F].blocking`, automatic
  * [[MigratorError.Apply]] conversion for [[SQLException]]s, and
  * [[Clock]]-backed duration measurement so that callers never reach
  * for `System.nanoTime()` or raw `IO.blocking` directly.
  */
trait Jdbc[F[_]]:

  /** Run a blocking JDBC call on the blocking thread-pool. */
  def blocking[A](thunk: => A): F[A]

  /** Like [[blocking]] but adapts any [[SQLException]] into a
    * [[MigratorError.Apply]] with the given `description`.
    */
  def checked[A](thunk: => A, description: String): F[A]

  /** Run `fa` and return its result paired with the elapsed
    * wall-clock duration.
    */
  def timed[A](fa: F[A]): F[(A, FiniteDuration)]

  /** Current monotonic time (nanosecond precision). */
  def monotonic: F[FiniteDuration]

object Jdbc:
  def apply[F[_]](using ev: Jdbc[F]): Jdbc[F] = ev

  given [F[_]: Sync]: Jdbc[F] with
    def blocking[A](thunk: => A): F[A] =
      Sync[F].blocking(thunk)

    def checked[A](thunk: => A, description: String): F[A] =
      Sync[F]
        .blocking(thunk)
        .adaptError { case e: SQLException =>
          MigratorError.Apply(s"$description: ${e.getMessage}", e)
        }

    def timed[A](fa: F[A]): F[(A, FiniteDuration)] =
      for
        start <- monotonic
        a     <- fa
        end   <- monotonic
      yield (a, end - start)

    def monotonic: F[FiniteDuration] =
      Sync[F].monotonic