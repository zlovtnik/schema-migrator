package com.sslproxy.schema.server.compress

import cats.data.OptionT
import cats.effect.IO
import com.sslproxy.schema.server.RouteJson
import fs2.{Chunk, Stream}
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

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes { request =>
      OptionT.liftF(decodeRequest(request).attempt).flatMap {
        case Right(decoded) =>
          routes(decoded).semiflatMap(response => encodeResponse(decoded, response))
        case Left(error: Bzip2.BadInput) =>
          OptionT.liftF(BadRequest(RouteJson.error(error.getMessage)))
        case Left(error: Bzip2.SizeLimitExceeded) =>
          OptionT.liftF(PayloadTooLarge(RouteJson.error(error.getMessage)))
        case Left(error) =>
          OptionT.liftF(IO.raiseError(error))
      }
    }

  private def decodeRequest(request: Request[IO]): IO[Request[IO]] =
    trailingBzip2Encoding(request.headers) match
      case Some(remainingEncodings) =>
        readBodyBounded(request.body, maxCompressedRequestBytes, "compressed bzip2 request body exceeds 10 MiB limit")
          .flatMap(Bzip2.decompress)
          .map { decoded =>
            request
              .withHeaders(decodedRequestHeaders(request.headers, remainingEncodings))
              .withBodyStream(Stream.emits(decoded).covary[IO])
          }
      case None => IO.pure(request)

  private def encodeResponse(request: Request[IO], response: Response[IO]): IO[Response[IO]] =
    if shouldCompress(request, response) && !belowCompressionThreshold(response) then
      IO.pure(
        response
          .withHeaders(removeHeaders(response.headers, Set(contentEncoding, contentLength)))
          .withBodyStream(Bzip2.compressStream(response.body))
          .putHeaders(Header.Raw(contentEncoding, "bzip2"))
      )
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

  private def isEventStream(response: Response[IO]): Boolean =
    response.contentType.exists(_.mediaType == eventStreamMediaType)

  private def belowCompressionThreshold(response: Response[IO]): Boolean =
    response.headers.headers
      .find(_.name == contentLength)
      .flatMap(header => header.value.toLongOption)
      .exists(_ <= thresholdBytes.toLong)

  private def trailingBzip2Encoding(headers: Headers): Option[List[String]] =
    val values = headers.headers
      .filter(_.name == contentEncoding)
      .flatMap(_.value.split(",").map(_.trim).filter(_.nonEmpty))
    values.lastOption.filter(_.equalsIgnoreCase("bzip2")).map(_ => values.dropRight(1))

  private def decodedRequestHeaders(headers: Headers, remainingEncodings: List[String]): Headers =
    val withoutDecodedHeaders = removeHeaders(headers, Set(contentEncoding, contentLength))
    if remainingEncodings.isEmpty then withoutDecodedHeaders
    else withoutDecodedHeaders.put(Header.Raw(contentEncoding, remainingEncodings.mkString(", ")))

  private def readBodyBounded(body: Stream[IO, Byte], maxBytes: Long, message: String): IO[Array[Byte]] =
    body.take(maxBytes + 1).chunks.compile.toList.flatMap { chunks =>
      val size = chunks.foldLeft(0L)((total, chunk) => total + chunk.size.toLong)
      if size > maxBytes then IO.raiseError(new Bzip2.SizeLimitExceeded(message))
      else IO.delay(chunksToArray(chunks, size.toInt))
    }

  private def chunksToArray(chunks: List[Chunk[Byte]], size: Int): Array[Byte] =
    val bytes = Array.ofDim[Byte](size)
    var offset = 0
    chunks.foreach { chunk =>
      val chunkBytes = chunk.toArray
      System.arraycopy(chunkBytes, 0, bytes, offset, chunkBytes.length)
      offset += chunkBytes.length
    }
    bytes

  private def removeHeaders(headers: Headers, names: Set[CIString]): Headers =
    Headers(headers.headers.filterNot(header => names.contains(header.name)))
