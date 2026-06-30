package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Ref}
import cats.syntax.all.*

import java.util.UUID

trait TargetStore:
  def list: IO[List[Target]]
  def create(payload: TargetPayload): IO[Target]
  def get(id: String): IO[Option[Target]]
  def getStored(id: String): IO[Option[StoredTarget]]
  def update(id: String, payload: TargetPayload): IO[Option[Target]]
  def delete(id: String): IO[Boolean]

object TargetStore:
  def inMemory: IO[TargetStore] =
    Ref.of[IO, Map[String, StoredTarget]](Map.empty).map(InMemoryTargetStore.apply)

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
          val target = toTarget(id, existing.target.created_at, payload)
          val password = payload.password.filter(_.nonEmpty).orElse(existing.password)
          val stored = StoredTarget(target, password)
          values.updated(id, stored) -> Some(target)
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
      created_at = createdAt
    )

  private def nowString: IO[String] =
    Clock[IO].realTimeInstant.map(_.toString)
