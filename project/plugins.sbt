addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6") // Verifique a versão mais recente

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.17"