package com.sslproxy.schema.effect

import cats.effect.Temporal
import cats.syntax.all.*

import scala.concurrent.duration.*

/** Retry policy for database connection operations.
  *
  * Replaces the hand-rolled retry loop in `JdbcSupport.retry` with a
  * composable, `Temporal[F]`-backed policy that supports exponential
  * backoff and connection-failure-only filtering.
  */
final case class RetryPolicy(
  maxAttempts: Int,
  baseDelay: FiniteDuration
):
  require(maxAttempts >= 1, "maxAttempts must be >= 1")
  require(baseDelay >= 0.millis, "baseDelay must be non-negative")

/** Retry combinators lifted into `Temporal[F]`. */
object Retry:

  /** Retry `fa` with exponential backoff when the error matches
    * `isRetryable`.  Non-retryable errors are re-raised immediately.
    *
    * @param policy    the retry budget (attempt limit + base delay)
    * @param isRetryable predicate that returns `true` for errors
    *                    that should be retried
    * @param onRetry   side-effect called before each retry sleep
    *                  (useful for logging); receives attempt number
    *                  (1-based).  Defaults to a no-op.
    */
  def withBackoff[F[_], A](
    policy: RetryPolicy,
    isRetryable: Throwable => Boolean,
    onRetry: Int => F[Unit]
  )(fa: F[A])(using F: Temporal[F]): F[A] =

    def loop(remaining: Int, used: Int): F[A] =
      fa.handleErrorWith { error =>
        if remaining > 1 && isRetryable(error) then
          val delay = policy.baseDelay * math.pow(2.0, used.toDouble).toLong
          onRetry(used + 1) *> F.sleep(delay) *> loop(remaining - 1, used + 1)
        else F.raiseError(error)
      }

    loop(policy.maxAttempts, 0)

  /** Convenience overload that omits the logging callback. */
  def withBackoff[F[_], A](
    policy: RetryPolicy,
    isRetryable: Throwable => Boolean
  )(fa: F[A])(using F: Temporal[F]): F[A] =
    withBackoff(policy, isRetryable, (_: Int) => F.unit)(fa)
