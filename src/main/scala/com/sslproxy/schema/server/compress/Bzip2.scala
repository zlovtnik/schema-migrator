package com.sslproxy.schema.server.compress

import cats.effect.IO
import org.apache.commons.compress.compressors.bzip2.{BZip2CompressorInputStream, BZip2CompressorOutputStream}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

object Bzip2:
  private val maxDecompressedBytes = 10 * 1024 * 1024

  def compress(bytes: Array[Byte]): IO[Array[Byte]] =
    IO.blocking {
      val out = ByteArrayOutputStream()
      val bzip = BZip2CompressorOutputStream(out)
      try bzip.write(bytes)
      finally bzip.close()
      out.toByteArray
    }

  def decompress(bytes: Array[Byte]): IO[Array[Byte]] =
    IO.blocking {
      val in = BZip2CompressorInputStream(ByteArrayInputStream(bytes))
      try
        val out = ByteArrayOutputStream()
        val buffer = Array.ofDim[Byte](8192)
        var total = 0
        var read = in.read(buffer)
        while read != -1 do
          total += read
          if total > maxDecompressedBytes then
            throw IllegalArgumentException(s"decompressed bzip2 payload exceeds $maxDecompressedBytes bytes")
          out.write(buffer, 0, read)
          read = in.read(buffer)
        out.toByteArray
      finally in.close()
    }
