package com.sslproxy.schema.server.crypto

import cats.data.OptionT
import cats.effect.IO
import io.circe.Json
import org.http4s.*
import org.typelevel.ci.CIString

import javax.crypto.spec.SecretKeySpec

object AesGcmMiddleware:
  def apply(key: Option[SecretKeySpec])(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    key match
      case None => routes
      case Some(secretKey) =>
        HttpRoutes { request =>
          routes(request).semiflatMap { response =>
            if shouldEncrypt(request, response) then encryptResponse(secretKey, response)
            else IO.pure(response)
          }
        }

  private def shouldEncrypt(request: Request[IO], response: Response[IO]): Boolean =
    val path = request.uri.path.renderString
    val isHealth = path == "/health" || path == "/api/health"
    val isJson = response.contentType.exists(_.mediaType == MediaType.application.json)
    val isEmpty = response.status == Status.NoContent || response.status == Status.NotModified
    val alreadyEncrypted = response.headers.headers.exists(_.name == CIString("X-Bedrock-Encrypted"))
    isJson && !isHealth && !isEmpty && !alreadyEncrypted

  private def encryptResponse(key: SecretKeySpec, response: Response[IO]): IO[Response[IO]] =
    for
      plain <- response.body.compile.toVector.map(_.toArray)
      encrypted <- AesGcm.encryptEnvelope(AesGcm.KeyRing("current", key, Map.empty), plain)
      envelope = Json.obj(
        "data" -> Json.fromString(encrypted.dataBase64),
        "iv" -> Json.fromString(encrypted.ivBase64),
        "key_version" -> Json.fromString(encrypted.keyVersion)
      )
    yield response
      .withEntity(envelope.noSpaces)
      .putHeaders(
        Header.Raw(CIString("Content-Type"), "application/json"),
        Header.Raw(CIString("X-Bedrock-Encrypted"), "1")
      )
