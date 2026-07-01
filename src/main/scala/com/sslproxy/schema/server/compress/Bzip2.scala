package com.sslproxy.schema.server.compress

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import org.apache.commons.compress.compressors.bzip2.{BZip2CompressorInputStream, BZip2CompressorOutputStream}

import java.io.{ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
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
    decompressStream(Stream.emits(bytes.toVector).covary[IO]).compile.toVector.map(_.toArray)

  def decompressStream(body: Stream[IO, Byte]): Stream[IO, Byte] =
    Stream.eval(Deferred[IO, Throwable]).flatMap { writerFailure =>
      Stream
        .bracket(
          IO.blocking {
            val input = PipedInputStream(chunkSize)
            val output = PipedOutputStream(input)
            input -> output
          }
        ) { case (input, output) =>
          IO.blocking(output.close()).handleErrorWith(_ => IO.unit) *>
            IO.blocking(input.close()).handleErrorWith(_ => IO.unit)
        }
        .flatMap { case (pipeInput, pipeOutput) =>
          val writer =
            body
              .through(fs2.io.writeOutputStream(IO.pure(pipeOutput), closeAfterUse = true))
              .compile
              .drain
              .attempt
              .flatMap {
                case Left(error) => writerFailure.complete(error).void
                case Right(_) => IO.unit
              }

          fs2.io
            .readInputStream(IO.blocking(BZip2CompressorInputStream(pipeInput)), chunkSize)
            .through(limitDecompressed)
            .handleErrorWith {
              case error: SizeLimitExceeded => Stream.raiseError[IO](error)
              case NonFatal(error) =>
                Stream.eval(writerFailure.tryGet).flatMap {
                  case Some(sizeError: SizeLimitExceeded) => Stream.raiseError[IO](sizeError)
                  case Some(writerError) => Stream.raiseError[IO](writerError)
                  case None => Stream.raiseError[IO](new BadInput("invalid bzip2 request body", error))
                }
            }
            .concurrently(Stream.eval(writer))
        }
    }

  private def limitDecompressed(body: Stream[IO, Byte]): Stream[IO, Byte] =
    Stream.eval(Ref.of[IO, Long](0L)).flatMap { totalRef =>
      body.chunks
        .evalMap { chunk =>
          totalRef
            .modify { total =>
              val next = total + chunk.size.toLong
              if next > maxDecompressedBytes then
                total -> Left(new SizeLimitExceeded(s"decompressed bzip2 payload exceeds $maxDecompressedBytes bytes"))
              else next -> Right(chunk)
            }
            .flatMap(IO.fromEither)
        }
        .flatMap(Stream.chunk)
    }
