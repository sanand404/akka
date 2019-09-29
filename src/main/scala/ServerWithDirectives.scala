import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
object ServerWithDirectives extends App{

  implicit val system = ActorSystem("ActorWithDirectives")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  // Directives
  import akka.http.scaladsl.server.Directives._

  val simpleRoute:Route =
    path("home") { // Directive
      complete(StatusCodes.OK) // Directive
    }

  val pathGetRoute: Route =
    path("home") {
      get {
        complete(StatusCodes.OK)
      }
    }

  val chainedRoute: Route =
    path("myEndPoint") {
      get {
        complete(StatusCodes.OK)
      } /**Very Important ----> **/~
      post {
        complete(StatusCodes.NotFound)
      }
    } ~
  path("home") {
    get {
      complete(
        StatusCodes.OK,
        HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            |<body>
            |Hi from home get directive
            |</body>
            |</html>
            |""".stripMargin
        )
      )
    }
  }

  val pathMultiExtractRoute =
    path("api" / "order" / IntNumber / IntNumber) { (id, inventory) =>
      println(s"I've got Two numbers in my path $id, $inventory")
      complete(StatusCodes.OK)

    }

  val queryParameterExtractionRoute =
    //api/item?id=5
    path("api" / "item") {
      parameter('id.as[Int]) { (itemId: Int) =>
        println(s"I've extracted the ID as $itemId")
        complete(StatusCodes.OK)

      }
    }

  val extractRequestRoute =
    path("controlEndPoint") {
      extractRequest { (httpRequest: HttpRequest) =>
        extractLog { (log: LoggingAdapter) =>
          log.info(s"HttpRequest is $httpRequest")
          complete(StatusCodes.OK)
        }
      }
    }

  /**
   * Actionable Directives
   */

  val completeOkRoute = complete(StatusCodes.OK)

  val failRoute =
    path("notSupported") {
      failWith(new RuntimeException("Unsupported!")) // Completes with HTTP 500
    }

  Http().bindAndHandle(extractRequestRoute, "localhost", 8080)
}
