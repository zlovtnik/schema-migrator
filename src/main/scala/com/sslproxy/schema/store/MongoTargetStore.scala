package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.Indexes
import com.mongodb.client.{MongoClients, MongoCollection}
import com.sslproxy.schema.config.MongoConfig
import org.bson.Document

import java.util.UUID
import scala.jdk.CollectionConverters.*

object MongoTargetStore:
  def resource(config: MongoConfig): Resource[IO, TargetStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .evalMap { client =>
        val store = MongoTargetStore(
          client.getDatabase(config.database).getCollection(config.targetsCollection)
        )
        store.initialize.as(store: TargetStore)
      }

private final class MongoTargetStore(collection: MongoCollection[Document]) extends TargetStore:
  override def list: IO[List[Target]] =
    IO.blocking {
      collection
        .find()
        .sort(Indexes.ascending("created_at"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(storedFromDocument)
        .map(_.target)
    }

  override def create(payload: TargetPayload): IO[Target] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- nowString
      target = toTarget(id, now, payload)
      stored = StoredTarget(target, payload.password.filter(_.nonEmpty))
      _ <- IO.blocking(collection.insertOne(documentFor(stored, now))).void
    yield target

  override def get(id: String): IO[Option[Target]] =
    getStored(id).map(_.map(_.target))

  override def getStored(id: String): IO[Option[StoredTarget]] =
    IO.blocking(Option(collection.find(idFilter(id)).first()).map(storedFromDocument))

  override def update(id: String, payload: TargetPayload): IO[Option[Target]] =
    for
      now <- nowString
      updated <- IO.blocking {
        Option(collection.find(idFilter(id)).first()).flatMap { document =>
          val existing = storedFromDocument(document)
          val target = toTarget(id, existing.target.created_at, payload)
          val password = payload.password.filter(_.nonEmpty).orElse(existing.password)
          val result = collection.replaceOne(idFilter(id), documentFor(StoredTarget(target, password), now))
          if result.getMatchedCount > 0 then Some(target) else None
        }
      }
    yield updated

  override def delete(id: String): IO[Boolean] =
    IO.blocking(collection.deleteOne(idFilter(id)).getDeletedCount > 0)

  private[store] def initialize: IO[Unit] =
    IO.blocking(collection.createIndex(Indexes.ascending("created_at"))).void

  private def documentFor(stored: StoredTarget, updatedAt: String): Document =
    val target = stored.target
    val document = new Document()
      .append("_id", target.id)
      .append("label", target.label)
      .append("app_name", target.app_name)
      .append("env", target.env)
      .append("jdbc_url", target.jdbc_url)
      .append("created_at", target.created_at)
      .append("updated_at", updatedAt)
    stored.password.fold(document)(password => document.append("password", password))

  private def storedFromDocument(document: Document): StoredTarget =
    val target = Target(
      id = requiredString(document, "_id"),
      label = requiredString(document, "label"),
      app_name = requiredString(document, "app_name"),
      env = requiredString(document, "env"),
      jdbc_url = requiredString(document, "jdbc_url"),
      created_at = requiredString(document, "created_at")
    )
    StoredTarget(target, Option(document.getString("password")).filter(_.nonEmpty))

  private def requiredString(document: Document, field: String): String =
    Option(document.getString(field))
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"target document is missing required field '$field'"))

  private def idFilter(id: String): Document =
    new Document("_id", id)

  private def toTarget(id: String, createdAt: String, payload: TargetPayload): Target =
    Target(
      id = id,
      label = payload.label,
      app_name = payload.app_name,
      env = payload.env,
      jdbc_url = payload.jdbc_url,
      created_at = createdAt
    )

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)
