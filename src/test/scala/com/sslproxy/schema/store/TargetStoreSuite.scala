package com.sslproxy.schema.store

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.sslproxy.schema.config.StateStoreConfig
import com.sslproxy.schema.server.crypto.AesGcm
import munit.FunSuite

class TargetStoreSuite extends FunSuite:
  targetStoreContract("in-memory", Resource.eval(TargetStore.inMemory))

  sys.env.get("BEDROCK_STATE_DB_TEST_URL").foreach { url =>
    val config = StateStoreConfig(
      url,
      sys.env.getOrElse("BEDROCK_STATE_DB_TEST_USER", "migrator"),
      sys.env.getOrElse("BEDROCK_STATE_DB_TEST_PASSWORD", "migrator")
    )
    targetStoreContract("tidb", tidbResource(config))
  }

  private val passwordKey =
    AesGcm.keyFromBase64("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").toOption.get

  private def targetStoreContract(name: String, storeResource: Resource[IO, TargetStore]): Unit =
    test(s"$name target store supports target CRUD and password preservation") {
      val result = storeResource
        .use { store =>
          for
            created <- store.create(targetPayload("Alpha", Some("first")))
            stored <- store.getStored(created.id)
            listed <- store.list
            updated <- store.update(created.id, targetPayload("Beta", None))
            storedAfterEmptyPasswordUpdate <- store.getStored(created.id)
            updatedWithPassword <- store.update(created.id, targetPayload("Gamma", Some("second")))
            storedAfterPasswordUpdate <- store.getStored(created.id)
            syncRecorded <- store.recordRepoSync(created.id, "abc123", "2026-07-02T12:00:00Z")
            synced <- store.get(created.id)
            missingUpdate <- store.update("missing", targetPayload("Missing", Some("secret")))
            deleted <- store.delete(created.id)
            missingDelete <- store.delete(created.id)
            missingFetch <- store.get(created.id)
          yield (
            created,
            stored,
            listed,
            updated,
            storedAfterEmptyPasswordUpdate,
            updatedWithPassword,
            storedAfterPasswordUpdate,
            syncRecorded,
            synced,
            missingUpdate,
            deleted,
            missingDelete,
            missingFetch
          )
        }
        .unsafeRunSync()

      val (
        created,
        stored,
        listed,
        updated,
        storedAfterEmptyPasswordUpdate,
        updatedWithPassword,
        storedAfterPasswordUpdate,
        syncRecorded,
        synced,
        missingUpdate,
        deleted,
        missingDelete,
        missingFetch
      ) = result

      assertEquals(created.label, "Alpha")
      assertEquals(stored.map(_.password), Some(Some("first")))
      assert(listed.exists(_.id == created.id))
      assertEquals(updated.map(_.label), Some("Beta"))
      assertEquals(storedAfterEmptyPasswordUpdate.map(_.password), Some(Some("first")))
      assertEquals(updatedWithPassword.map(_.label), Some("Gamma"))
      assertEquals(storedAfterPasswordUpdate.map(_.password), Some(Some("second")))
      assertEquals(syncRecorded, true)
      assertEquals(synced.flatMap(_.last_synced_commit), Some("abc123"))
      assertEquals(missingUpdate, None)
      assertEquals(deleted, true)
      assertEquals(missingDelete, false)
      assertEquals(missingFetch, None)
    }

  private def tidbResource(config: StateStoreConfig): Resource[IO, TargetStore] =
    StateDatabase.resource(config).map(database => TiDBTargetStore(database, passwordKey): TargetStore)

  private def targetPayload(label: String, password: Option[String]): TargetPayload =
    TargetPayload(
      label = label,
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:postgresql://localhost:5432/app?user=app&sslmode=disable",
      password = password,
      repo_url = "https://example.com/schema-migrator.git",
      repo_branch = "main",
      repo_sql_path = "sql"
    )
