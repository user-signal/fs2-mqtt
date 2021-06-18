import sbt.Keys._
import sbt._

lazy val scala3 = "3.0.0"
lazy val scala213 = "2.13.4"
lazy val scala212 = "2.12.12"
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
  organization := "net.sigusr",
  scalaVersion := scala213,
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
    case Some((2, 12)) => Seq(
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
      "-explaintypes",
    )
    case Some((2, 13)) => Seq(
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
      "-explaintypes",
    )
    case _ => Seq() 
  }),
  Compile / unmanagedSourceDirectories ++= {
    val sourceDir = (Compile / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _))  => Seq(sourceDir / "scala-3")
      case _             => Seq(sourceDir / "scala-2")
    }
  },
  Compile / console / scalacOptions ~= filterConsoleScalacOptions,
  Test / scalacOptions ++= {
    val sourceDir = (Compile / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _))  => Seq()
      case _             => Seq("-Yrangepos")
    }
  },
  Test / console / scalacOptions ~= filterConsoleScalacOptions,
  versionScheme := Some("semver-spec")
)

lazy val root = (project in file(".")).aggregate(core, examples)

lazy val core = project
  .in(file("core"))
  .configs(IntegrationTest)
  .settings(
    commonSettings ++ testSettings ++ pgpSettings ++ publishingSettings ++ Seq(
      name := """fs2-mqtt""",
      version := "0.6.0-SNAPSHOT",
      libraryDependencies ++= Seq(
        ("org.specs2" %% "specs2-core" % "4.12.0" % "test").cross(CrossVersion.for3Use2_13),
        ("com.codecommit" %% "cats-effect-testing-specs2" % "0.5.0" % "test").cross(CrossVersion.for3Use2_13),
        "org.typelevel" %% "cats-effect-laws" % "3.1.1" % "test",

        "org.scodec" %% "scodec-stream" % "2.0.2",
        
        "co.fs2" %% "fs2-io" % "2.5.6",
        "org.typelevel" %% "cats-effect" % "3.1.1",
        ("com.github.cb372" %% "cats-retry" % "2.1.0").cross(CrossVersion.for3Use2_13)
      ) ++ {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((3, _))  => Seq()
          case _             => Seq(
            ("com.beachape" %% "enumeratum" % "1.6.1").cross(CrossVersion.for3Use2_13),
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
        "io.monix" %% "monix" % "3.4.0",
        "dev.zio" %% "zio-interop-cats" % "2.5.1.0"
      ),
      publish := ((): Unit),
      publishLocal := ((): Unit),
      publishArtifact := false
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
def licenseUrl = "http://opensource.org/licenses/Apache-2.0"
def licenseDistribution = "repo"
def scmUrl = projectUrl
def scmConnection = "scm:git:" + scmUrl

def generatePomExtra(scalaVersion: String): xml.NodeSeq =
  <url>
    {projectUrl}
  </url>
    <licenses>
      <license>
        <name>
          {licenseName}
        </name>
        <url>
          {licenseUrl}
        </url>
        <distribution>
          {licenseDistribution}
        </distribution>
      </license>
    </licenses>
    <scm>
      <url>
        {scmUrl}
      </url>
      <connection>
        {scmConnection}
      </connection>
    </scm>
    <developers>
      <developer>
        <id>
          {developerId}
        </id>
        <name>
          {developerName}
        </name>
      </developer>
    </developers>

def publishingSettings: Seq[Setting[_]] =
  Seq(
    credentialsSetting,
    publishMavenStyle := true,
    publishTo := version((v: String) => Some(if (v.trim.endsWith("SNAPSHOT")) ossSnapshots else ossStaging)).value,
    Test / publishArtifact := false,
    pomIncludeRepository := (_ => false),
    pomExtra := scalaVersion(generatePomExtra).value
  )

lazy val credentialsSetting = credentials += {
  Seq("SONATYPE_USER", "SONATYPE_PASS").map(k => sys.env.get(k)) match {
    case Seq(Some(user), Some(pass)) =>
      Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
    case _ =>
      Credentials(Path.userHome / ".ivy2" / ".credentials")
  }
}
