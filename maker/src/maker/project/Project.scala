package maker.project

import maker.utils.FileUtils._
import java.io.File
import maker.Props
import maker.utils.Implicits.RichString._

case class Project(
  name : String,
  root : File,
  immediateUpstreamModules:List[Module],
  props : Props,
  topLevelExcludedFolders:List[String] = Nil
) extends BaseProject  with TmuxIntegration{
  
  override def toString = name

  def constructorCodeAsString : String = {
    val b = new StringBuffer
    allUpstreamModules.foreach{
      m => 
        b.addLine(m.constructorCodeAsString)
    }
    b.addLine("""val %s = maker.project.Project("%s", new java.io.File("%s"), %s, maker.Props(new java.io.File("%s")))""" % (
      name, 
      name, 
      root.getAbsolutePath.toString, 
      allUpstreamModules.mkString("List(", ", ", ")"),
      props.root.getAbsolutePath + "/Maker.conf"
    ))
    b.toString
  }
  def docOutputDir = file(rootAbsoluteFile, "docs")
  def allUpstreamModules = immediateUpstreamModules.flatMap(_.allUpstreamModules).distinct
  def allUpstreamTestModules = allUpstreamModules
  def testClassNames() = {
    allUpstreamModules.flatMap(_.testClassNames())
  }
  def immediateUpstreamTestModules : List[Module] = Nil
  private val generator = IDEAProjectGenerator(props)
  def allModules = allUpstreamModules.flatMap(_.allUpstreamModules).distinct
  def generateIDEAProject() {

    generator.generateTopLevelModule(rootAbsoluteFile, name, topLevelExcludedFolders)
    generator.generateIDEAProjectDir(rootAbsoluteFile, name)
    allModules.foreach(module => generator.generateModule(module))
    generator.generateModulesFile(file(rootAbsoluteFile, ".idea"), this)
  }

}
