package com.sslproxy.schema.db

import com.sslproxy.schema.config.DbKind
import munit.FunSuite

class TargetDescriptorSuite extends FunSuite:
  test("parses supported database kinds and hosts in one place") {
    val postgres = TargetDescriptor.parse("jdbc:postgresql://db.example:5432/app").toOption.get
    val oracle = TargetDescriptor.parse("jdbc:oracle:thin:@//oracle.example:1521/FREE").toOption.get
    val tidb = TargetDescriptor.parse("jdbc:mysql://tidb.example:4000/app").toOption.get

    assertEquals(postgres.dbKind, DbKind.Postgres)
    assertEquals(postgres.host, Some("db.example"))
    assertEquals(oracle.dbKind, DbKind.Oracle)
    assertEquals(oracle.host, Some("oracle.example"))
    assertEquals(tidb.dbKind, DbKind.TiDB)
    assertEquals(tidb.host, Some("tidb.example"))
  }

  test("rejects Oracle URLs without connection content") {
    val expected: Either[String, TargetDescriptor] = Left("unsupported database URL for migration execution")

    assertEquals(TargetDescriptor.parse("jdbc:oracle:thin:"), expected)
    assertEquals(TargetDescriptor.parse("jdbc:oracle:thin:   "), expected)
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
