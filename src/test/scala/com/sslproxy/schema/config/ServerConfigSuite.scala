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
        stateStore = Some(validStateStore)
      )

      assert(config.validate.left.exists(_.nonEmpty))
    finally deleteIfExists(stageDir)
  }

  test("server validation requires static API bearer token and PostgreSQL state store config") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val missingToken = validConfig(stageDir).copy(apiBearerToken = None)
      val missingStateStore = validConfig(stageDir).copy(stateStore = None)

      assertEquals(missingToken.validate, Left("BEDROCK_API_BEARER_TOKEN must not be empty"))
      assertEquals(
        missingStateStore.validate,
        Left("BEDROCK_STATE_DB_URL, BEDROCK_STATE_DB_USER, and BEDROCK_STATE_DB_PASSWORD must be set")
      )
    finally deleteIfExists(stageDir)
  }

  test("server validation requires encryption key for persisted targets") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val missingEncryptKey = validConfig(stageDir).copy(encryptKeyBase64 = None)

      assertEquals(missingEncryptKey.validate, Left("BEDROCK_ENCRYPT_KEY must not be empty"))
    finally deleteIfExists(stageDir)
  }

  test("server validation only requires dev auth secret when dev auth is enabled") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val disabled = validConfig(stageDir).copy(devAuthEnabled = false, devAuthSecret = "")
      val enabled = validConfig(stageDir).copy(devAuthEnabled = true, devAuthSecret = "")

      assertEquals(disabled.validate, Right(()))
      assertEquals(enabled.validate, Left("BEDROCK_DEV_AUTH_SECRET must not be empty when dev auth is enabled"))
    finally deleteIfExists(stageDir)
  }

  test("server validation requires Keycloak issuer when Keycloak auth is enabled") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val disabled = validConfig(stageDir).copy(keycloakEnabled = false, keycloakIssuer = None)
      val enabled = validConfig(stageDir).copy(keycloakEnabled = true, keycloakIssuer = None)

      assertEquals(disabled.validate, Right(()))
      assertEquals(enabled.validate, Left("BEDROCK_KEYCLOAK_ISSUER must not be empty when Keycloak auth is enabled"))
    finally deleteIfExists(stageDir)
  }

  test("server validation requires a Keycloak audience or client id when Keycloak auth is enabled") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val missingScope = validConfig(stageDir).copy(
        keycloakEnabled = true,
        keycloakIssuer = Some("https://keycloak.example.com/realms/bedrock"),
        keycloakAudience = None,
        keycloakClientId = None
      )
      val withAudience = missingScope.copy(keycloakAudience = Some("bedrock-ui"))
      val withClientId = missingScope.copy(keycloakClientId = Some("bedrock-ui"))

      assertEquals(
        missingScope.validate,
        Left("BEDROCK_KEYCLOAK_AUDIENCE or BEDROCK_KEYCLOAK_CLIENT_ID must be set when Keycloak auth is enabled")
      )
      assertEquals(withAudience.validate, Right(()))
      assertEquals(withClientId.validate, Right(()))
    finally deleteIfExists(stageDir)
  }

  test("server validation rejects non-JDBC-PostgreSQL and non-currentSchema state URLs") {
    val stageDir = Files.createTempDirectory("schema-migrator-config")
    try
      val mysql = validConfig(stageDir).copy(
        stateStore = Some(StateStoreConfig("jdbc:mysql://db.example/schema_migrator", "migrator", "secret"))
      )
      val r2dbc = validConfig(stageDir).copy(
        stateStore = Some(StateStoreConfig("r2dbc:postgresql://db.example/schema_migrator", "migrator", "secret"))
      )
      val noSchema = validConfig(stageDir).copy(
        stateStore = Some(StateStoreConfig("jdbc:postgresql://db.example/schema_migrator", "migrator", "secret"))
      )

      assertEquals(
        mysql.validate,
        Left("BEDROCK_STATE_DB_URL must be a JDBC PostgreSQL URL starting with jdbc:postgresql://")
      )
      assertEquals(
        r2dbc.validate,
        Left("BEDROCK_STATE_DB_URL must be a JDBC PostgreSQL URL starting with jdbc:postgresql://")
      )
      assertEquals(noSchema.validate, Left("BEDROCK_STATE_DB_URL must set currentSchema=schema_migrator"))
    finally deleteIfExists(stageDir)
  }

  test("state database validation requires the canonical database and a non-root account") {
    assertEquals(
      validStateStore.copy(url = "jdbc:postgresql://db.example/other?currentSchema=schema_migrator").validate,
      Left("BEDROCK_STATE_DB_URL must select the schema_migrator database")
    )
    assertEquals(
      validStateStore.copy(user = "root").validate,
      Left("BEDROCK_STATE_DB_USER must be a dedicated non-root PostgreSQL user")
    )
    assertEquals(
      validStateStore.copy(url = "jdbc:postgresql://localhost:5432/schema_migrator?currentSchema=schema_migrator").validate,
      Left("BEDROCK_STATE_DB_URL must use an external non-loopback PostgreSQL host")
    )
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
      stateStore = Some(validStateStore)
    )

  private val validStateStore =
    StateStoreConfig("jdbc:postgresql://db.example:5432/schema_migrator?currentSchema=schema_migrator", "migrator", "secret")

  private def deleteIfExists(path: java.nio.file.Path): Unit =
    Files.deleteIfExists(path)
    ()
