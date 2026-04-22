addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
// TODO: Re-enable when Avro plugin issue is resolved
// addSbtPlugin("com.github.sbt" % "sbt-avro" % "3.4.4")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.17"