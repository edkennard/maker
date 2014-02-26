package maker.task.publish

import org.scalatest.FreeSpec
import maker.utils.FileUtils._
import maker.project.TestModule
import maker.utils.Implicits.RichString._
import maker.project.Project
import maker.MakerProps

class IvyUtilsTests extends FreeSpec {
  "test dynamic ivy file" in {
    withTempDir{
      dir => 
        val props = MakerProps.initialiseTestProps(dir)
        val a = TestModule(file(dir, "a").makeDir, "a", props)
        val b = TestModule(file(dir, "b").makeDir, "b", props, List(a))
        val c = new Project("c", dir, List(a, b), props)

        // Not needed as we aren't running tests - and ivy will just try to get them
        file(dir, "a/external-resources").delete
        file(dir, "b/external-resources").delete
        
        assert(
          IvyUtils.generateIvyFile(b).readLines.mkString("\n") === 
          """|<ivy-module version="1.0" xmlns:e="http://ant.apache.org/ivy/extra">
             |  <info revision="${maker.module.version}" module="b" organisation="MakerTestGroupID"></info>
             |  <configurations>
             |    <conf name="default" transitive="false"></conf>
             |    <conf name="compile" transitive="false"></conf>
             |    <conf name="test" transitive="false"></conf>
             |  </configurations>
             |  <publications>
             |    <artifact type="pom" name="b"></artifact>
             |    <artifact conf="default" type="jar" name="b" ext="jar"></artifact>
             |  </publications>
             |  <dependencies defaultconfmapping="${ivy.default.conf.mappings}">
             |    <exclude org="MakerTestGroupID" module="a"></exclude>
             |    <exclude org="MakerTestGroupID" module="b"></exclude>
             |  </dependencies>
             |</ivy-module>""".stripMargin
        )
        assert(
          IvyUtils.generateIvyFile(c).readLines.mkString("\n") === 
          """|<ivy-module version="1.0" xmlns:e="http://ant.apache.org/ivy/extra">
             |  <info revision="${maker.module.version}" module="c" organisation="MakerTestGroupID"></info>
             |  <configurations>
             |    <conf name="default" transitive="false"></conf>
             |    <conf name="compile" transitive="false"></conf>
             |    <conf name="test" transitive="false"></conf>
             |  </configurations>
             |  <publications>
             |    <artifact type="pom" name="c"></artifact>
             |    <artifact conf="default" type="jar" name="c" ext="jar"></artifact>
             |  </publications>
             |  <dependencies defaultconfmapping="${ivy.default.conf.mappings}">
             |    <exclude org="MakerTestGroupID" module="a"></exclude>
             |    <exclude org="MakerTestGroupID" module="b"></exclude>
             |    <exclude org="MakerTestGroupID" module="c"></exclude>
             |  </dependencies>
             |</ivy-module>""".stripMargin)
    }
  }
}