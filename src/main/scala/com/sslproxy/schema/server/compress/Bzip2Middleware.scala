package com.sslproxy.schema.server.compress

import cats.data.OptionT
import cats.effect.IO
import fs2.Stream
import org.http4s.*
import org.typelevel.ci.CIString

object Bzip2Middleware:
  private val thresholdBytes = 512
  private val eventStreamMediaType = MediaType.unsafeParse("text/event-stream")
  private val contentEncoding = CIString("Content-Encoding")
  private val contentLength = CIString("Content-Length")

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes { request =>
      OptionT.liftF(decodeRequest(request)).flatMap { decoded =>
        routes(decoded).semiflatMap(response => encodeResponse(decoded, response))
      }
    }

  private def decodeRequest(request: Request[IO]): IO[Request[IO]] =
    if hasHeaderValue(request.headers, "Content-Encoding", "bzip2") then
      request.body.compile.toVector.flatMap(bytes => Bzip2.decompress(bytes.toArray)).map { decoded =>
        request
          .withHeaders(removeHeaders(request.headers, Set(contentEncoding, contentLength)))
          .withBodyStream(Stream.emits(decoded).covary[IO])
      }
    else IO.pure(request)

  private def encodeResponse(request: Request[IO], response: Response[IO]): IO[Response[IO]] =
    if shouldCompress(request, response) then
      response.body.compile.toVector.flatMap { bytesVector =>
        val bytes = bytesVector.toArray
        if bytes.length <= thresholdBytes then IO.pure(response.withBodyStream(Stream.emits(bytes).covary[IO]))
        else
          Bzip2.compress(bytes).map { compressed =>
            response
              .withHeaders(removeHeaders(response.headers, Set(contentEncoding, contentLength)))
              .withBodyStream(Stream.emits(compressed).covary[IO])
              .putHeaders(Header.Raw(contentEncoding, "bzip2"))
          }
      }
    else IO.pure(response)

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

  private def hasHeaderValue(headers: Headers, name: String, value: String): Boolean =
    headers.headers
      .filter(_.name == CIString(name))
      .exists(_.value.split(",").exists(_.trim.equalsIgnoreCase(value)))

  private def isEventStream(response: Response[IO]): Boolean =
    response.contentType.exists(_.mediaType == eventStreamMediaType)

  private def removeHeaders(headers: Headers, names: Set[CIString]): Headers =
    Headers(headers.headers.filterNot(header => names.contains(header.name)))
