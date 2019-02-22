import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.StandaloneWSRequest
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


object Main extends App {
  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  val ws = StandaloneAhcWSClient()

  val Month = 2
  val Year = 2019
  val start = LocalDateTime.of(Year, Month, 1, 0, 0)
  val end = LocalDateTime.of(Year, Month, 1, 0, 0).plusMonths(1)

  val GitHubToken = sys.env.getOrElse("GITHUB_TOKEN", sys.error("No GITHUB_TOKEN environment variable found")).trim

  def await[T](future: Future[T]): T = Await.result(future, 10.seconds)

  def pathRequest(path: String) = ws.url(s"https://api.github.com/$path")
    .withHttpHeaders("Authorization" -> s"token $GitHubToken")

  def urlRequest(url: String) = ws.url(url)
    .withHttpHeaders("Authorization" -> s"token $GitHubToken")

  val LinkUrlRegex = " *<(.*)> *".r
  val RelRegex = " *rel=\"(.*)\" *".r

  def extractNextLink(value: String): Option[String] = {
    value.split(",")
      .map(_.split(";").toList)
      .collect {
        case LinkUrlRegex(url) :: params =>
          url -> params.collectFirst {
            case RelRegex("next") => true
          }.getOrElse(false)
      }.collectFirst {
        case (url, true) => url
      }
  }

  def allPages[T: Reads](request: StandaloneWSRequest): List[T] = {
    println(s"Requesting ${request.uri}")
    val result = await(request.get())
    if (result.status >= 400) {
      println(result.body)
      sys.error(s"Error response status: ${result.status}")
    }
    val content = result.body[JsValue].as[List[T]]
    result.header("Link").flatMap(extractNextLink) match {
      case None => content
      case Some(next) => content ::: allPages(urlRequest(next))
    }
  }

  try {

    val contributors = allPages[Repo](pathRequest("orgs/eclipse/repos")
      .withQueryStringParameters("per_page" -> "100")
    )
      .filter(_.name.startsWith("microprofile-"))
      .flatMap { repo =>
        allPages[Commit](pathRequest(s"repos/eclipse/${repo.name}/commits")
          .withQueryStringParameters(
            "since" -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(start),
            "until" -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(end),
            "per_page" -> "100"
          ))
      }.collect {
        case Commit(Some(Author(login))) => login
      }.groupBy(identity)
      .map {
        case (author, list) => author -> list.size
      }.toSeq.sortBy(_._2).reverse

    contributors.foreach {
      case (login, contributions) => println(s"$login: $contributions")
    }

  } finally {
    ws.close()
    system.terminate()
  }

}

case class Repo(name: String)

object Repo {
  implicit val reads: Reads[Repo] = Json.reads
}

case class Commit(author: Option[Author])

object Commit {
  implicit val reads: Reads[Commit] = Json.reads
}

case class Author(login: String)

object Author {
  implicit val reads: Reads[Author] = Json.reads
}
