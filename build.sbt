ThisBuild / organization := "com.sslproxy"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.6"

val catsEffectVersion = "3.6.3"
val fs2Version = "3.12.2"
val declineVersion = "2.5.0"
val circeVersion = "0.14.14"
val doobieVersion = "1.0.0-RC10"
val oracleJdbcVersion = "23.6.0.24.10"
val oracleOsdtVersion = "21.18.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "schema-migrator",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "com.monovore" %% "decline" % declineVersion,
      "com.monovore" %% "decline-effect" % declineVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
      "org.slf4j" % "slf4j-simple" % "2.0.17",
      "org.postgresql" % "postgresql" % "42.7.5",
      "com.oracle.database.jdbc" % "ojdbc11" % oracleJdbcVersion,
      "com.oracle.database.security" % "oraclepki" % oracleJdbcVersion,
      "com.oracle.database.security" % "osdt_core" % oracleOsdtVersion,
      "com.oracle.database.security" % "osdt_cert" % oracleOsdtVersion,
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    Compile / mainClass := Some("com.sslproxy.schema.Main"),
    Compile / run / fork := true,
    Test / fork := true
  )
