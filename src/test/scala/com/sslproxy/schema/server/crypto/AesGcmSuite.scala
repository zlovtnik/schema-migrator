package com.sslproxy.schema.server.crypto

import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.charset.StandardCharsets

class AesGcmSuite extends FunSuite:
  private val keyText = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY="

  test("AES-GCM encrypt then decrypt preserves plaintext") {
    val key = AesGcm.keyFromBase64(keyText).fold(message => fail(message), identity)
    val plain = "schema migrator payload".getBytes(StandardCharsets.UTF_8)
    val (cipherText, iv) = AesGcm.encrypt(key, plain).unsafeRunSync()
    val decrypted = AesGcm.decrypt(key, cipherText, iv).unsafeRunSync()

    assertEquals(String(decrypted, StandardCharsets.UTF_8), "schema migrator payload")
  }

  test("AES-GCM rejects non-256-bit keys") {
    assert(AesGcm.keyFromBase64("c2hvcnQ=").isLeft)
  }
