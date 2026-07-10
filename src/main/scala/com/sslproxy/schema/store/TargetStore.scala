package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref, Resource}
import cats.syntax.all.*
import com.mongodb.client.MongoClient
import com.sslproxy.schema.config.MongoConfig

import java.util.UUID
import javax.crypto.spec.SecretKeySpec

trait TargetStore:
  def list: IO[List[Target]]
  def create(payload: TargetPayload): IO[Target]
  def get(id: String): IO[Option[Target]]
  def getStored(id: String): IO[Option[StoredTarget]]
  def update(id: String, payload: TargetPayload): IO[Option[Target]]
  def recordRepoSync(id: String, commitSha: String, syncedAt: String): IO[Boolean]
  def delete(id: String): IO[Boolean]

object TargetStore:
  def inMemory: IO[TargetStore] =
    Ref.of[IO, Map[String, StoredTarget]](Map.empty).map(InMemoryTargetStore.apply)

  def mongo(config: MongoConfig, passwordKey: SecretKeySpec): Resource[IO, TargetStore] =
    MongoTargetStore.resource(config, passwordKey)

  def mongo(config: MongoConfig, passwordKey: SecretKeySpec, client: MongoClient): Resource[IO, TargetStore] =
    MongoTargetStore.resource(config, passwordKey, client)

private final class InMemoryTargetStore(ref: Ref[IO, Map[String, StoredTarget]]) extends TargetStore:
  override def list: IO[List[Target]] =
    ref.get.map(_.values.map(_.target).toList.sortBy(_.created_at))

  override def create(payload: TargetPayload): IO[Target] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- nowString
      target = toTarget(id, now, payload)
      stored = StoredTarget(target, payload.password.filter(_.nonEmpty))
      _ <- ref.update(_ + (id -> stored))
    yield target

  override def get(id: String): IO[Option[Target]] =
    getStored(id).map(_.map(_.target))

  override def getStored(id: String): IO[Option[StoredTarget]] =
    ref.get.map(_.get(id))

  override def update(id: String, payload: TargetPayload): IO[Option[Target]] =
    ref.modify { values =>
      values.get(id) match
        case None => values -> Option.empty[Target]
        case Some(existing) =>
          val repoChanged = existing.target.repo_url != payload.repo_url ||
            existing.target.repo_branch != payload.repo_branch ||
            existing.target.repo_sql_path != payload.repo_sql_path
          val target = toTarget(id, existing.target.created_at, payload).copy(
            last_synced_commit = if repoChanged then None else existing.target.last_synced_commit,
            last_synced_at = if repoChanged then None else existing.target.last_synced_at
          )
          val password = payload.password.filter(_.nonEmpty).orElse(existing.password)
          val stored = StoredTarget(target, password)
          values.updated(id, stored) -> Some(target)
    }

  override def recordRepoSync(id: String, commitSha: String, syncedAt: String): IO[Boolean] =
    ref.modify { values =>
      values.get(id) match
        case None => values -> false
        case Some(existing) =>
          val updated = existing.copy(
            target = existing.target.copy(
              last_synced_commit = Some(commitSha),
              last_synced_at = Some(syncedAt)
            )
          )
          values.updated(id, updated) -> true
    }

  override def delete(id: String): IO[Boolean] =
    ref.modify(values => (values - id) -> values.contains(id))

  private def toTarget(id: String, createdAt: String, payload: TargetPayload): Target =
    Target(
      id = id,
      label = payload.label,
      app_name = payload.app_name,
      env = payload.env,
      jdbc_url = payload.jdbc_url,
      created_at = createdAt,
      repo_url = payload.repo_url,
      repo_branch = payload.repo_branch,
      repo_sql_path = payload.repo_sql_path,
      last_synced_commit = None,
      last_synced_at = None
    )

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)
