package com.sslproxy.schema.server.crypto

import cats.effect.IO
import cats.syntax.all.*

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

object AesGcm:
  final case class Envelope(dataBase64: String, ivBase64: String, keyVersion: String)
  final case class KeyRing(currentVersion: String, currentKey: SecretKeySpec, previousKeys: Map[String, SecretKeySpec]):
    def key(version: String): Option[SecretKeySpec] =
      Option.when(version == currentVersion)(currentKey).orElse(previousKeys.get(version))

  private val keyBytes = 32
  private val ivBytes = 12
  private val tagBits = 128
  private val random = SecureRandom()

  def keyFromBase64(value: String): Either[String, SecretKeySpec] =
    Either
      .catchNonFatal(Base64.getDecoder.decode(value))
      .left
      .map(_.getMessage)
      .flatMap { bytes =>
        if bytes.length == keyBytes then Right(SecretKeySpec(bytes, "AES"))
        else Left(s"AES-GCM key must decode to $keyBytes bytes")
      }

  def encrypt(key: SecretKeySpec, plain: Array[Byte]): IO[(Array[Byte], Array[Byte])] =
    IO.delay {
      val iv = new Array[Byte](ivBytes)
      random.nextBytes(iv)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(tagBits, iv))
      cipher.doFinal(plain) -> iv
    }

  def decrypt(key: SecretKeySpec, cipherText: Array[Byte], iv: Array[Byte]): IO[Array[Byte]] =
    IO.delay {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(tagBits, iv))
      cipher.doFinal(cipherText)
    }

  def encryptEnvelope(keyRing: KeyRing, plain: Array[Byte]): IO[Envelope] =
    encrypt(keyRing.currentKey, plain).map { case (cipherText, iv) =>
      Envelope(base64(cipherText), base64(iv), keyRing.currentVersion)
    }

  def decryptEnvelope(keyRing: KeyRing, envelope: Envelope): IO[Array[Byte]] =
    keyRing.key(envelope.keyVersion) match
      case Some(key) =>
        val decoder = Base64.getDecoder
        decrypt(key, decoder.decode(envelope.dataBase64), decoder.decode(envelope.ivBase64))
      case None =>
        IO.raiseError(IllegalArgumentException(s"AES-GCM key version '${envelope.keyVersion}' is not configured"))

  def base64(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)
