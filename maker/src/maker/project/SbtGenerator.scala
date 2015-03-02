package maker.project

import java.io.{File, FileWriter, Writer}
import maker.{Resource, ConfigPimps}
import maker.utils.FileUtils._
import org.apache.commons.io.{FileUtils => ApacheFileUtils}
import sbt._
import maker.task.Build
import com.sun.org.apache.xalan.internal.xsltc.cmdline.Compile
import com.typesafe.config.{ConfigFactory, Config}

// TODO: resources should not be copied to target stackoverflow.com/questions/25158689
class SbtGenerator(config : Config = ConfigFactory.load()) extends ConfigPimps{

  def generate(proj: BaseProject): Unit = {
    val outputDir = file(proj.rootAbsoluteFile, "project")
    outputDir.mkdirs()

    val propertiesFile = file(outputDir, "build.properties")
    ApacheFileUtils.writeStringToFile(propertiesFile, properties, false)

    val buildFile = file(outputDir, "Build.scala")
    ApacheFileUtils.writeStringToFile(buildFile, build(proj), false)

    val customisationsFile = file(outputDir, "MakerCustom.scala")
    if (!customisationsFile.exists)
      ApacheFileUtils.writeStringToFile(customisationsFile, customisations, false)

    val pluginsFile = file(outputDir, "plugins.sbt")
    if (!pluginsFile.exists)
      ApacheFileUtils.writeStringToFile(pluginsFile, plugins, false)
  }

  private val properties = "sbt.version = 0.13.5"

  private val customisations = """

/** This file is produced by maker's SbtGenerator on first run
  * and is made available for user customisations. It will not
  * be over-written.
  */
object MakerCustom {

  /** @param settings produced by maker
    * @return the settings to actually use
    */
  def customiseSettings(settings: Seq[Setting[_]]): Seq[Setting[_]] = settings

  /** @param project extracted by maker
    * @return the project to actually use
    */
  def customiseProject(project: Project): Project = project

}
"""

  private val plugins = """
// This file is produced by maker's SbtGenerator on first run
// and is made available for user customisations. It will not
// be over-written.

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

"""

  private def build(p: BaseProject): String = {
    """

/** Generated by maker's SbtGenerator
  * DO NOT EDIT: CHANGES WILL BE OVER-WRITTEN
  */
object MakerBuild extends Build {

  override val settings = super.settings ++ Seq(
    organization := """" + p.organization.getOrElse(???) + """",
    version := "1.0-SNAPSHOT", // no version in maker
    scalaVersion := """" + config.scalaVersion + """"
  )

  lazy val defaultSettings = customiseSettings(Seq(
    resolvers ++= Seq(
      """ + p.config.httpResolvers.map {
      case List(k, v) => "\"" + k + "\" at \"" + v + "\""
    }.mkString("", ",\n" + " " * 6, "") + """),
    sourcesInBase := false,
    javaSource in Compile <<= baseDirectory(_ / "src"),
    javaSource in Test <<= baseDirectory(_ / "tests"),
    scalaSource in Compile <<= baseDirectory(_ / "src"),
    scalaSource in Test <<= baseDirectory(_ / "tests"),
    resourceDirectory in Compile <<= baseDirectory(_ / "resources"),
    resourceDirectory in Test <<= baseDirectory(_ / "test-resources"),
    unmanagedResourceDirectories in Compile += baseDirectory.value / "resource_managed",
    target <<= baseDirectory(_ / "target-maker"),
    classDirectory in Compile <<= target(_ / "classes"),
    classDirectory in Test <<= target(_ / "test-classes"),
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8"),
    javacOptions in Compile ++= Seq("-encoding", "utf8"),
    fork := true,
    parallelExecution in Test := true,
    maxErrors := 1,
    baseDirectory in Test := file(System.getProperty("user.dir")),
    baseDirectory in run := file(System.getProperty("user.dir"))
  ))

  def module(dir: String) = Project(id = dir.replace(".", "_").replace("-", "_"), base = file(dir), settings = defaultSettings)

  """ + p.allUpstreamModules.map(generateModule).mkString("\n\n  ") + """

  lazy val root = customiseProject(Project(id = "parent", base = file("."), settings = defaultSettings) aggregate (
      """ + p.allUpstreamModules.map(refName).mkString(",") + """
  ) dependsOn (
      """ + p.immediateUpstreamModules.map(refName).mkString(",") + """
  ))
}
"""
  }

  private def generateModule(m: Module): String = {
    "lazy val " + refName(m) + " = customiseProject(module(\"" + m.name + """") dependsOn (
    """ + (m.immediateUpstreamModules.map(refName) ++
      // maker and sbt mean different things by a test dependency
      m.immediateUpstreamTestModules.map(refName(_) + " % \"test->test\"")).mkString(",") + """
  ) settings (
    libraryDependencies ++= List(
      """ +
    m.resources.filterNot(_.extension == "sources").map(formatResource(m, _)).distinct.mkString(",\n" + " " * 6) + """
    )
  ))"""
  }

  private def formatResource(m : Module, r: Resource): String = {
    val scalaV = "_" + config.scalaVersion
    val artifact =
      if (r.artifactId.endsWith(scalaV))
        "\" %% \"" + r.artifactId.replace(scalaV, "")
      else
        "\" % \"" + r.artifactId

    if (r.extension == "jar")
      "\"" + r.groupId + artifact + "\" % \"" + r.version + "\" jar() intransitive()"
    else
      "\"" + r.groupId + artifact + "\" % \"" + r.version + "\"" + "artifacts(Artifact(\"" +
        r.artifactId + "\",\"" + r.extension + "\",\"" + r.extension + "\")) intransitive()"
  }

  private def refName(m: Module) = m.name.replace(".", "_").replace("-", "_")
}
