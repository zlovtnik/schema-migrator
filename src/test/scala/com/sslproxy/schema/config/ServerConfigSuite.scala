package com.sslproxy.schema.config

import munit.FunSuite

import java.nio.file.Files

class ServerConfigSuite extends FunSuite:
  test("server validation rejects malformed AES-GCM encryption keys") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val config = ServerConfig(
        host = "127.0.0.1",
        port = 8080,
        corsOrigins = Set("http://localhost:5173"),
        encryptKeyBase64 = Some("not-base64"),
        jwtSecret = "jwt",
        devAuthSecret = "dev",
        dbTestAllowedHosts = Set.empty,
        patchStageDir = stageDir,
        apiBearerToken = Some("api-token"),
        mongo = Some(MongoConfig("mongodb://localhost:27017", "schema_migrator", "targets"))
      )

      assert(config.validate.left.exists(_.nonEmpty))
    finally Files.deleteIfExists(stageDir)
  }

  test("server validation requires static API bearer token and Mongo config") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val missingToken = validConfig(stageDir).copy(apiBearerToken = None)
      val missingMongo = validConfig(stageDir).copy(mongo = None)

      assertEquals(missingToken.validate, Left("BEDROCK_API_BEARER_TOKEN must not be empty"))
      assertEquals(
        missingMongo.validate,
        Left("BEDROCK_MONGO_URI, BEDROCK_MONGO_DATABASE, and BEDROCK_MONGO_TARGETS_COLLECTION must be set")
      )
    finally Files.deleteIfExists(stageDir)
  }

  test("server validation requires encryption key for persisted targets") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val missingEncryptKey = validConfig(stageDir).copy(encryptKeyBase64 = None)

      assertEquals(missingEncryptKey.validate, Left("BEDROCK_ENCRYPT_KEY must not be empty"))
    finally Files.deleteIfExists(stageDir)
  }

  test("server validation only requires dev auth secret when dev auth is enabled") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val disabled = validConfig(stageDir).copy(devAuthEnabled = false, devAuthSecret = "")
      val enabled = validConfig(stageDir).copy(devAuthEnabled = true, devAuthSecret = "")

      assertEquals(disabled.validate, Right(()))
      assertEquals(enabled.validate, Left("BEDROCK_DEV_AUTH_SECRET must not be empty when dev auth is enabled"))
    finally Files.deleteIfExists(stageDir)
  }

  private def validConfig(stageDir: java.nio.file.Path): ServerConfig =
    ServerConfig(
      host = "127.0.0.1",
      port = 8080,
      corsOrigins = Set("http://localhost:5173"),
      encryptKeyBase64 = Some("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
      jwtSecret = "jwt",
      devAuthSecret = "dev",
      dbTestAllowedHosts = Set.empty,
      patchStageDir = stageDir,
      apiBearerToken = Some("api-token"),
      mongo = Some(MongoConfig("mongodb://localhost:27017", "schema_migrator", "targets"))
    )
