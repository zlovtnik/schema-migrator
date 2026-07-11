package com.sslproxy.schema.store

import cats.effect.{Clock, IO}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, ReplaceOptions}
import com.mongodb.client.{MongoClient, MongoCollection}
import com.sslproxy.schema.config.{MongoConfig, ServerConfig}
import org.bson.Document

object KeycloakConfigStore:
  private[store] val DocumentId = "keycloak"

  def persist(config: ServerConfig, mongoConfig: MongoConfig, collectionName: String, client: MongoClient): IO[Unit] =
    val collection = client.getDatabase(mongoConfig.database).getCollection(collectionName)
    for
      now <- Clock[IO].realTimeInstant.map(_.toString)
      store = MongoKeycloakConfigStore(collection)
      _ <- store.initialize
      _ <- store.save(config, now)
    yield ()

private final class MongoKeycloakConfigStore(collection: MongoCollection[Document]):
  import KeycloakConfigStore.*

  def initialize: IO[Unit] =
    IO.blocking {
      collection.createIndex(Indexes.ascending("updated_at"))
      collection.createIndex(Indexes.ascending("enabled"))
    }.void

  def save(config: ServerConfig, updatedAt: String): IO[Unit] =
    IO.blocking {
      collection.replaceOne(
        new Document("_id", DocumentId),
        toDocument(config, updatedAt),
        ReplaceOptions().upsert(true)
      )
    }.void

  private def toDocument(config: ServerConfig, updatedAt: String): Document =
    new Document()
      .append("_id", DocumentId)
      .append("enabled", Boolean.box(config.keycloakEnabled))
      .append("issuer", config.keycloakIssuer.orNull)
      .append("jwks_uri", config.keycloakJwksUri.orNull)
      .append("client_id", config.keycloakClientId.orNull)
      .append("audience", config.keycloakAudience.orNull)
      .append("updated_at", updatedAt)
