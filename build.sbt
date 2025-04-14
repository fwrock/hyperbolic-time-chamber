import sbt.Keys.libraryDependencies
import scala.collection.Seq

ThisBuild / version := "1.0.0"

ThisBuild / scalaVersion := "3.3.5"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

// Apache Pekko
val pekkoVersion = "1.1.3"
val pekkoManagementVersion = "1.1.0"
val jacksonVersion = "2.18.3"
val pekkoHttpVersion = "1.1.0"

// Logs
val logbackVersion = "1.5.18"

// Serialization
val jacksonModuleVersion = "2.18.3"
val jacksonDatabindVersion = "2.18.3"
val jacksonDataTypeVersion = "2.18.3"
val kryoVersion = "1.2.1"
val protobufVersion = "4.30.2"
val pekkoProtobuf = "1.0.3"

// Connectors
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
      "org.apache.pekko" %% "pekko-persistence" % pekkoVersion,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-management" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-tools" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoVersion,
      "org.apache.pekko" %% "pekko-remote" % pekkoVersion,

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

      // Protobuf
      "com.google.protobuf" % "protobuf-java" % protobufVersion,

      // Protobuf
      "com.google.protobuf" % "protobuf-java" % protobufVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.1",

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
    ),
    Compile / PB.protoSources := Seq(
      baseDirectory.value / "src" / "main" / "protobuf"
    ),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
    ),
    PB.protocVersion := "-v4.30.2"
  )
