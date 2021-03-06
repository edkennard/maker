package maker.project

import java.io.{File, FileOutputStream}
import java.util.concurrent.ConcurrentHashMap
import maker.task._
import maker.task.compile._
import maker.task.tasks._
import maker.utils.FileUtils._
import maker.{PomUtils, ScalaVersion}
import org.slf4j.LoggerFactory
import sbt.ConsoleLogger
import sbt.inc.Analysis
import scala.collection.immutable.Nil
import com.typesafe.config.{ConfigFactory, Config}
import org.eclipse.aether.graph.{Exclusion, Dependency => AetherDependency}
import maker.utils.FileUtils
import org.apache.commons.io.output.TeeOutputStream

/**
  * Corresponds to a module in IntelliJ
  */

class Module(
    val root : File,
    val name : String,
    val config : Config = ConfigFactory.load(),
    val immediateUpstreamModules : Seq[Module] = Nil,
    val testModuleDependencies : Seq[Module] = Nil,
    val analyses : ConcurrentHashMap[File, Analysis] = Module.analyses
)
  extends ProjectTrait
  with DependencyPimps
{

  import Module.logger
  def modules = this :: Nil
  protected val upstreamModulesForBuild = List(this)

  override def tearDown(graph : Dependency.Graph, result : BuildResult) = true
  def dependencies : Seq[RichDependency]  = Nil

  def compilationMetadataDirectory(scalaVersion : ScalaVersion, phase : CompilePhase) = 
    mkdirs(file(makerDirectory, "compilation-metadata", scalaVersion.versionBase, phase.name))

  def compilationCacheFile(scalaVersion : ScalaVersion, phase : CompilePhase) = {
    file(compilationMetadataDirectory(scalaVersion, phase), "compilation-analysis-cache")
  }

  def lastCompilationTime(scalaVersion : ScalaVersion, phase : CompilePhase) : Option[Long] = {
    if (compilationCacheFile(scalaVersion, phase).exists)
      lastModifiedProperFileTime(Vector(compilationCacheFile(scalaVersion, phase)))
    else
      None
  }

  def lastSourceModifcationTime(phase : CompilePhase) : Option[Long] = lastModifiedProperFileTime(sourceFiles(phase))

  def sourceFilesDeletedSinceLastCompilation(scalaVersion : ScalaVersion, phase : CompilePhase) : Seq[File] = {
    Option(analyses.get(classDirectory(scalaVersion, phase))) match {
      case None => Nil
      case Some(analysis) => 
        analysis.infos.allInfos.keySet.toVector.filterNot(_.exists)
    }
  }

  def moduleCompilationErrorsFile(scalaVersion : ScalaVersion, phase : CompilePhase) = {
    file(compilationMetadataDirectory(scalaVersion, phase), "vim-compile-errors")
  }

  def compilationOutputStream(scalaVersion : ScalaVersion, phase : CompilePhase) = {
    new TeeOutputStream(
      Console.err,
      new FileOutputStream(moduleCompilationErrorsFile(scalaVersion, phase))
    )
  }
  def compilationFailedMarker(scalaVersion : ScalaVersion, phase : CompilePhase) = 
    file(compilationMetadataDirectory(scalaVersion, phase), "compilation-failed-marker")

  def lastCompilationFailed(scalaVersion : ScalaVersion, phase : CompilePhase) = 
    compilationFailedMarker(scalaVersion, phase).exists

  def markCompilatonFailure(scalaVersion : ScalaVersion, phase : CompilePhase) = compilationFailedMarker(scalaVersion, phase).touch

  Module.warnOfUnnecessaryDependencies(this)

  def javacOptions : List[String] = Nil 
  def scalacOptions : List[String] = Nil
  /**
   * The standard equals method was slow, making Dependency operations very expensive.
   */
   override def equals(rhs : Any) = {
     rhs match {
       case p : Module if p.root == root => {
         //I believe this assertion should always hold. It's really here so that
         //this overriden equals method never returns true on differing modules.
         assert(this eq p, "Shouldn't have two modules pointing to the same root")
         true
       }
       case _ => false
     }
   }

  override def hashCode = root.hashCode

  private def warnOfRedundantDependencies() {
    immediateUpstreamModules.foreach{
      module =>
        val otherUpstreamModules = immediateUpstreamModules.filterNot(_ == module)
        otherUpstreamModules.find(_.upstreamModules.contains(module)) match {
          case Some(otherUpstreamModule) =>
          logger.warn(name + " shouldn't depend on " + module.name + " as it is inherited via " + otherUpstreamModule.name)
          case None =>
        }
    }
  }

  warnOfRedundantDependencies()


  override def toString = name

  /**************************
  *       TASKS
  **************************/

  /**
    * Execute just this single task, none of its upstream 
    * dependencies. 
    * In general should only be used by devs at the REPL - should
    * not be used as the basis for more complex builds
    */
  private def executeSansDependencies(task : Task) : BuildResult = {
    val build = Build(
      task.name + " for " + name + " only",
      Dependency.Graph(task),
      config.taskThreadPoolSize
    )
    execute(build)
  }

  def cleanOnly = executeSansDependencies(CleanTask(this, defaultScalaVersion))

  def testTaskBuild(scalaVersion : ScalaVersion, lastCompilationTimeFilter : Option[Long]) = {
    // For a module, the `test` task runs just tha module's tests.
    // To run all tests, use the containing project
    transitiveBuild(
      RunUnitTestsTask(
        s"Unit tests for $this", 
        modules = this :: Nil, 
        rootProject = this, 
        classOrSuiteNames_ = None,
        scalaVersion = scalaVersion,
        lastCompilationTimeFilter = lastCompilationTimeFilter,
        testPhase = TestCompilePhase
      ) :: Nil
    )
  }



  def testCompileTaskBuild(scalaVersion : ScalaVersion, testPhases : Seq[CompilePhase]) = transitiveBuild(
    (this +: testModuleDependencies).flatMap{module => 
      testPhases.map(CompileTask(this, module, scalaVersion, _))
    }
  )

  def testFailuredSuitesOnly(scalaVersion : ScalaVersion) : BuildResult = executeSansDependencies(
    RunUnitTestsTask.failingTests(this, this, scalaVersion)
  )
  def testFailuredSuitesOnly : BuildResult = testFailuredSuitesOnly(defaultScalaVersion)


  /********************
  *     Test classses 
  ********************/

  private def classFiles(scalaVersion : ScalaVersion, phase : CompilePhase) : Seq[File] = FileUtils.findClasses(classDirectory(scalaVersion, phase))

  def testClassNames(rootProject : ProjectTrait, scalaVersion : ScalaVersion, lastCompilationTime : Option[Long], testPhase : CompilePhase) : Seq[String] = {
    val isTestSuite = isAccessibleScalaTestSuite(rootProject, scalaVersion, testPhase)
    var classFiles_ = classFiles(scalaVersion, testPhase)
    lastCompilationTime.foreach{
      time => 
        classFiles_ = classFiles_.filter(_.lastModified >= time)
    }
    classFiles_.map(_.className(classDirectory(scalaVersion, testPhase))).filterNot(_.contains("$")).filter(isTestSuite).toList
  }


  /********************
  *     Paths and files
  ********************/

  def makerDirectory = mkdirs(rootAbsoluteFile, ".maker")
  def cacheDirectory = mkdirs(makerDirectory, "cache")


  def sourceDirs(compilePhase : CompilePhase) : List[File] = compilePhase match {
    case SourceCompilePhase => 
      List(file(rootAbsoluteFile, "src/main/scala"), file(rootAbsoluteFile, "src/main/java"))
    case TestCompilePhase => 
      List(file(rootAbsoluteFile, "src/test/scala"), file(rootAbsoluteFile, "src/test/java"))
    case IntegrationTestCompilePhase => 
      List(file(rootAbsoluteFile, "src/it/scala"), file(rootAbsoluteFile, "src/it/java"))
    case EndToEndTestCompilePhase => 
      List(file(rootAbsoluteFile, "src/e2e/scala"), file(rootAbsoluteFile, "src/e2e/java"))
  }

  def scalaFiles(phase : CompilePhase) = findFilesWithExtension("scala", sourceDirs(phase) : _*)
  def javaFiles(phase : CompilePhase) = findFilesWithExtension("java", sourceDirs(phase): _*)

  def sourceFiles(phase : CompilePhase) = scalaFiles(phase) ++ javaFiles(phase)

  def resourceDir(compilePhase : CompilePhase) = compilePhase match {
    case SourceCompilePhase => file(rootAbsoluteFile, "src/main/resources")
    case TestCompilePhase => file(rootAbsoluteFile, "src/test/resources")
    case IntegrationTestCompilePhase => file(rootAbsoluteFile, "src/it/resources")
    case EndToEndTestCompilePhase => file(rootAbsoluteFile, "src/e2e/resources")
  }

  def targetDir = file(rootAbsoluteFile, "target-maker")
  def classDirectory(scalaVersion : ScalaVersion, phase : CompilePhase) : File = {
    phase match {
      case SourceCompilePhase             => file(targetDir, scalaVersion.versionNo, "classes")
      case TestCompilePhase               => file(targetDir, scalaVersion.versionNo, "test-classes")
      case IntegrationTestCompilePhase    => file(targetDir, scalaVersion.versionNo, "integration-test-classes")
      case EndToEndTestCompilePhase       => file(targetDir, scalaVersion.versionNo, "end-to-end-test-classes")
    }
  }

  def warnUnnecessaryResources = true
  def vimModuleCompileOutputFile = file(root, "vim-compile-output")

  def compilerName = "zinc"

}

trait ClassicLayout {
  this: Module =>
  override def sourceDirs(compilePhase : CompilePhase) : List[File] = compilePhase match {
    case SourceCompilePhase           => file(rootAbsoluteFile, "src") :: Nil
    case TestCompilePhase             => file(rootAbsoluteFile, "tests") :: Nil
    case IntegrationTestCompilePhase  => file(rootAbsoluteFile, "it") :: Nil
    case EndToEndTestCompilePhase     => file(rootAbsoluteFile, "e2e") :: Nil
  }
  override def resourceDir(compilePhase : CompilePhase) = compilePhase match {
    case SourceCompilePhase           => file(rootAbsoluteFile, "resources")
    case TestCompilePhase             => file(rootAbsoluteFile, "test-resources")
    case IntegrationTestCompilePhase  => file(rootAbsoluteFile, "integration-test-resources")
    case EndToEndTestCompilePhase     => file(rootAbsoluteFile, "e2e-test-resources")
  }
}


object Module{
 
  lazy val logger = LoggerFactory.getLogger(this.getClass)

  val analyses = new ConcurrentHashMap[File, Analysis]()

  import maker.utils.RichIterable._
  import maker.utils.FileUtils._
  def asClasspathStr(files : Iterable[File], sep : String = java.io.File.pathSeparator) =
    files.distinctBy { (f1, f2) =>
      // distinction rules remove dupe jars (logback really doesn't like dupes)
      (f1.getAbsolutePath == f2.getAbsolutePath) || (
        f1.isFile && f2.isFile &&
          f1.getName == f2.getName &&
          f1.getName.endsWith(".jar") &&
          f1.length() == f2.length()
      )
    }.map(_.getAbsolutePath).sortWith(_ < _).mkString(sep)

  def warnOfUnnecessaryDependencies(proj : Module){

    proj.immediateUpstreamModules.foreach{
      p => 
        proj.immediateUpstreamModules.filterNot(_ == p).find(_.upstreamModules.contains(p)).foreach{
          p1 => 
            logger.warn("Module " + proj.name + " doesn't need to depend on " + p.name + " as it is already inherited from " + p1.name)
        }
    }


    val strictlyUpstreamDependencies = (proj.immediateUpstreamModules ++ proj.testModuleDependencies).distinct.map{
      module => 
        module -> module.upstreamDependencies
    }.toMap

    proj.dependencies.foreach{
      dependency => 
        strictlyUpstreamDependencies.find{
          case (_, upstreamDeps) => upstreamDeps.contains(dependency)
        } match {
          case Some((upstreamModule, _)) => 
            logger.warn("Module " + proj.name + " doesn't need dependency " + dependency + " as it is supplied by " + upstreamModule.name)
          case None => 
        }
    }


  }

}

case class InvalidModuleException(msg : String) extends RuntimeException(msg)
