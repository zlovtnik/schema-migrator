package com.sslproxy.schema.store

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.mongodb.client.MongoClients
import com.sslproxy.schema.config.MongoConfig
import munit.FunSuite

import java.util.UUID

class TargetStoreSuite extends FunSuite:
  targetStoreContract("in-memory", Resource.eval(TargetStore.inMemory))

  sys.env.get("BEDROCK_MONGO_TEST_URI").foreach { uri =>
    val database = sys.env.getOrElse("BEDROCK_MONGO_TEST_DATABASE", "schema_migrator_test")
    val collection = s"targets_${UUID.randomUUID().toString.replace("-", "")}"
    targetStoreContract("mongo", mongoResource(MongoConfig(uri, database, collection)))
  }

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
        missingUpdate,
        deleted,
        missingDelete,
        missingFetch
      ) = result

      assertEquals(created.label, "Alpha")
      assertEquals(stored.map(_.password), Some(Some("first")))
      assertEquals(listed.map(_.id), List(created.id))
      assertEquals(updated.map(_.label), Some("Beta"))
      assertEquals(storedAfterEmptyPasswordUpdate.map(_.password), Some(Some("first")))
      assertEquals(updatedWithPassword.map(_.label), Some("Gamma"))
      assertEquals(storedAfterPasswordUpdate.map(_.password), Some(Some("second")))
      assertEquals(missingUpdate, None)
      assertEquals(deleted, true)
      assertEquals(missingDelete, false)
      assertEquals(missingFetch, None)
    }

  private def mongoResource(config: MongoConfig): Resource[IO, TargetStore] =
    TargetStore.mongo(config).onFinalize {
      IO.blocking {
        val client = MongoClients.create(config.uri)
        try client.getDatabase(config.database).getCollection(config.targetsCollection).drop()
        finally client.close()
      }
    }

  private def targetPayload(label: String, password: Option[String]): TargetPayload =
    TargetPayload(
      label = label,
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:postgresql://localhost:5432/app?user=app&sslmode=disable",
      password = password
    )
