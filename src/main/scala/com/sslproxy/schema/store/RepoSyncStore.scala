package com.sslproxy.schema.store

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.sslproxy.schema.config.MongoConfig
import org.bson.Document

final case class RepoSyncState(target_id: String, commit_sha: String, synced_at: String)

trait RepoSyncStore:
  def getSyncState(targetId: String): IO[Option[RepoSyncState]]
  def recordSync(targetId: String, commitSha: String, syncedAt: String): IO[Unit]
  def clear(targetId: String): IO[Unit]

object RepoSyncStore:
  def inMemory: IO[RepoSyncStore] =
    Ref.of[IO, Map[String, RepoSyncState]](Map.empty).map(InMemoryRepoSyncStore(_))

  def mongo(config: MongoConfig, collectionName: String): Resource[IO, RepoSyncStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .flatMap(client => mongo(config, collectionName, client))

  def mongo(config: MongoConfig, collectionName: String, client: MongoClient): Resource[IO, RepoSyncStore] =
    Resource.eval {
      val store = MongoRepoSyncStore(
        client.getDatabase(config.database).getCollection(collectionName)
      )
      store.initialize.as(store: RepoSyncStore)
    }

private final class InMemoryRepoSyncStore(ref: Ref[IO, Map[String, RepoSyncState]]) extends RepoSyncStore:
  override def getSyncState(targetId: String): IO[Option[RepoSyncState]] =
    ref.get.map(_.get(targetId))

  override def recordSync(targetId: String, commitSha: String, syncedAt: String): IO[Unit] =
    ref.update(_ + (targetId -> RepoSyncState(targetId, commitSha, syncedAt)))

  override def clear(targetId: String): IO[Unit] =
    ref.update(_ - targetId)

private final class MongoRepoSyncStore(collection: MongoCollection[Document]) extends RepoSyncStore:
  override def getSyncState(targetId: String): IO[Option[RepoSyncState]] =
    IO.blocking(Option(collection.find(idFilter(targetId)).first()).map(fromDocument))

  override def recordSync(targetId: String, commitSha: String, syncedAt: String): IO[Unit] =
    IO.blocking {
      collection.replaceOne(
        idFilter(targetId),
        new Document()
          .append("_id", targetId)
          .append("target_id", targetId)
          .append("commit_sha", commitSha)
          .append("synced_at", syncedAt),
        ReplaceOptions().upsert(true)
      )
      ()
    }

  override def clear(targetId: String): IO[Unit] =
    IO.blocking(collection.deleteOne(idFilter(targetId))).void

  private[store] def initialize: IO[Unit] =
    IO.unit

  private def fromDocument(document: Document): RepoSyncState =
    RepoSyncState(
      target_id = requiredString(document, "target_id"),
      commit_sha = requiredString(document, "commit_sha"),
      synced_at = requiredString(document, "synced_at")
    )

  private def requiredString(document: Document, field: String): String =
    Option(document.getString(field))
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"repo_sync_state document is missing required field '$field'"))

  private def idFilter(targetId: String): Document =
    new Document("_id", targetId)
