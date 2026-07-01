package com.sslproxy.schema.server.compress

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString
import munit.FunSuite

import java.nio.charset.StandardCharsets
import scala.util.Random

class Bzip2Suite extends FunSuite:
  private val maxBzip2RequestBytes = 10L * 1024L * 1024L
  private val maxBzip2DecompressedBytes = 10 * 1024 * 1024

  test("bzip2 compress and decompress round-trips") {
    val plain = "payload".repeat(200).getBytes(StandardCharsets.UTF_8)
    val compressed = Bzip2.compress(plain).unsafeRunSync()
    val decompressed = Bzip2.decompress(compressed).unsafeRunSync()

    assertEquals(String(decompressed, StandardCharsets.UTF_8), "payload".repeat(200))
  }

  test("middleware compresses large accepted responses") {
    val routes = HttpRoutes.of[IO] { case GET -> Root / "payload" =>
      Ok(Json.obj("value" -> Json.fromString("x".repeat(800))))
    }
    val request =
      Request[IO](Method.GET, Uri.unsafeFromString("/payload"))
        .putHeaders(Header.Raw(CIString("Accept-Encoding"), "bzip2"))

    val response = Bzip2Middleware(routes).orNotFound.run(request).unsafeRunSync()
    val encoded = response.body.compile.toVector.map(_.toArray).unsafeRunSync()
    val decoded = Bzip2.decompress(encoded).unsafeRunSync()

    assertEquals(response.headers.headers.find(_.name == CIString("Content-Encoding")).map(_.value), Some("bzip2"))
    assertEquals(response.headers.headers.find(_.name == CIString("Vary")).map(_.value), Some("Accept-Encoding"))
    assert(String(decoded, StandardCharsets.UTF_8).contains("x".repeat(800)))
  }

  test("middleware leaves small responses without content length uncompressed") {
    val body = "small response".getBytes(StandardCharsets.UTF_8)
    val routes = HttpRoutes.of[IO] { case GET -> Root / "small" =>
      IO.pure(Response[IO](Status.Ok).withBodyStream(Stream.emits(body.toVector).covary[IO]))
    }
    val request =
      Request[IO](Method.GET, Uri.unsafeFromString("/small"))
        .putHeaders(Header.Raw(CIString("Accept-Encoding"), "bzip2"))

    val response = Bzip2Middleware(routes).orNotFound.run(request).unsafeRunSync()
    val responseBody = response.body.compile.toVector.map(_.toArray).unsafeRunSync()

    assertEquals(response.headers.headers.find(_.name == CIString("Content-Encoding")).map(_.value), None)
    assertEquals(String(responseBody, StandardCharsets.UTF_8), "small response")
  }

  test("decompress stream propagates writer-side errors") {
    val writerError = IllegalStateException("writer failed")

    val error = intercept[IllegalStateException] {
      Bzip2.decompressStream(Stream.raiseError[IO](writerError)).compile.drain.unsafeRunSync()
    }

    assertEquals(error, writerError)
  }

  test("middleware rejects invalid bzip2 request bodies as bad input") {
    val routes = HttpRoutes.of[IO] { case request @ POST -> Root / "payload" =>
      request.body.compile.drain *> Ok()
    }
    val request =
      Request[IO](Method.POST, Uri.unsafeFromString("/payload"))
        .putHeaders(Header.Raw(CIString("Content-Encoding"), "bzip2"))
        .withBodyStream(Stream.emits("not-bzip2".getBytes(StandardCharsets.UTF_8).toVector).covary[IO])

    val response = Bzip2Middleware(routes).orNotFound.run(request).unsafeRunSync()

    assertEquals(response.status, Status.BadRequest)
  }

  test("middleware maps compressed bzip2 request size overflow to payload too large") {
    val routes = HttpRoutes.of[IO] { case request @ POST -> Root / "payload" =>
      request.body.compile.drain *> Ok()
    }
    val plain = Array.ofDim[Byte](maxBzip2DecompressedBytes - 1)
    Random(0L).nextBytes(plain)
    val compressed = Bzip2.compress(plain).unsafeRunSync()
    assert(compressed.length.toLong > maxBzip2RequestBytes)
    val request =
      Request[IO](Method.POST, Uri.unsafeFromString("/payload"))
        .putHeaders(Header.Raw(CIString("Content-Encoding"), "bzip2"))
        .withBodyStream(Stream.emits(compressed.toVector).covary[IO])

    val response = Bzip2Middleware(routes).orNotFound.run(request).unsafeRunSync()
    val json = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.PayloadTooLarge)
    assertEquals(json.hcursor.get[String]("error"), Right("compressed bzip2 request body exceeds 10 MiB limit"))
  }

  test("middleware maps decompressed bzip2 request size overflow to payload too large") {
    val routes = HttpRoutes.of[IO] { case request @ POST -> Root / "payload" =>
      request.body.compile.drain *> Ok()
    }
    val compressed = Bzip2.compress(Array.fill(maxBzip2DecompressedBytes + 1)(0.toByte)).unsafeRunSync()
    assert(compressed.length.toLong <= maxBzip2RequestBytes)
    val request =
      Request[IO](Method.POST, Uri.unsafeFromString("/payload"))
        .putHeaders(Header.Raw(CIString("Content-Encoding"), "bzip2"))
        .withBodyStream(Stream.emits(compressed.toVector).covary[IO])

    val response = Bzip2Middleware(routes).orNotFound.run(request).unsafeRunSync()
    val json = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.PayloadTooLarge)
    assertEquals(
      json.hcursor.get[String]("error"),
      Right(s"decompressed bzip2 payload exceeds $maxBzip2DecompressedBytes bytes")
    )
  }

  test("middleware preserves earlier content encodings when decoding trailing bzip2 requests") {
    val routes = HttpRoutes.of[IO] { case request @ POST -> Root / "payload" =>
      request.body.compile.toVector.flatMap { bytes =>
        val remainingEncoding = request.headers.headers.find(_.name == CIString("Content-Encoding")).map(_.value)
        Ok(
          Json.obj(
            "body" -> Json.fromString(String(bytes.toArray, StandardCharsets.UTF_8)),
            "content_encoding" -> remainingEncoding.fold(Json.Null)(Json.fromString)
          )
        )
      }
    }
    val compressed = Bzip2.compress("payload".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()
    val request =
      Request[IO](Method.POST, Uri.unsafeFromString("/payload"))
        .putHeaders(Header.Raw(CIString("Content-Encoding"), "gzip, bzip2"))
        .withBodyStream(Stream.emits(compressed.toVector).covary[IO])

    val response = Bzip2Middleware(routes).orNotFound.run(request).unsafeRunSync()
    val json = response.as[Json].unsafeRunSync()

    assertEquals(response.status, Status.Ok)
    assertEquals(json.hcursor.get[String]("body"), Right("payload"))
    assertEquals(json.hcursor.get[String]("content_encoding"), Right("gzip"))
  }
