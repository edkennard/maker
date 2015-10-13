import sbt._
import sbt.Keys._

object Maker extends Build {

  // Override SBT's default root project in order to exclude maker.git/Maker.scala
  lazy val root = Project(
    id = "root",
    base = file("."),
    settings = Seq(
      sourcesInBase := false
    )
  ).in(file(".")).aggregate(maker)

  // Configuration module needed so that references.conf can be included in the classpath
  lazy val config = Project(
    id = "config",
    base = file("config"),
    settings = Defaults.coreDefaultSettings ++ Seq(
      organization := "com.github.cage433",
      version := "0.15",
      scalaVersion := "2.10.4",
      resourceDirectory in Compile := baseDirectory.value
    )
  )

  lazy val maker = Project(
    id = "maker",
    base = file("maker"),
    settings = Defaults.coreDefaultSettings ++ Seq(

      organization := "com.github.cage433",
      version := "0.15",
      scalaVersion := "2.10.4",
      scalacOptions := Seq(
        "-unchecked",
        "-feature",
        //"-Xfatal-warnings", // No time to fix deprecation errors right now
        //"-deprecation",     // No time to fix deprecation errors right now
        "-language:implicitConversions"),

      scalaSource in Compile := baseDirectory.value / "src",
      scalaSource in Test := baseDirectory.value / "tests",
      resourceDirectory in Compile := baseDirectory.value / "resources",
      resourceDirectory in Test := baseDirectory.value / "test-resources",

      libraryDependencies ++= Seq(
        "org.scalatest" % "scalatest_2.10" % "2.2.0",
        "ch.qos.logback" % "logback-classic" % "1.0.6",
        "org.slf4j" % "jcl-over-slf4j" % "1.6.1",
        "commons-io" % "commons-io" % "2.1",
        "com.typesafe.zinc" % "zinc" % "0.3.7",
        "org.apache.httpcomponents" % "httpclient" % "4.3",
        "org.apache.ivy" % "ivy" % "2.3.0-rc2",
        "org.scalaz" % "scalaz-core_2.10" % "7.0.1",
        "com.google.guava" % "guava" %  "11.0.2",
        "com.typesafe" % "config" % "1.2.1",
        "io.spray" % "spray-json_2.10" % "1.3.1",
        "javax.inject" % "javax.inject" %  "1",
        "org.apache.commons" % "commons-exec" % "1.3",
        "org.apache.maven" % "maven-aether-provider" % "3.2.5",
        "org.eclipse.aether" % "aether-connector-basic" % "1.0.0.v20140518",
        "org.eclipse.aether" % "aether-impl" % "1.0.0.v20140518",
        "org.eclipse.aether" % "aether-transport-file" % "1.0.0.v20140518",
        "org.eclipse.aether" % "aether-transport-http" % "1.0.0.v20140518",
        "org.eclipse.aether" %"aether-test-util" % "1.0.0.v20140518",
        "org.mortbay.jetty" % "jetty" % "6.1.26",
        "com.github.cage433" % "maker-test-reporter_2.10" % "0.15"
      )
    )
  ).dependsOn(config)
}
