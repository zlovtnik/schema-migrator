package com.sslproxy.schema.validation

import com.sslproxy.schema.discovery.SqlFile
import com.sslproxy.schema.engine.Graph
import com.sslproxy.schema.parser.HeaderParser

import java.nio.file.Files

object DependencyValidator:
  def validate(files: List[SqlFile]): List[String] =
    val objects =
      files.flatMap { file =>
        val sql = Files.readString(file.path)
        HeaderParser.value(sql, "object").map { name =>
          name -> HeaderParser.value(sql, "depends_on").map(HeaderParser.dependsOn).getOrElse(Nil)
        }
      }
    val objectNames = objects.map(_._1).toSet
    val missing = objects.flatMap { case (name, deps) =>
      deps
        .filter(dep => !objectNames.contains(dep) && !isExternal(dep))
        .map(dep => s"$name depends on missing object '$dep'")
    }
    val cycleErrors = Graph
      .findDependencyCycle(objects.length, index => objects(index)._1, index => objects(index)._2)
      .map(cycle => List(s"dependency cycle detected among: ${cycle.mkString(", ")}"))
      .getOrElse(Nil)
    missing ++ cycleErrors

  private def isExternal(dep: String): Boolean =
    dep.startsWith("ext:") || dep.startsWith("external:")
