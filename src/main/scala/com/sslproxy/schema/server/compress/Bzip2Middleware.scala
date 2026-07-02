package com.sslproxy.schema.server.compress

import cats.data.OptionT
import cats.effect.IO
import com.sslproxy.schema.server.RouteJson
import fs2.Stream
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

object Bzip2Middleware:
  private val thresholdBytes = 512
  private val maxCompressedRequestBytes = 10L * 1024L * 1024L
  private val eventStreamMediaType = MediaType.unsafeParse("text/event-stream")
  private val contentEncoding = CIString("Content-Encoding")
  private val contentLength = CIString("Content-Length")
  private val vary = CIString("Vary")

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes { request =>
      OptionT {
        decodeRequest(request)
          .flatMap(decoded => routes(decoded).semiflatMap(response => encodeResponse(decoded, response)).value)
          .attempt
          .flatMap {
            case Right(response) => IO.pure(response)
            case Left(error: Bzip2.BadInput) => BadRequest(RouteJson.error(error.getMessage)).map(Some(_))
            case Left(error: Bzip2.SizeLimitExceeded) => PayloadTooLarge(RouteJson.error(error.getMessage)).map(Some(_))
            case Left(error) => IO.raiseError(error)
          }
      }
    }

  private def decodeRequest(request: Request[IO]): IO[Request[IO]] =
    trailingBzip2Encoding(request.headers) match
      case Some(remainingEncodings) =>
        IO.pure(
          request
            .withHeaders(decodedRequestHeaders(request.headers, remainingEncodings))
            .withBodyStream(
              Bzip2.decompressStream(
                limitBody(request.body, maxCompressedRequestBytes, "compressed bzip2 request body exceeds 10 MiB limit")
              )
            )
        )
      case None => IO.pure(request)

  private def encodeResponse(request: Request[IO], response: Response[IO]): IO[Response[IO]] =
    if shouldCompress(request, response) then
      responseWithCompressionDecision(response).map {
        case (true, responseToCompress) =>
          encodeBzip2(responseToCompress)
        case (false, responseToKeep) => responseToKeep
      }
    else IO.pure(response)

  private def encodeBzip2(response: Response[IO]): Response[IO] =
    val nextVary = appendHeaderValue(headerValues(response.headers, vary), "Accept-Encoding").mkString(", ")
    response
      .withHeaders(removeHeaders(response.headers, Set(contentEncoding, contentLength, vary)))
      .withBodyStream(Bzip2.compressStream(response.body))
      .putHeaders(Header.Raw(contentEncoding, "bzip2"), Header.Raw(vary, nextVary))

  private def shouldCompress(request: Request[IO], response: Response[IO]): Boolean =
    acceptsBzip2(request) &&
      response.status != Status.NoContent &&
      response.status != Status.NotModified &&
      !isEventStream(response) &&
      !response.headers.headers.exists(_.name == contentEncoding)

  private def acceptsBzip2(request: Request[IO]): Boolean =
    request.headers.headers
      .filter(_.name == CIString("Accept-Encoding"))
      .exists(_.value.split(",").exists(_.trim.equalsIgnoreCase("bzip2")))

  private def isEventStream(response: Response[IO]): Boolean =
    response.contentType.exists(_.mediaType == eventStreamMediaType)

  private def responseWithCompressionDecision(response: Response[IO]): IO[(Boolean, Response[IO])] =
    response.headers.headers
      .find(_.name == contentLength)
      .flatMap(header => header.value.toLongOption)
      .fold(IO.pure(true -> response)) { length =>
        IO.pure((length > thresholdBytes.toLong) -> response)
      }

  private def trailingBzip2Encoding(headers: Headers): Option[List[String]] =
    val values = headers.headers
      .filter(_.name == contentEncoding)
      .flatMap(_.value.split(",").map(_.trim).filter(_.nonEmpty))
    values.lastOption.filter(_.equalsIgnoreCase("bzip2")).map(_ => values.dropRight(1))

  private def decodedRequestHeaders(headers: Headers, remainingEncodings: List[String]): Headers =
    val withoutDecodedHeaders = removeHeaders(headers, Set(contentEncoding, contentLength))
    if remainingEncodings.isEmpty then withoutDecodedHeaders
    else withoutDecodedHeaders.put(Header.Raw(contentEncoding, remainingEncodings.mkString(", ")))

  private def limitBody(body: Stream[IO, Byte], maxBytes: Long, message: String): Stream[IO, Byte] =
    Stream.eval(cats.effect.Ref.of[IO, Long](0L)).flatMap { totalRef =>
      body.chunks
        .evalMap { chunk =>
          totalRef
            .modify { total =>
              val next = total + chunk.size.toLong
              if next > maxBytes then total -> Left(new Bzip2.SizeLimitExceeded(message))
              else next -> Right(chunk)
            }
            .flatMap(IO.fromEither)
        }
        .flatMap(Stream.chunk)
    }

  private def headerValues(headers: Headers, name: CIString): List[String] =
    headers.headers
      .filter(_.name == name)
      .flatMap(_.value.split(",").map(_.trim))
      .filter(_.nonEmpty)

  private def appendHeaderValue(values: List[String], value: String): List[String] =
    if values.exists(_.equalsIgnoreCase(value)) then values else values :+ value

  private def removeHeaders(headers: Headers, names: Set[CIString]): Headers =
    Headers(headers.headers.filterNot(header => names.contains(header.name)))
