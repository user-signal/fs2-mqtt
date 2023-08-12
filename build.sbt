import sbt.Keys._
import sbt._

lazy val scala3 = "3.3.0"
lazy val scala213 = "2.13.10"
lazy val scala212 = "2.12.18"
lazy val supportedScalaVersion = Seq(scala3, scala213, scala212)

lazy val IntegrationTest = config("it").extend(Test)

val filterConsoleScalacOptions = { options: Seq[String] =>
  options.filterNot(
    Set(
      "-Xfatal-warnings",
      "-Werror",
      "-Wdead-code",
      "-Wunused:imports",
      "-Ywarn-unused:imports",
      "-Ywarn-unused-import",
      "-Ywarn-dead-code"
    )
  )
}

lazy val commonSettings = Seq(
  homepage := Some(url("https://github.com/user-signal/fs2-mqtt")),
  licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "fcabestre",
      "Frédéric Cabestre",
      "frederic.cabestre@sigusr.net",
      url("https://github.com/fcabestre")
    )
  ),
  organization := "net.sigusr",
  scalaVersion := scala3,
  crossScalaVersions := supportedScalaVersion,
  coverageExcludedPackages := "net.sigusr.mqtt.examples",
  scalacOptions := Seq(
    "-encoding",
    "utf-8",
    "-feature",
    "-language:existentials",
    "-language:experimental.macros",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-Xfatal-warnings",
    "-unchecked"
  ),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      Seq(
        "-Xcheckinit",
        "-Xlint:adapted-args",
        "-Xlint:constant",
        "-Xlint:delayedinit-select",
        "-Xlint:doc-detached",
        "-Xlint:inaccessible",
        "-Xlint:infer-any",
        "-Xlint:missing-interpolator",
        "-Xlint:nullary-override",
        "-Xlint:nullary-unit",
        "-Xlint:option-implicit",
        "-Xlint:package-object-classes",
        "-Xlint:poly-implicit-overload",
        "-Xlint:private-shadow",
        "-Xlint:stars-align",
        "-explaintypes"
      )
    case Some((2, 13)) =>
      Seq(
        "-Wdead-code",
        "-Wextra-implicit",
        "-Wnumeric-widen",
        "-Wunused:implicits",
        "-Wunused:imports",
        "-Wunused:locals",
        "-Wunused:params",
        "-Wunused:patvars",
        "-Wunused:privates",
        "-Wvalue-discard",
        "-Xcheckinit",
        "-Xlint:adapted-args",
        "-Xlint:constant",
        "-Xlint:delayedinit-select",
        "-Xlint:deprecation",
        "-Xlint:doc-detached",
        "-Xlint:inaccessible",
        "-Xlint:infer-any",
        "-Xlint:missing-interpolator",
        "-Xlint:nullary-unit",
        "-Xlint:option-implicit",
        "-Xlint:package-object-classes",
        "-Xlint:poly-implicit-overload",
        "-Xlint:private-shadow",
        "-Xlint:stars-align",
        "-Xlint:type-parameter-shadow",
        "-Ymacro-annotations",
        "-explaintypes"
      )
    case _ => Seq()
  }),
  Compile / unmanagedSourceDirectories ++= {
    val sourceDir = (Compile / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) => Seq(sourceDir / "scala-3")
      case _            => Seq(sourceDir / "scala-2")
    }
  },
  Compile / console / scalacOptions ~= filterConsoleScalacOptions,
  Test / scalacOptions ++= {
    val sourceDir = (Compile / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) => Seq()
      case _            => Seq("-Yrangepos")
    }
  },
  Test / console / scalacOptions ~= filterConsoleScalacOptions,
  versionScheme := Some("semver-spec")
)

lazy val fs2_mqtt = project
  .in(file("."))
  .settings(
    Seq(
      publish / skip := true,
      sonatypeProfileName := "net.sigusr"
    )
  )
  .aggregate(core, examples)

lazy val core = project
  .in(file("core"))
  .configs(IntegrationTest)
  .settings(
    commonSettings ++ testSettings ++ pgpSettings ++ publishingSettings ++ Seq(
      name := "fs2-mqtt",
      libraryDependencies ++= Seq(
        ("org.specs2" %% "specs2-core" % "4.20.2" % "test").cross(CrossVersion.for3Use2_13),
        "org.typelevel" %% "cats-effect-testing-specs2" % "1.4.0" % "test",
        "org.typelevel" %% "cats-effect-laws" % "3.3.8" % "test",
        "org.typelevel" %% "cats-effect-testkit"% "3.3.8" % "test",
        "org.scodec" %% "scodec-stream" % "3.0.2",
        "co.fs2" %% "fs2-io" % "3.2.5",
        "org.typelevel" %% "cats-effect" % "3.3.8",
        "com.github.cb372" %% "cats-retry" % "3.1.0"
      ) ++ {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((3, _)) => Seq()
          case _ =>
            Seq(
              ("com.beachape" %% "enumeratum" % "1.7.3").cross(CrossVersion.for3Use2_13)
            )
        }
      }
    )
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core)
  .settings(
    commonSettings ++ Seq(
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-interop-cats" % "3.2.9.1"
      ),
      publish / skip := true
    )
  )

def itFilter(name: String): Boolean = name.startsWith("net.sigusr.mqtt.integration")
def unitFilter(name: String): Boolean = !itFilter(name)

def testSettings =
  Seq(
    Test / testOptions := Seq(Tests.Filter(unitFilter)),
    IntegrationTest / testOptions := Seq(Tests.Filter(itFilter))
  ) ++ inConfig(IntegrationTest)(Defaults.testTasks)

import com.jsuereth.sbtpgp.PgpKeys.{gpgCommand, pgpSecretRing, useGpg}

def pgpSettings =
  Seq(
    useGpg.withRank(KeyRanks.Invisible) := true,
    gpgCommand.withRank(KeyRanks.Invisible) := "/usr/bin/gpg",
    pgpSecretRing.withRank(KeyRanks.Invisible) := file("~/.gnupg/secring.gpg")
  )

val ossSnapshots = "Sonatype OSS Snapshots".at("https://oss.sonatype.org/content/repositories/snapshots/")
val ossStaging = "Sonatype OSS Staging".at("https://oss.sonatype.org/service/local/staging/deploy/maven2/")

def projectUrl = "https://github.com/user-signal/fs2-mqtt"
def developerId = "fcabestre"
def developerName = "Frédéric Cabestre"
def licenseName = "Apache-2.0"
def licenseDistribution = "repo"
def scmUrl = projectUrl
def scmConnection = "scm:git:" + scmUrl

def publishingSettings: Seq[Setting[_]] =
  Seq(
    Test / publishArtifact := false,
    pomIncludeRepository := (_ => false)
  )
