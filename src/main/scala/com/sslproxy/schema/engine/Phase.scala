package com.sslproxy.schema.engine

enum Phase:
  case Structural, Behavioral

object Phase:
  def forKind(kind: String): Phase =
    kind match
      case "extension" | "schema" | "type" | "table" | "index" | "sql_file" | "baseline" =>
        Structural
      case _ => Behavioral
