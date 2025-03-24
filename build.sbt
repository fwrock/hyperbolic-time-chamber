import sbt.Keys.libraryDependencies

import scala.collection.Seq

ThisBuild / version := "0.1.0"

ThisBuild / scalaVersion := "3.3.5"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

val pekkoVersion = "1.1.3"
val pekkoManagementVersion = "1.1.0"
val logbackVersion = "1.5.18"
val jacksonVersion = "2.18.3"
val kryoVersion = "1.2.1"

val cassandraConnectorsVersion = "1.1.0"

val kafkaConnectorsVersion = "1.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "hyperbolic-time-chamber",
    idePackagePrefix := Some("org.interscity.htc"),
    assembly / assemblyJarName := "hyperbolic-time-chamber-0.1.0.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", "org.slf4j.spi.SLF4JServiceProvider") => MergeStrategy.first
      case PathList("META-INF", _*) => MergeStrategy.discard
      case PathList("reference.conf", _*)         => MergeStrategy.concat
      case "application.conf"                     => MergeStrategy.concat
      case "logback.xml"                          => MergeStrategy.concat
      case x => MergeStrategy.first
    },
    libraryDependencies ++= Seq(
      // Apache Pekko
      "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-management" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-tools" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,

      // Brokers
      "org.apache.pekko" %% "pekko-connectors-kafka" % kafkaConnectorsVersion,

      //Databases
      "org.apache.pekko" %% "pekko-connectors-cassandra" % cassandraConnectorsVersion,

      // Jackson
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,

      // Kryo
      "io.altoo" %% "pekko-kryo-serialization" % kryoVersion,

      // Logs
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "ch.qos.logback" % "logback-core" % logbackVersion,
      "org.slf4j" % "slf4j-api" % "2.0.17",

      // Faker
      "com.github.javafaker" % "javafaker" % "1.0.2",
      "com.typesafe" % "config" % "1.4.3",

      // Test
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    )
  )
