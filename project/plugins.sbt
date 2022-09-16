addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.3")
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1032048a")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.3")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.10"
