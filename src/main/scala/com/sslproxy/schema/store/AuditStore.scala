package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*
import io.circe.Json

import java.util.UUID

final case class AuditFilters(
  actor: Option[String] = None,
  entityType: Option[String] = None,
  entityId: Option[String] = None,
  targetId: Option[String] = None,
  limit: Int = 100
)

trait AuditStore:
  def list(filters: AuditFilters): IO[List[AuditEvent]]
  def record(
    actor: String,
    role: String,
    action: String,
    entityType: String,
    entityId: String,
    targetId: Option[String],
    metadata: Option[Json] = None
  ): IO[AuditEvent]

object AuditStore:
  def inMemory: IO[AuditStore] =
    Ref.of[IO, Map[String, AuditEvent]](Map.empty).map(InMemoryAuditStore.apply)

  def boundedLimit(value: Option[String]): Int =
    value.flatMap(raw => Either.catchNonFatal(raw.toInt).toOption).filter(_ > 0).fold(100)(_.min(500))

private final class InMemoryAuditStore(ref: Ref[IO, Map[String, AuditEvent]]) extends AuditStore:
  override def list(filters: AuditFilters): IO[List[AuditEvent]] =
    ref.get.map { events =>
      events.values.toList
        .filter(event => filters.actor.forall(_ == event.actor))
        .filter(event => filters.entityType.forall(_ == event.entity_type))
        .filter(event => filters.entityId.forall(_ == event.entity_id))
        .filter(event => filters.targetId.forall(value => event.target_id.contains(value)))
        .sortBy(_.at)
        .reverse
        .take(filters.limit)
    }

  override def record(
    actor: String,
    role: String,
    action: String,
    entityType: String,
    entityId: String,
    targetId: Option[String],
    metadata: Option[Json]
  ): IO[AuditEvent] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- Clock[IO].realTimeInstant.map(_.toString)
      event = AuditEvent(id, actor, role, action, entityType, entityId, targetId, now, metadata)
      _ <- ref.update(_ + (id -> event))
    yield event


