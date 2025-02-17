import sbt.Keys.libraryDependencies

import scala.collection.Seq

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.5"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

val pekkoVersion = "1.1.3"
val pekkoHttpVersion = "1.1.0"
val logbackVersion = "1.5.16"
val jacksonModuleVersion = "2.18.2"
val jacksonDatabindVersion = "2.18.2"
val cassandraConnectorsVersion = "1.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "hyperbolic-time-chamber",
    idePackagePrefix := Some("org.interscity.htc"),
    libraryDependencies ++= Seq(
      // Apache Pekko
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-tools" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,

      //Databases
      "org.apache.pekko" %% "pekko-connectors-cassandra" % cassandraConnectorsVersion,

      // Jackson
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonModuleVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion,

      // Logs
      "ch.qos.logback" % "logback-classic" % logbackVersion,

      // Faker
      "com.github.javafaker" % "javafaker" % "1.0.2",
      "com.typesafe" % "config" % "1.4.3",

      // Test
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    )
  )
