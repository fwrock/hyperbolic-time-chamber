import sbt.Keys.libraryDependencies
import scala.collection.Seq

ThisBuild / version := "2.0.0"

ThisBuild / scalaVersion := "3.3.5"

ThisBuild / scalacOptions ++= Seq("-Wconf:msg=match may not be exhaustive:error")

resolvers += "Akka library repository".at("https://repo.akka.io/maven")
resolvers += "Confluent".at("https://packages.confluent.io/maven/")

// Scala
val scalaTestVersion = "3.2.20"
val scalapbVersion = "0.11.11"

// Apache Pekko
val pekkoVersion = "1.5.0"
val pekkoManagementVersion = "1.2.1"
val jacksonVersion = "2.21.2"
val pekkoHttpVersion = "1.3.0"
val pekkoCassandraPersistenceVersion="1.1.0"

// Logs
val logbackVersion = "1.5.32"

// Serialization
val jacksonModuleVersion = "2.18.3"
val jacksonDatabindVersion = "2.18.3"
val jacksonDataTypeVersion = "2.18.3"
val kryoVersion = "1.5.1"
val protobufVersion = "4.34.1"
val pekkoProtobuf = "1.0.3"
val avroVersion = "1.12.0"
val confluentAvroVersion = "7.7.2"

// Parquet
val parquetVersion = "1.17.0"
val avroVersion2 = "1.12.1"
val snappyVersion = "1.1.10.8"
val zstdVersion = "1.5.7-7"
val hadoopVersion = "3.5.0"

// Connectors
val kafkaConnectorsVersion = "1.1.0"
val jedisVersion = "7.4.1"
val levelDBVersion = "1.8"
val sqliteJdbcVersion = "3.47.1.0"

// Prometheus Metrics
val prometheusVersion = "0.16.0"

// Others
val jolVersion = "0.17"


lazy val root = (project in file("."))
  .settings(
    name := "hyperbolic-time-chamber",
    idePackagePrefix := Some("org.interscity.htc"),
    assembly / assemblyJarName := "hyperbolic-time-chamber-1.24.3.jar",
    assembly / mainClass := Some("org.interscity.htc.main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", "org.slf4j.spi.SLF4JServiceProvider") => MergeStrategy.first
      case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", _*) => MergeStrategy.discard
      case PathList("reference.conf", _*)         => MergeStrategy.concat
      case "application.conf"                     => MergeStrategy.concat
      case "logback.xml"                          => MergeStrategy.concat
      case x if x.endsWith(".proto")              => MergeStrategy.discard
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
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-discovery-kubernetes-api" % pekkoManagementVersion,
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
      "redis.clients" % "jedis" % jedisVersion,
      "org.fusesource.leveldbjni" % "leveldbjni-all" % levelDBVersion,
      "org.xerial" % "sqlite-jdbc" % sqliteJdbcVersion,

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
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.2",

      // Parquet columnar format with Avro schema support
      "org.apache.parquet" % "parquet-avro" % parquetVersion,
      "org.apache.avro" % "avro" % avroVersion2 exclude("org.yaml", "snakeyaml"),
      "org.xerial.snappy" % "snappy-java" % snappyVersion,
      "com.github.luben" % "zstd-jni" % zstdVersion,

      ("org.apache.hadoop" % "hadoop-client-runtime" % hadoopVersion)
        .exclude("org.slf4j", "slf4j-reload4j")
        .exclude("log4j", "log4j"),

      // Logs
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "ch.qos.logback" % "logback-core" % logbackVersion,
      "org.slf4j" % "slf4j-api" % "2.0.17",

      "com.typesafe" % "config" % "1.4.6",

      // Observability
      "org.openjdk.jol" % "jol-core" % "0.17",

      // Prometheus Metrics (JVM + HTTP server)
      "io.prometheus" % "simpleclient" % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
      "io.prometheus" % "simpleclient_httpserver" % prometheusVersion,
      "io.prometheus" % "simpleclient_common" % prometheusVersion,

      // Test
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    ),
    
    dependencyOverrides ++= Seq(
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion
    ),
    
    Compile / PB.protoSources := Seq(
      baseDirectory.value / "src" / "main" / "protobuf"
    ),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
    ),
    PB.protocVersion := "4.30.2"
  )
