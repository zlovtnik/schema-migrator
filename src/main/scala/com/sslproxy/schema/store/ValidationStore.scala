package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref}

trait ValidationStore:
  def get(runId: String): IO[Option[ValidationResult]]
  def recordClean(runId: String, targetId: String): IO[ValidationResult]
  def rerun(run: Run): IO[ValidationResult]

object ValidationStore:
  def inMemory: IO[ValidationStore] =
    Ref.of[IO, Map[String, ValidationResult]](Map.empty).map(InMemoryValidationStore.apply)

private final class InMemoryValidationStore(ref: Ref[IO, Map[String, ValidationResult]]) extends ValidationStore:
  override def get(runId: String): IO[Option[ValidationResult]] =
    ref.get.map(_.get(runId))

  override def recordClean(runId: String, targetId: String): IO[ValidationResult] =
    for
      now <- nowString
      result = ValidationResult(
        run_id = runId,
        target_id = targetId,
        checked_at = now,
        invalid = Nil,
        status = "clean"
      )
      _ <- ref.update(_ + (runId -> result))
    yield result

  override def rerun(run: Run): IO[ValidationResult] =
    recordClean(run.id, run.target_id)

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)
