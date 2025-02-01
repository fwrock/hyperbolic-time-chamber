package org.interscity.htc.core

import scala.collection.immutable.Seq

ThisBuild / version := "0.1.0"

ThisBuild / scalaVersion := "3.3.4"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

val pekkoVersion = "1.1.3"
val pekkoHttpVersion = "1.1.0"
val logbackVersion = "1.5.16"
val jacksonModuleVersion = "2.18.2"
val jacksonDatabindVersion = "2.18.2"

lazy val root = (project in file("."))
  .settings(
    name := "hyperbolic-time-chamber",
    idePackagePrefix := Some("org.interscity.htc"),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,

      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonModuleVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion,

      "com.github.javafaker" % "javafaker" % "1.0.2",
      "com.typesafe" % "config" % "1.4.3",

      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    )
  )


