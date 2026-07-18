package com.sslproxy.schema.db

import com.sslproxy.schema.config.DbKind
import munit.FunSuite

class TargetDescriptorSuite extends FunSuite:
  test("parses supported database kinds and hosts in one place") {
    val postgres = TargetDescriptor.parse("jdbc:postgresql://db.example:5432/app").toOption.get
    val oracle = TargetDescriptor.parse("jdbc:oracle:thin:@//oracle.example:1521/FREE").toOption.get

    assertEquals(postgres.dbKind, DbKind.Postgres)
    assertEquals(postgres.host, Some("db.example"))
    assertEquals(oracle.dbKind, DbKind.Oracle)
    assertEquals(oracle.host, Some("oracle.example"))
  }

  test("rejects unsupported JDBC URLs") {
    assert(TargetDescriptor.parse("jdbc:mysql://db.example/app").isLeft)
  }

  test("rejects ambiguous multi-host Oracle descriptors for host allow-listing") {
    val descriptor = TargetDescriptor
      .parse(
        "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=db1.example))(ADDRESS=(HOST=db2.example))(CONNECT_DATA=(SERVICE_NAME=FREE)))"
      )
      .toOption
      .get

    assertEquals(descriptor.host, None)
  }
