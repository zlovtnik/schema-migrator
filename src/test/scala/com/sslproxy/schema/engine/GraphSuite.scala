package com.sslproxy.schema.engine

import munit.FunSuite

class GraphSuite extends FunSuite:
  test("sorts linear chain dependencies first") {
    val sorted = Graph.topologicalSort(List(obj("c", "b"), obj("a"), obj("b", "a"))).toOption.get
    assertEquals(sorted.map(_.objectName), List("a", "b", "c"))
  }

  test("ignores unknown external dependencies") {
    val sorted = Graph.topologicalSort(List(obj("a", "pgvector extension"), obj("b"))).toOption.get
    assertEquals(sorted.map(_.objectName), List("a", "b"))
  }

  test("reports cycle members") {
    val error = Graph.topologicalSort(List(obj("a", "c"), obj("b", "a"), obj("c", "b"))).left.toOption.get
    assert(error.getMessage.contains("dependency cycle detected among: a, b, c"))
  }

  private def obj(name: String, deps: String*): SchemaObject =
    SchemaObject(
      kind = "table",
      objectName = name,
      sourceFile = s"tables/$name.sql",
      dependsOn = deps.toList,
      rollbackFile = None,
      transactional = true,
      rawSql = s"create table if not exists $name(id int);",
      canonicalSql = s"create table if not exists $name(id int);",
      sha256 = name
    )

