package com.sslproxy.schema.discovery

import java.nio.file.Path

final case class SqlFile(folder: String, path: Path, name: String, relativePath: String)

final case class DiscoveryResult(files: List[SqlFile], warnings: List[String])
