package com.sslproxy.schema.discovery

import com.sslproxy.schema.config.DbKind

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.jdk.CollectionConverters.*

final case class ApplyOrderManifest(
  path: Path,
  engine: String,
  layer: String,
  customer: Option[String],
  applyOrder: List[String]
)

object ApplyOrderManifest:
  def read(path: Path): Either[String, ApplyOrderManifest] =
    val lines =
      try Right(Files.readAllLines(path).asScala.toList)
      catch case error: Exception => Left(s"$path: could not read manifest (${error.getMessage})")

    lines.flatMap { manifestLines =>
      val scalars = scala.collection.mutable.Map.empty[String, String]
      val applyOrder = List.newBuilder[String]
      var inApplyOrder = false
      var parseError = Option.empty[String]

      manifestLines.zipWithIndex.foreach { case (rawLine, index) =>
        if parseError.isEmpty then
          val line = stripComment(rawLine).trim
          if line.nonEmpty then
            if line.startsWith("-") && inApplyOrder then
              val value = unquote(line.drop(1).trim)
              if value.nonEmpty then applyOrder += value
            else
              val colon = line.indexOf(':')
              if colon >= 0 then
                val key = line.take(colon).trim
                val value = line.drop(colon + 1).trim
                if key == "apply_order" then
                  inApplyOrder = true
                  if value == "[]" then ()
                  else if value.nonEmpty then parseError = Some(s"$path:${index + 1}: apply_order must be a YAML list")
                else
                  inApplyOrder = false
                  scalars += key -> unquote(value)
              else parseError = Some(s"$path:${index + 1}: unsupported manifest line '$line'")
      }

      parseError match
        case Some(error) => Left(error)
        case None =>
          for
            engine <- required(path, scalars.toMap, "engine")
            layer <- required(path, scalars.toMap, "layer")
          yield ApplyOrderManifest(
            path = path,
            engine = engine.toLowerCase(Locale.ROOT),
            layer = layer.toLowerCase(Locale.ROOT),
            customer = scalars.get("customer").filter(_.nonEmpty),
            applyOrder = applyOrder.result()
          )
    }

  def validate(manifest: ApplyOrderManifest, dbKind: DbKind, expectedLayer: String): List[String] =
    val expectedEngine = SqlLayout.engineName(dbKind)
    val engineWarnings =
      if manifest.engine == expectedEngine then Nil
      else
        List(s"${manifest.path}: manifest engine '${manifest.engine}' does not match selected engine '$expectedEngine'")
    val layerWarnings =
      if manifest.layer == expectedLayer then Nil
      else List(s"${manifest.path}: manifest layer '${manifest.layer}' does not match expected layer '$expectedLayer'")
    engineWarnings ++ layerWarnings

  private def required(path: Path, scalars: Map[String, String], key: String): Either[String, String] =
    scalars.get(key).filter(_.nonEmpty).toRight(s"$path: missing required '$key' field")

  private def stripComment(line: String): String =
    val hash = line.indexOf('#')
    if hash >= 0 then line.take(hash) else line

  private def unquote(value: String): String =
    value.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'").trim
