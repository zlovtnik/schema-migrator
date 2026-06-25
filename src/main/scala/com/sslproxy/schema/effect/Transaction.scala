package com.sslproxy.schema.effect

import cats.effect.Sync
import cats.effect.kernel.syntax.all.*
import cats.syntax.all.*

import java.sql.{Connection, SQLException}

/** A transactional boundary effect for JDBC sessions.
  *
  * For databases that support DDL transactions (Postgres) this
  * manages `setAutoCommit(false)` before the work and restores it
  * after. For Oracle, where DDL auto-commits, implementations
  * should be a no-op identity.
  *
  * The caller is still responsible for explicit `commit()` /
  * `rollback()` calls inside the transaction body, since
  * recording and error-handling logic is interleaved with SQL
  * execution in the existing codebase.
  */
trait Transaction[F[_]]:

  /** Run `fa` with auto-commit set to `autoCommit`.
    *
    * Restores auto-commit to the original value on completion
    * (success or failure).
    */
  def apply[A](fa: F[A]): F[A]

object Transaction:

  /** No-op transaction — the identity effect. */
  def none[F[_]]: Transaction[F] =
    new Transaction[F]:
      def apply[A](fa: F[A]): F[A] = fa

  /** Create a [[Transaction]] that sets auto-commit on the JDBC
    * [[Connection]] before `fa` and restores it afterwards.
    */
  def fromConnection[F[_]: Sync](connection: Connection): Transaction[F] =
    new Transaction[F]:
      private val F = Sync[F]

      def apply[A](fa: F[A]): F[A] =
        for
          saved <- F.blocking {
            val current = connection.getAutoCommit
            connection.setAutoCommit(false)
            current
          }
          result <- fa.guarantee(F.blocking {
            if !connection.getAutoCommit then connection.rollback()
            connection.setAutoCommit(saved)
          })
        yield result
