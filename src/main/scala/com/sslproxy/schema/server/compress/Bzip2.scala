package com.sslproxy.schema.server.compress

import cats.effect.IO
import fs2.Stream
import org.apache.commons.compress.compressors.bzip2.{BZip2CompressorInputStream, BZip2CompressorOutputStream}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.util.control.NonFatal

object Bzip2:
  private val maxDecompressedBytes = 10 * 1024 * 1024
  private val chunkSize = 8192

  final class BadInput(message: String, cause: Throwable) extends IllegalArgumentException(message, cause)
  final class SizeLimitExceeded(message: String) extends IllegalArgumentException(message)

  def compress(bytes: Array[Byte]): IO[Array[Byte]] =
    IO.blocking {
      val out = ByteArrayOutputStream()
      val bzip = BZip2CompressorOutputStream(out)
      try bzip.write(bytes)
      finally bzip.close()
      out.toByteArray
    }

  def compressStream(body: Stream[IO, Byte]): Stream[IO, Byte] =
    fs2.io.readOutputStream[IO](chunkSize) { output =>
      IO.blocking(BZip2CompressorOutputStream(output)).flatMap { bzip =>
        body.chunks
          .evalMap(chunk => IO.blocking(bzip.write(chunk.toArray)))
          .compile
          .drain
          .guarantee(IO.blocking(bzip.close()).handleErrorWith(_ => IO.unit))
      }
    }

  def decompress(bytes: Array[Byte]): IO[Array[Byte]] =
    IO.blocking {
      try
        val in = BZip2CompressorInputStream(ByteArrayInputStream(bytes))
        try
          val out = ByteArrayOutputStream()
          val buffer = Array.ofDim[Byte](chunkSize)
          var total = 0
          var read = in.read(buffer)
          while read != -1 do
            total += read
            if total > maxDecompressedBytes then
              throw new SizeLimitExceeded(s"decompressed bzip2 payload exceeds $maxDecompressedBytes bytes")
            out.write(buffer, 0, read)
            read = in.read(buffer)
          out.toByteArray
        finally in.close()
      catch
        case error: SizeLimitExceeded => throw error
        case NonFatal(error) => throw new BadInput("invalid bzip2 request body", error)
    }
