ThisBuild / organization := "com.sslproxy"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / wartremoverErrors ++= Warts.allBut(
  Wart.Any,
  Wart.Nothing,
  Wart.ImplicitParameter,
  Wart.DefaultArguments,
  Wart.NonUnitStatements,
  Wart.Null,
  Wart.ToString,
  Wart.AsInstanceOf,
  Wart.IsInstanceOf,
  Wart.MutableDataStructures,
  Wart.Overloading,
  Wart.Var,
  Wart.While,
  Wart.Throw,
  Wart.Return,
  Wart.OptionPartial,
  Wart.EitherProjectionPartial,
  Wart.StringPlusAny,
  Wart.FinalCaseClass,
  Wart.ArrayEquals,
  Wart.JavaSerializable,
  Wart.Serializable,
  Wart.Product,
  Wart.SortedMaxMin,
  Wart.LeakingSealed,
  Wart.PlatformDefault,
  Wart.Equals,
  Wart.ListAppend,
  Wart.Recursion,
  Wart.Option2Iterable,
  Wart.IterableOps,
  Wart.SeqApply,
  Wart.SizeIs,
  Wart.RedundantIsInstanceOf
)

val catsEffectVersion = "3.6.3"
val fs2Version = "3.12.2"
val declineVersion = "2.5.0"
val circeVersion = "0.14.14"
val doobieVersion = "1.0.0-RC10"
val http4sVersion = "0.23.34"
val mongoDriverVersion = "5.8.0"
val catsMtlVersion = "1.7.0"
val oracleJdbcVersion = "23.6.0.24.10"
val oracleOsdtVersion = "21.18.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "schema-migrator",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "cats-mtl" % catsMtlVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "com.monovore" %% "decline" % declineVersion,
      "com.monovore" %% "decline-effect" % declineVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "com.auth0" % "java-jwt" % "4.4.0",
      "org.mongodb" % "mongodb-driver-sync" % mongoDriverVersion,
      "org.apache.commons" % "commons-compress" % "1.27.1",
      "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
      "org.slf4j" % "slf4j-simple" % "2.0.17",
      "org.postgresql" % "postgresql" % "42.7.5",
      "com.oracle.database.jdbc" % "ojdbc11" % oracleJdbcVersion,
      "com.oracle.database.security" % "oraclepki" % oracleJdbcVersion,
      "com.oracle.database.security" % "osdt_core" % oracleOsdtVersion,
      "com.oracle.database.security" % "osdt_cert" % oracleOsdtVersion,
      "com.h2database" % "h2" % "2.4.240" % Test,
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    scalacOptions ++= Seq("-Yfuture-lazy-vals", "-java-output-version:11"),
    Compile / mainClass := Some("com.sslproxy.schema.Main"),
    assembly / assemblyJarName := "schema-migrator.jar",
    assembly / mainClass := Some("com.sslproxy.schema.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
      case "reference.conf"                           => MergeStrategy.concat
      case _                                          => MergeStrategy.first
    },
    Compile / run / fork := true,
    Test / fork := true
  )
