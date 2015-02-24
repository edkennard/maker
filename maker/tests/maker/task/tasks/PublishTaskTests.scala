package maker.task.tasks

import org.scalatest.FreeSpec
import maker.utils.FileUtils._
import maker.project.{TestModule, Project, HasDummyCompiler}
import maker.Resource._
import maker.{Resource, MakerProps, MakerConfig}
import org.apache.commons.io.{FileUtils => ApacheFileUtils}
import maker.utils.RichString._

class PublishTaskTests 
  extends FreeSpec 
  with MakerConfig
{
  "test wether dynamic ivy is really needed" ignore{
    withTempDir{
      dir =>  

        val publishDir = file(dir, "publish").makeDir
        val ivySettingsFile_ = file(dir, "ivysettings.xml")
        writeToFile(
          ivySettingsFile_,
          """ |<ivysettings>
              |  <property name="ivy.default.conf.mappings" value="default->*" />
              |  <resolvers>
              |    <filesystem name="maker-local" m2compatible="true">
              |      <artifact pattern="%s/[module]/[revision]/[artifact]-[revision].[ext]" />
              |    </filesystem>
              |  </resolvers>
              |</ivysettings>""".stripMargin % publishDir)

        val proj = new TestModule(dir, "testPublish") with HasDummyCompiler{
          override def ivySettingsFile = ivySettingsFile_
          override def organization = Some("org.org")
        }

        proj.writeSrc(
          "testPublish/Foo.scala",
          """
          |package testPublish
          | 
          |case object Foo
          """.stripMargin
        )
        val version = "1.0-SNAPSHOT"
        proj.publish(version, "maker-local")

        val expectedPomText = 
           """|<?xml version="1.0" encoding="UTF-8"?>
              |<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              |    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
              |
              |  <modelVersion>4.0.0</modelVersion>
              |  <groupId>org.org</groupId>
              |  <artifactId>testPublish</artifactId>
              |  <packaging>jar</packaging>
              |  <version>1.0-SNAPSHOT</version>
              |  <dependencies>
              |    <dependency>
              |      <groupId>org.scala-lang</groupId>
              |      <artifactId>scala-library</artifactId>
              |      <version>""".stripMargin + config.scalaVersion + """</version>
              |      <scope>compile</scope>
              |    </dependency>
              |  </dependencies>
              |</project>""".stripMargin
        val publishedPomFile = file(dir, "publish-local/testPublish/1.0-SNAPSHOT/testPublish-1.0-SNAPSHOT.pom")
        val actualPomText = proj.publishLocalPomFile(version).readLines.mkString("\n")
        // TODO - proper XML test
        assert(expectedPomText === actualPomText)


    }

  }
}
