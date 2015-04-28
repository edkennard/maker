import scala.util.Properties

Properties.setProp("scala.usejavacp", "false")
Properties.setProp("log4j.ignoreTCL", "true")



import scala.xml.NodeSeq
import maker.task.FailingTests._
import maker.task.compile._
import maker.project._
import maker.ScalaVersion
import maker.utils.FileUtils._
import org.eclipse.aether.util.artifact.JavaScopes

import DependencyPimps._

val extraPomInfo : List[NodeSeq] = {
  val devNodes = List("Alex McGuire", "Louis Botterill", "Sam Halliday").map{name => <developer><name>{name}</name></developer>}
  List(
    <name>Maker</name>
    <description>A scala build tool</description>
    <developers>{devNodes}</developers>
    <licenses>
      <license>
        <name>BSD 2 clause</name>
      </license>
    </licenses>
    <url>https:github.com/cage433/maker</url> 
    <scm>
      <url>https://github.com/cage433/maker</url> 
    </scm>
  )
}

lazy val testReporterModule = new Module(
  root = file("test-reporter").asAbsoluteFile, 
  name = "maker-test-reporter"
) with ClassicLayout with TmuxIntegration {
  override def dependencies() = {
    Vector(
      "org.scalatest" % "scalatest" %%  "2.2.0"
    )
  }
  override def defaultScalaVersion : ScalaVersion = ScalaVersion.TWO_TEN_DEFAULT
}

lazy val testReporterProject = new Project(
  "maker-test-reporter",
  root = file("test-reporter").asAbsoluteFile, 
  immediateUpstreamModules = List(testReporterModule)
){
  override def extraProjectPomInfo = extraPomInfo
  override def organization = Some("com.github.cage433")
}

lazy val makerModule = new Module(
  root = file("maker").asAbsoluteFile,
  name = "maker"
) with ClassicLayout with Bootstrapper {
  override def dependencies() = {
    Vector(
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
      "org.mortbay.jetty" % "jetty" % "6.1.26"
    )
  }
  override def defaultScalaVersion : ScalaVersion = ScalaVersion.TWO_TEN_DEFAULT
}

lazy val makerProject = new Project("maker", file("."), List(makerModule)) with TmuxIntegration {
  override def extraProjectPomInfo = extraPomInfo
  override def organization = Some("com.github.cage433")
  override def defaultScalaVersion : ScalaVersion = ScalaVersion.TWO_TEN_DEFAULT
} 
// Used to disambiguate which maker is running in the repl.
def pwd = println(Properties.userDir)

def publishSnapshots(version : String, scalaVersionString : String) = {
  val scalaVersion = ScalaVersion(scalaVersionString)
  testReporterProject.publishSonatypeSnapshot(version, scalaVersion) andThen 
  makerProject.publishSonatypeSnapshot(version, scalaVersion)
}

def publishRelease(version : String, scalaVersionString : String) = {
  val scalaVersion = ScalaVersion(scalaVersionString)
  testReporterProject.publishToSonatype(version, scalaVersion) andThen makerProject.publishToSonatype(version, scalaVersion)
}

import makerProject._
