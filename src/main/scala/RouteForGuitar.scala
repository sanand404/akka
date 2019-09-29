import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.Future

object RouteForGuitar extends App with GuitarStoreJsonProtocol {

  implicit val system: ActorSystem = ActorSystem("routerForGuitar")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher
  import akka.http.scaladsl.server.Directives._
  import GuitarDB._
  import akka.pattern.ask
  import scala.concurrent.duration._
  import spray.json._

  /**
   * GET /api/guitar fetches ALL the guitars in the store
   * GET /api/guitar?id=x fetches the guitar with id X
   * GET /api/guitar/X fetched guitar with id X
   * GET /api/guitar/inventory?inStock=true
   */

  val guitarDB = system.actorOf(Props[GuitarDB], "guitarDBActor")
  val guitarList = List(
    Guitar("Fender", "Stratocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )

  guitarList.foreach { guitar =>
    guitarDB ! CreateGuitar(guitar)
  }

  implicit val timeout: Timeout = Timeout(2.seconds)

  val guitarServerRoute =
    path("api" / "guitar") {
      parameter(Symbol("id").as[Int]) { id: Int =>
        get {
          println(s"I got id $id")
          val guitarFuture: Future[Option[Guitar]] = (guitarDB ? FindGuitar(id)).mapTo[Option[Guitar]]

          val entityFuture = guitarFuture.map { guitar =>
            HttpEntity(
              ContentTypes.`application/json`,
              guitar.toJson.prettyPrint
            )
          }

          complete(StatusCodes.OK, entityFuture)
        }
      } ~
      get {
        val guitarFuture: Future[List[Guitar]] = (guitarDB ? FindAllGuitar).mapTo[List[Guitar]]
        val entityFuture = guitarFuture.map { guitar =>
          HttpEntity(
            ContentTypes.`application/json`,
            guitar.toJson.prettyPrint
          )
        }

        complete(StatusCodes.OK, entityFuture)
      }
    } ~
    path("api" / "guitar" / IntNumber) { guitarId =>
      get {
        println(s"I got id $guitarId")
        val guitarFuture: Future[Option[Guitar]] = (guitarDB ? FindGuitar(guitarId)).mapTo[Option[Guitar]]

        val entityFuture = guitarFuture.map { guitar =>
          HttpEntity(
            ContentTypes.`application/json`,
            guitar.toJson.prettyPrint
          )
        }

        complete(StatusCodes.OK, entityFuture)
      }
    } ~
    path("api" / "guitar" / "inventory") {
      get {
        parameter(Symbol("inStock").as[Boolean]) { inStock =>
          val guitarFuture: Future[List[Guitar]] = (guitarDB ? FindGuitarInStock(inStock)).mapTo[List[Guitar]]

          val entityFuture = guitarFuture.map { guitar =>
            HttpEntity(
             ContentTypes.`application/json`,
             guitar.toJson.prettyPrint
            )
          }

          complete(StatusCodes.OK, entityFuture)
        }
      }
    }

  def toHttpEntity(payload: String) = HttpEntity(ContentTypes.`application/json`, payload)

  val simplifiedGuitarServerRoute =
    (pathPrefix("api" / "guitar") & get) {
      path("inventory") {
        parameter(Symbol("inStock").as[Boolean]) { inStock =>
          complete(StatusCodes.OK,
            (guitarDB ? FindGuitarInStock(inStock))
            .mapTo[List[Guitar]]
            .map(_.toJson.prettyPrint)
            .map(toHttpEntity)
          )
        }
      } ~
      (path(IntNumber) | parameter(Symbol("id").as[Int])) { guitarId =>
        complete(StatusCodes.OK,
          (guitarDB ? FindGuitar(guitarId))
          .mapTo[Option[Guitar]]
          .map(_.toJson.prettyPrint)
          .map(toHttpEntity)
        )
      } ~
      pathEndOrSingleSlash {
        complete(StatusCodes.OK,
          (guitarDB ? FindAllGuitar)
            .mapTo[List[Guitar]]
            .map(_.toJson.prettyPrint)
            .map(toHttpEntity)
        )
      }
    }

  Http().bindAndHandle(simplifiedGuitarServerRoute, "localhost", 8080)
}



























