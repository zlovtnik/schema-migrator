package com.sslproxy.schema.store

import cats.effect.{Clock, IO, Resource}
import cats.syntax.all.*
import com.mongodb.client.model.{Indexes, Updates}
import com.mongodb.client.{MongoClient, MongoClients, MongoCollection}
import com.sslproxy.schema.config.MongoConfig
import com.sslproxy.schema.server.crypto.AesGcm
import org.bson.Document

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.*

object MongoTargetStore:
  def resource(config: MongoConfig, passwordKey: SecretKeySpec): Resource[IO, TargetStore] =
    Resource
      .make(IO.blocking(MongoClients.create(config.uri)))(client => IO.blocking(client.close()))
      .flatMap(client => resource(config, passwordKey, client))

  def resource(config: MongoConfig, passwordKey: SecretKeySpec, client: MongoClient): Resource[IO, TargetStore] =
    Resource.eval {
      val store = MongoTargetStore(
        client.getDatabase(config.database).getCollection(config.targetsCollection),
        new PasswordCrypto(passwordKey)
      )
      store.initialize.as(store: TargetStore)
    }

private final class MongoTargetStore(collection: MongoCollection[Document], passwordCrypto: PasswordCrypto)
    extends TargetStore:
  override def list: IO[List[Target]] =
    IO.blocking {
      collection
        .find()
        .sort(Indexes.ascending("created_at"))
        .into(new java.util.ArrayList[Document]())
        .asScala
        .toList
        .map(targetFromDocument)
    }

  override def create(payload: TargetPayload): IO[Target] =
    for
      id <- IO.delay(UUID.randomUUID().toString)
      now <- nowString
      target = toTarget(id, now, payload)
      stored = StoredTarget(target, payload.password.filter(_.nonEmpty))
      document <- documentFor(stored, now)
      _ <- IO.blocking(collection.insertOne(document)).void
    yield target

  override def get(id: String): IO[Option[Target]] =
    IO.blocking(Option(collection.find(idFilter(id)).first()).map(targetFromDocument))

  override def getStored(id: String): IO[Option[StoredTarget]] =
    IO.blocking(Option(collection.find(idFilter(id)).first())).flatMap(_.traverse(storedFromDocument))

  override def update(id: String, payload: TargetPayload): IO[Option[Target]] =
    IO.blocking(Option(collection.find(idFilter(id)).first())).flatMap {
      case None => IO.pure(None)
      case Some(document) =>
        nowString.flatMap { now =>
          val existingRepoUrl = optionalString(document, "repo_url").getOrElse("")
          val existingRepoBranch = optionalString(document, "repo_branch").getOrElse("main")
          val existingRepoSqlPath = optionalString(document, "repo_sql_path").getOrElse("sql")
          val repoChanged = existingRepoUrl != payload.repo_url ||
            existingRepoBranch != payload.repo_branch ||
            existingRepoSqlPath != payload.repo_sql_path

          val baseUpdates = List(
            Updates.set("label", payload.label),
            Updates.set("app_name", payload.app_name),
            Updates.set("env", payload.env),
            Updates.set("jdbc_url", payload.jdbc_url),
            Updates.set("repo_url", payload.repo_url),
            Updates.set("repo_branch", payload.repo_branch),
            Updates.set("repo_sql_path", payload.repo_sql_path),
            Updates.set("updated_at", now)
          )
          val repoChangeUpdates =
            if repoChanged then List(Updates.unset("last_synced_commit"), Updates.unset("last_synced_at"))
            else Nil
          val allUpdates = baseUpdates ++ repoChangeUpdates
          val update = Updates.combine(allUpdates*)
          val password = payload.password.filter(_.nonEmpty)

          val passwordUpdateIO = password match
            case Some(value) => passwordCrypto.encrypt(value).map { encrypted =>
              Updates.combine(
                update,
                Updates.set("password_ciphertext", encrypted.cipherTextBase64),
                Updates.set("password_iv", encrypted.ivBase64)
              )
            }
            case None => IO.pure(update)

          passwordUpdateIO.flatMap { passwordUpdate =>
            IO.blocking(collection.updateOne(idFilter(id), passwordUpdate)).flatMap { result =>
              IO.blocking(Option(collection.find(idFilter(id)).first()))
                .map(_.map(targetFromDocument).filter(_ => result.getMatchedCount > 0))
            }
          }
        }
    }

  override def delete(id: String): IO[Boolean] =
    IO.blocking(collection.deleteOne(idFilter(id)).getDeletedCount > 0)

  override def recordRepoSync(id: String, commitSha: String, syncedAt: String): IO[Boolean] =
    IO.blocking {
      collection
        .updateOne(
          idFilter(id),
          Updates.combine(
            Updates.set("last_synced_commit", commitSha),
            Updates.set("last_synced_at", syncedAt)
          )
        )
        .getMatchedCount > 0
    }

  private[store] def initialize: IO[Unit] =
    IO.blocking(collection.createIndex(Indexes.ascending("created_at"))).void

  private def documentFor(stored: StoredTarget, updatedAt: String): IO[Document] =
    val target = stored.target
    val document = new Document()
      .append("_id", target.id)
      .append("label", target.label)
      .append("app_name", target.app_name)
      .append("env", target.env)
      .append("jdbc_url", target.jdbc_url)
      .append("created_at", target.created_at)
      .append("repo_url", target.repo_url)
      .append("repo_branch", target.repo_branch)
      .append("repo_sql_path", target.repo_sql_path)
      .append("updated_at", updatedAt)
    target.last_synced_commit.foreach(document.append("last_synced_commit", _))
    target.last_synced_at.foreach(document.append("last_synced_at", _))
    stored.password.traverse(passwordCrypto.encrypt).map {
      case Some(encrypted) =>
        document
          .append("password_ciphertext", encrypted.cipherTextBase64)
          .append("password_iv", encrypted.ivBase64)
      case None => document
    }

  private def storedFromDocument(document: Document): IO[StoredTarget] =
    passwordCrypto.decrypt(document).map(password => StoredTarget(targetFromDocument(document), password))

  private def targetFromDocument(document: Document): Target =
    Target(
      id = requiredString(document, "_id"),
      label = requiredString(document, "label"),
      app_name = requiredString(document, "app_name"),
      env = requiredString(document, "env"),
      jdbc_url = requiredString(document, "jdbc_url"),
      created_at = requiredString(document, "created_at"),
      repo_url = optionalString(document, "repo_url").getOrElse(""),
      repo_branch = optionalString(document, "repo_branch").getOrElse("main"),
      repo_sql_path = optionalString(document, "repo_sql_path").getOrElse("sql"),
      last_synced_commit = optionalString(document, "last_synced_commit"),
      last_synced_at = optionalString(document, "last_synced_at")
    )

  private def requiredString(document: Document, field: String): String =
    Option(document.getString(field))
      .filter(_.nonEmpty)
      .getOrElse(throw IllegalStateException(s"target document is missing required field '$field'"))

  private def optionalString(document: Document, field: String): Option[String] =
    Option(document.getString(field)).filter(_.nonEmpty)

  private def idFilter(id: String): Document =
    new Document("_id", id)

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

private final case class EncryptedPassword(cipherTextBase64: String, ivBase64: String)

private final class PasswordCrypto(key: SecretKeySpec):
  def encrypt(password: String): IO[EncryptedPassword] =
    AesGcm.encrypt(key, password.getBytes(StandardCharsets.UTF_8)).map { case (cipherText, iv) =>
      EncryptedPassword(AesGcm.base64(cipherText), AesGcm.base64(iv))
    }

  def decrypt(document: Document): IO[Option[String]] =
    val cipherText = Option(document.getString("password_ciphertext")).filter(_.nonEmpty)
    val iv = Option(document.getString("password_iv")).filter(_.nonEmpty)
    (cipherText, iv) match
      case (Some(cipherTextBase64), Some(ivBase64)) =>
        val decoder = Base64.getDecoder
        AesGcm
          .decrypt(key, decoder.decode(cipherTextBase64), decoder.decode(ivBase64))
          .map(bytes => Some(String(bytes, StandardCharsets.UTF_8)))
      case (None, None) =>
        IO.pure(Option(document.getString("password")).filter(_.nonEmpty))
      case _ =>
        IO.raiseError(IllegalStateException("target document has incomplete encrypted password fields"))