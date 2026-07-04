package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, Sorts}
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.sslproxy.schema.config.MongoConfig
import io.circe.Json
import io.circe.parser.parse
import org.bson.Document

import java.util.UUID
import scala.jdk.CollectionConverters.*

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
  def mongo(config: MongoConfig, collectionName: String): Resource[IO, AuditStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .flatMap(client => mongo(config, collectionName, client))

  def mongo(config: MongoConfig, collectionName: String, client: MongoClient): Resource[IO, AuditStore] =
    Resource.eval {
      val store = MongoAuditStore(client.getDatabase(config.database).getCollection(collectionName))
      store.initialize.as(store: AuditStore)
    }

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

private final class MongoAuditStore(collection: MongoCollection[Document]) extends AuditStore:
  override def list(filters: AuditFilters): IO[List[AuditEvent]] =
    IO.blocking {
      collection
        .find(filterDocument(filters))
        .sort(Sorts.descending("at"))
        .limit(filters.limit)
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(fromDocument)
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
      _ <- IO.blocking(collection.insertOne(toDocument(event))).void
    yield event

  private[store] def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("at"))
      collection.createIndex(Indexes.ascending("actor", "at"))
      collection.createIndex(Indexes.ascending("entity_type", "entity_id", "at"))
      collection.createIndex(Indexes.ascending("target_id", "at"))
    }.void

  private def filterDocument(filters: AuditFilters): Document =
    val document = new Document()
    filters.actor.foreach(document.append("actor", _))
    filters.entityType.foreach(document.append("entity_type", _))
    filters.entityId.foreach(document.append("entity_id", _))
    filters.targetId.foreach(document.append("target_id", _))
    document

  private def toDocument(event: AuditEvent): Document =
    new Document()
      .append("_id", event.id)
      .append("actor", event.actor)
      .append("role", event.role)
      .append("action", event.action)
      .append("entity_type", event.entity_type)
      .append("entity_id", event.entity_id)
      .append("target_id", event.target_id.orNull)
      .append("at", event.at)
      .append("metadata_json", event.metadata.map(_.noSpaces).orNull)

  private def fromDocument(document: Document): AuditEvent =
    AuditEvent(
      id = requiredString(document, "_id"),
      actor = requiredString(document, "actor"),
      role = requiredString(document, "role"),
      action = requiredString(document, "action"),
      entity_type = requiredString(document, "entity_type"),
      entity_id = requiredString(document, "entity_id"),
      target_id = optionalString(document, "target_id"),
      at = requiredString(document, "at"),
      metadata = optionalString(document, "metadata_json").flatMap(text => parse(text).toOption)
    )

  private def requiredString(document: Document, field: String): String =
    optionalString(document, field)
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"audit document is missing required field '$field'"))

  private def optionalString(document: Document, field: String): Option[String] =
    Option(document.getString(field)).filter(_.nonEmpty)
