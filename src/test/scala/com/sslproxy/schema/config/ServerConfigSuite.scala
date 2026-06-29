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
        patchStageDir = stageDir
      )

      assert(config.validate.left.exists(_.nonEmpty))
    finally Files.deleteIfExists(stageDir)
  }
