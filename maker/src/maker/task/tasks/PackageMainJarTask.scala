package maker.task.tasks

import maker.task.Task
import maker.project.Module
import maker.task.TaskResult
import maker.utils.Stopwatch
import maker.utils.os.Command
import maker.utils.FileUtils._
import java.io.File
import maker.task.compile._
import maker.task.SingleModuleTask
import maker.task.DefaultTaskResult

case class PackageMainJarTask(module : Module) 
  extends SingleModuleTask(module)
{
  def name = "Package Jar"
  val props = module.props
  val log = props.log

  def upstreamTasks = SourceCompileTask(module) :: module.immediateUpstreamModules.map(PackageMainJarTask)

  // Note: until we support scopes properly we have to be careful what we put on the runtime classpath
  //   and in package runtime binary artifacts (so test scope content is deliberately excluded here)
  private lazy val mainDirsToPack : List[File] =  List(module.compilePhase.outputDir, module.resourceDir).filter(_.exists)


  def exec(results : Iterable[TaskResult], sw : Stopwatch) = {
    synchronized{
      if (fileIsLaterThan(module.outputArtifact, mainDirsToPack)) {
        log.info("Packaging up to date for " + module.name + ", skipping...")
        DefaultTaskResult(this, true, sw)
      } else {
        doPackage(results, sw)
      }
    }
  }

  private def doPackage(results : Iterable[TaskResult], sw : Stopwatch) = {
    val jar = props.Jar().getAbsolutePath

    case class WrappedCommand(cmd : Command, ignoreFailure : Boolean){
      def exec : Int = {
        (cmd.exec(), ignoreFailure) match {
          case  (0, _) => 0
          case (errorNo, true) => {
            log.warn("Ignoring  error in " + cmd + ". Artifact may be missing content")
            0
          }
          case (errorNo, _) => errorNo
        }
      }
    }
    def jarCommand(updateOrCreate : String,targetFile : File, dir : File) = WrappedCommand(
      Command(props, List(jar, updateOrCreate,targetFile.getAbsolutePath, "-C", dir.getAbsolutePath, "."): _*),
      ignoreFailure = false
    )

    def createJarCommand(dir : File) = jarCommand("cf",module.outputArtifact,dir)
    def updateJarCommand(dir : File) = jarCommand("uf",module.outputArtifact,dir)

    if (!module.packageDir.exists)
      module.packageDir.mkdirs

    val cmds : List[WrappedCommand] = createJarCommand(mainDirsToPack.head) :: mainDirsToPack.tail.map(updateJarCommand)

    cmds.find(_.exec != 0) match {
      case Some(failingCommand) => DefaultTaskResult(this, false, sw, message = Some(failingCommand.cmd.savedOutput))
      case None => DefaultTaskResult(this, true, sw)
    }
  }
}