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

class Bzip2Suite extends FunSuite:
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
    assert(String(decoded, StandardCharsets.UTF_8).contains("x".repeat(800)))
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
