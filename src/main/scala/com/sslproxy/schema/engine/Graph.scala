package com.sslproxy.schema.engine

import com.sslproxy.schema.error.MigratorError

import scala.collection.mutable

object Graph:
  def topologicalSort(objects: List[SchemaObject]): Either[MigratorError, List[SchemaObject]] =
    topologicalIndexes(
      objects.length,
      index => objects(index).objectName,
      index => objects(index).dependsOn
    ).map(indexes => indexes.map(objects))

  def findDependencyCycle[A](
      count: Int,
      objectName: Int => String,
      dependencies: Int => List[String]
  ): Option[List[String]] =
    topologicalIndexes(count, objectName, dependencies).left.toOption.collect {
      case MigratorError.Apply(message, _) if message.startsWith("dependency cycle detected among: ") =>
        message.stripPrefix("dependency cycle detected among: ").split(", ").toList
    }

  private def topologicalIndexes(
      count: Int,
      objectName: Int => String,
      dependencies: Int => List[String]
  ): Either[MigratorError, List[Int]] =
    val nameToIndex = (0 until count).map(index => objectName(index) -> index).toMap
    val inDegree = Array.fill(count)(0)
    val adjacency = Array.fill(count)(mutable.ListBuffer.empty[Int])

    (0 until count).foreach { index =>
      dependencies(index).foreach { dep =>
        nameToIndex.get(dep).foreach { depIndex =>
          adjacency(depIndex).append(index)
          inDegree(index) += 1
        }
      }
    }

    val available = mutable.TreeSet.empty[Int]
    inDegree.zipWithIndex.foreach { case (degree, index) =>
      if degree == 0 then available.add(index)
    }

    val sorted = mutable.ListBuffer.empty[Int]
    while available.nonEmpty do
      val index = available.head
      available.remove(index)
      sorted.append(index)
      adjacency(index).foreach { dependent =>
        inDegree(dependent) -= 1
        if inDegree(dependent) == 0 then available.add(dependent)
      }

    if sorted.length != count then
      val sortedSet = sorted.toSet
      val cycle = (0 until count).filterNot(sortedSet.contains).map(objectName).mkString(", ")
      Left(MigratorError.Apply(s"dependency cycle detected among: $cycle"))
    else Right(sorted.toList)

