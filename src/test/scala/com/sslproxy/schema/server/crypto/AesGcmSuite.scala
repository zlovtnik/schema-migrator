package com.sslproxy.schema.server.crypto

import cats.effect.unsafe.implicits.global
import munit.FunSuite

import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException

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

  test("stored credentials cannot be decrypted with a different key") {
    val wrongKey =
      AesGcm.keyFromBase64("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").fold(message => fail(message), identity)
    val key = AesGcm.keyFromBase64(keyText).fold(message => fail(message), identity)
    val plain = "stored password".getBytes(StandardCharsets.UTF_8)
    val (cipherText, iv) = AesGcm.encrypt(key, plain).unsafeRunSync()

    intercept[AEADBadTagException] {
      AesGcm.decrypt(wrongKey, cipherText, iv).unsafeRunSync()
    }
  }

  test("stored credentials use a fresh nonce for each encryption") {
    val key = AesGcm.keyFromBase64(keyText).fold(message => fail(message), identity)
    val plain = "stored password".getBytes(StandardCharsets.UTF_8)
    val (firstCipherText, firstIv) = AesGcm.encrypt(key, plain).unsafeRunSync()
    val (secondCipherText, secondIv) = AesGcm.encrypt(key, plain).unsafeRunSync()

    assertNotEquals(firstIv.toSeq, secondIv.toSeq)
    assertNotEquals(firstCipherText.toSeq, secondCipherText.toSeq)
  }
