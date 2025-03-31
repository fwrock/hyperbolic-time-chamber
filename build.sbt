import sbt.Keys.libraryDependencies
import scala.collection.Seq

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.5"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

// Apache Pekko
val pekkoVersion = "1.1.3"
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
      "org.apache.pekko" %% "pekko-protobuf-v3" % pekkoVersion,

      // Brokers
      "org.apache.pekko" %% "pekko-connectors-kafka" % kafkaConnectorsVersion,

      //Databases
      "org.apache.pekko" %% "pekko-connectors-cassandra" % cassandraConnectorsVersion,

      // Jackson
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonModuleVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonDataTypeVersion,

      // Kryo
      "io.altoo" %% "pekko-kryo-serialization" % kryoVersion,

      // Protobuf
      "com.google.protobuf" % "protobuf-java" % protobufVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.1",

      // Logs
      "ch.qos.logback" % "logback-classic" % logbackVersion,

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
