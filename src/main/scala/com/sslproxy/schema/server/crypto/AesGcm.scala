package com.sslproxy.schema.server.crypto

import cats.effect.IO
import cats.syntax.all.*

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

object AesGcm:
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

  def base64(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)
