package maker

import com.typesafe.config.{Config, ConfigFactory}
import scalaz.syntax.std.ToBooleanOps
import maker.utils.Int
import org.slf4j.LoggerFactory
import maker.utils.FileUtils._
import scala.collection.JavaConversions._
import java.io.File
import maker.project.{Module, DependencyPimps}
import org.eclipse.aether.util.artifact.JavaScopes

trait ConfigPimps extends DependencyPimps{
  lazy val logger = LoggerFactory.getLogger(getClass)

  implicit class RichConfig(config : Config) extends ToBooleanOps{

    def proxy : Option[(String, Int)] = {
      config.getBoolean("maker.http.proxy.required").option(
        (config.getString("maker.http.proxy.host"), config.getInt("maker.http.proxy.port"))
      )
    }

    def scalaLibraryResolver = config.getString("maker.project.scala.resolver")

    def resourceCache = mkdirs(file(config.getString("maker.resource-cache")))

    def httpResolvers = config.getStringList("maker.http.resolvers").toList.grouped(2)

    def httpHeaders = config.getStringList("maker.http.headers").map{
      case header =>
        val Array(field, value) = header.split(":")
        (field, value)
    }
      
    def makerVersion = config.getString("maker.version")

    def makerTestReporterDependency = "com.github.cage433" % "maker-test-reporter" %% makerVersion withScope(JavaScopes.TEST)

    def unitTestLogbackConfigFile() = {
      val configFile = file(config.getString("maker.logback.unit-tests-config"))
      if (! configFile.exists){
        logger.info("No Logback config found at $configFile - creating an INFO level console appender")
        writeToFile(
          configFile,
          """
            |<!-- This was generated by maker when no logback config was found.
            |     Modify or update maker.logback.unit-tests-config to point to 
            |     another file -->
            |
            |<configuration scan="true" scanPeriod="3 seconds">
            |        
            |  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            |    <encoder>
            |      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level - %msg%n</pattern>
            |      <immediateFlush>true</immediateFlush>
            |    </encoder>
            |    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            |      <level>ERROR</level>
            |    </filter>
            |  </appender>
            |
            |  <root level="info">
            |    <appender-ref ref="CONSOLE" />
            |  </root>
            |</configuration>""".stripMargin
        )
      }
      configFile
    }

    def javaHome = {
      Option(System.getenv("JAVA_HOME")) orElse Option(System.getenv("JDK_HOME")) match {
          case Some(dir) => file(dir)
          case None => 
            throw new IllegalStateException("JAVA_HOME or JDK_HOME must be specified")
      }
    }
    
    def javaExecutable = {
      file(javaHome, "bin", "java")
    }

    def debugFlags = {
      val port = config.getInt("maker.debug.port")
      if (port == 0)
        Nil
      else
        List(s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=$port")
    }

    def gpgPassPhrase = config.getString("maker.gpg-pass-phrase")
    def sonatypeCredentials = config.getString("maker.sonatype-credentials").split(":")

    def execMode = config.getBoolean("maker.exec-mode")

    def unitTestHeapSize : Int = {
      val size = config.getInt("maker.unit-test-heap-size")
      if (size == 0){
        val runtimeMemory = (Runtime.getRuntime.maxMemory / 1024 / 1024).toInt
        (runtimeMemory / 2) min 1024
      } else {
        size
      }
    }

    def taskThreadPoolSize = {
      val size = config.getInt("maker.task-thread-pool-size")
      if (size == 0)
        (Runtime.getRuntime.availableProcessors / 2 max 1) min 4
      else
        size
    }

  }
}
