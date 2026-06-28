package com.sslproxy.schema.server.compress

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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
    val routes = HttpRoutes.of[IO] {
      case GET -> Root / "payload" =>
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
