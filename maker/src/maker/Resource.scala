package maker

import maker.utils.FileUtils._
import maker.utils.RichString._
import org.apache.commons.io.{FileUtils => ApacheFileUtils}
import java.io.{File, FileOutputStream, IOException}
import maker.utils.os.Command
import scala.xml.{Elem, NodeSeq}
import maker.project.Module
import maker.utils.{FileUtils, Int}
import org.slf4j.{LoggerFactory, Logger}
import maker.utils.http.HttpUtils
import scala.collection.JavaConversions._

/*  
    Defines a resource held at some maven/nexus repository
      Maven      / Ivy
      groupId    / org      org.scalacheck
      artifactId / name     scalacheck_2.9.2
      version    / rev      1.9

    might be found at the URL

      http://repo1.maven.org/maven2/org/scalacheck/scalacheck_2.9.2/1.9/scalacheck_2.9.2-1.9.jar
      http://nexus.global.trafigura.com:8081/nexus/content/groups/mirror/starling/test/resources/bookmark-test-db/1.2/bookmark-test-db-1.2.gz


    It would be stored locally at 

      ./<project-dir>/lib_managed/org.scalacheck-scalacheck_2.9.2-1.9.jar

    The jar will be cached at

      ~/.maker-resource-cache/org.scalacheck-scalacheck_2.9.2-1.9.jar
*/

case class Resource(
  groupId : String, 
  artifactId : String, 
  version : String, 
  extension : String = "jar",
  classifier : Option[String] = None
) extends ConfigPimps {
  import Resource._
  import HttpUtils.{StatusCode, ErrorMessage}
  override def toString = s"Resource: $groupId $artifactId $version $extension $classifier"
  lazy val log = LoggerFactory.getLogger(this.getClass)
  def relativeURL = "%s/%s/%s/%s-%s%s.%s" %
    (groupId.replace('.', '/'), artifactId, version, artifactId, version, classifier.map("-" + _).getOrElse(""), extension)

  def basename : String = "%s-%s-%s%s.%s" %
    (groupId, artifactId, version, classifier.map("-" + _).getOrElse(""), extension)

  def pomDependencyXML = {
    <dependency>
      <groupId>{groupId}</groupId>
      <artifactId>{artifactId}</artifactId>
      <version>{version}</version>
      <scope>compile</scope>
    </dependency>
  }

  def toIvyInclude : Elem =
    <dependency org={groupId} name={artifactId} rev={version}>
      {classifier.map(c => <artifact name={artifactId} type="jar" ext="jar" e:classifier={c} />).getOrElse(NodeSeq.Empty)}
    </dependency>

  def isJarResource = extension == "jar"
  def isSourceJarResource = isJarResource && classifier == Some("sources")
  def isBinaryJarResource = isJarResource && ! isSourceJarResource
}

object Resource{
  sealed trait UpdateResult{
    def errors : List[(Int, String)] = Nil // (return code, curl command)
  }
  case object ResourceAlreadyExists extends UpdateResult
  case object ResourceDownloaded extends UpdateResult
  case class ResourceFailedToDownload(override val errors : List[(Int, String)]) extends UpdateResult

  def parse(s : String, resourceVersions : Map[String, String] = Map.empty, downloadDirectory : Option[File] = None) : Resource = {
    def exitWithBadUsage{
      val errorMessage = """|Valid resource format is 
                            | <group-id> <artifact-id> <version> [resolver:<resolver-name>] [type:<type-name - e.g. jar, xml, gz>]
                            |
                            | Was given [""".stripMargin + s + "]"
      throw new RuntimeException(errorMessage)
    }

    val terms : List[String] = s.split(" ").toList.filterNot(_ == "")

      if (terms.size < 3 
        || terms.take(3).exists(_.contains(':')) 
        || !terms.drop(3).forall(_.contains(':')))
    {
      exitWithBadUsage
    }

    val List(groupId, artifactId, version) = terms.take(3)

    val optionalArgs = terms.drop(3).map{term => 
      val List(key, value) = term.split(':').toList
        key -> value
    }.toMap

    Resource(
      groupId, artifactId, version, 
      extension = optionalArgs.getOrElse("type", "jar")
    )
  }
}

trait ResourcePimps{
  class OrgAndArtifact(org : String, artifact : String){
    def %(version : String) = Resource(org, artifact, version)
  }
  implicit class Organization(name : String){
    def %(artifact : String) = new OrgAndArtifact(name, artifact)
  }
}
