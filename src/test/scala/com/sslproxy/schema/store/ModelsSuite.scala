package com.sslproxy.schema.store

import munit.FunSuite

class ModelsSuite extends FunSuite:
  test("target string rendering redacts Oracle thin URL passwords") {
    val target = Target(
      id = "target-1",
      label = "Oracle",
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:oracle:thin:user/secret@//db.example:1521/FREE",
      created_at = "2026-06-28T12:00:00Z",
      repo_url = "https://example.com/schema-migrator.git",
      repo_branch = "main",
      repo_sql_path = "sql",
      last_synced_commit = None,
      last_synced_at = None
    )

    val rendered = target.toString

    assert(rendered.contains("jdbc:oracle:thin:user/<redacted>@//db.example:1521/FREE"))
    assert(!rendered.contains("secret"))
  }

  test("target payload string rendering redacts query and userinfo credentials") {
    val payload = TargetPayload(
      label = "Postgres",
      app_name = "app",
      env = "dev",
      jdbc_url = "jdbc:postgresql://app:secret@db.example/app?password=query-secret&pwd=short-secret",
      password = Some("body-secret"),
      repo_url = "https://example.com/schema-migrator.git",
      repo_branch = "main",
      repo_sql_path = "sql"
    )

    val rendered = payload.toString

    assert(!rendered.contains("secret"))
    assert(rendered.contains("password=<redacted>"))
    assert(rendered.contains("pwd=<redacted>"))
    assert(rendered.contains("app:<redacted>@db.example"))
  }
