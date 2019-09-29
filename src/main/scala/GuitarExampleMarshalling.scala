import GuitarDB.{AddQuantity, CreateGuitar, FindAllGuitar, FindGuitar, FindGuitarInStock, GuitarCreated}
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.ActorMaterializer
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.Future

// Step 1
import spray.json._

case class Guitar(make: String, model: String, quantity: Int = 0)

object GuitarDB {
  case class CreateGuitar(guitar: Guitar)
  case class GuitarCreated(id: Int)
  case class FindGuitar(id: Int)
  case object FindAllGuitar
  case class AddQuantity(id: Int, quantity: Int)
  case class FindGuitarInStock(inStock: Boolean)
}

class GuitarDB extends Actor with ActorLogging{
  import GuitarDB._

  var guitars: Map[Int, Guitar] = Map()
  var currentGuitarId: Int = 0

  override def receive: Receive = {
    case FindAllGuitar =>
      log.info("Searching for all guitars")
      sender() ! guitars.values.toList

    case FindGuitar(id) =>
      log.info(s"Searching guitar by id: $id")
      sender() ! guitars.get(id)

    case CreateGuitar(guitar) =>
      log.info(s"Adding guitar $guitar with id $currentGuitarId")
      guitars = guitars + (currentGuitarId -> guitar)
      sender() ! GuitarCreated(currentGuitarId)
      currentGuitarId += 1

    case AddQuantity(id, quantity) =>
      log.info(s"Add quantity item for guitar :: ${id}")
      val guitar: Option[Guitar] = guitars.get(id)
      val newGuitar: Option[Guitar] = guitar.map {
        case Guitar(make, model, q) => Guitar(make, model, q + quantity)
      }
      newGuitar.foreach(guitar => guitars = guitars + (id -> guitar))
      sender() ! newGuitar

    case FindGuitarInStock(inStock) =>
      log.info(s"Searching for all guitars ${if(inStock) "in" else "out of" } stock ")
      if(inStock)
        sender() ! guitars.values.filter(_.quantity > 0)
      else
        sender() ! guitars.values.filter(_.quantity == 0)
  }
}

// Step 2
trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {

  // Step 3
  implicit val guitarFormat: RootJsonFormat[Guitar] = jsonFormat3(Guitar)
}

object GuitarExampleMarshalling extends App with GuitarStoreJsonProtocol {

  implicit val system: ActorSystem = ActorSystem("GuitarActorSystem")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  /**
   * - GET on /api/guitar => All the guitar in the store
   * - GET on /api/guitar?id=X => fetches the guitar associated with id X
   * - POST on /api/guitar => insert the guitar into the store
   */

  // JSON -> marshalling
  val simpleGuitar = Guitar("Fender", "Stratocaster")
  println(simpleGuitar.toJson.prettyPrint)

  //Unmarshalling
  val simpleGuitarJsonString =
    """
      |{
      |  "make": "Fender",
      |  "model": "Stratocaster",
      |  "quantity": 3
      |}
      |""".stripMargin

  println(simpleGuitarJsonString.parseJson.convertTo[Guitar])

  /**
   * setup for actor
   */
  val guitarDB = system.actorOf(Props[GuitarDB], "guitarActor")
  val guitarList = List(
    Guitar("Fender", "Startocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )

  guitarList.foreach { guitar =>
    guitarDB ! CreateGuitar(guitar)
  }

  def getGuitar(query: Query): Future[HttpResponse] = {
    val guitarId = query.get("id").map(_.toInt) // Option[Int]

    guitarId match {

      case None => Future(HttpResponse(StatusCodes.NotFound))

      case Some(id: Int) =>
        val guitarFuture: Future[Option[Guitar]] = (guitarDB ? FindGuitar(id)).mapTo[Option[Guitar]]
        guitarFuture.map {

          case None => HttpResponse(StatusCodes.NotFound)

          case Some(guitar) =>
            HttpResponse(
              entity = HttpEntity(
                ContentTypes.`application/json`,
                guitar.toJson.prettyPrint
              )
            )
        }
    }

  }

  /**
   * Server code
   */
  implicit val defaultTimeout: Timeout = Timeout(10.seconds)
  val requestHandler: HttpRequest => Future[HttpResponse] = {

    case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      val guitarId: Option[Int] = query.get("id").map(_.toInt)
      val guitarQuantity: Option[Int] = query.get("quantity").map(_.toInt)

      val validGuitarFuture: Option[Future[HttpResponse]] = for {
        id <- guitarId
        quantity <- guitarQuantity
      } yield {
        val newGuitarFuture: Future[Option[Guitar]] = (guitarDB ? AddQuantity(id, quantity)).mapTo[Option[Guitar]]
        newGuitarFuture.map(_ => HttpResponse(StatusCodes.OK))
      }

      validGuitarFuture.getOrElse(Future(HttpResponse(StatusCodes.BadRequest)))

    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      val inStockOption = query.get("inStock").map(_.toBoolean)

      inStockOption match {
        case Some(inStock) =>
          val inStockFuture: Future[List[Guitar]] = (guitarDB ? FindGuitarInStock(inStock)).mapTo[List[Guitar]]
          inStockFuture map { guitar =>
            HttpResponse(
              StatusCodes.OK,
              entity = HttpEntity(
                ContentTypes.`application/json`,
                guitar.toJson.prettyPrint
              )
            )

          }

        case None =>
          Future(HttpResponse(StatusCodes.BadRequest))
      }




    case HttpRequest(HttpMethods.GET,uri@Uri.Path("/api/guitar"), _, _, _) =>

      /*
      query parameter handling code
      localhost:8080/api/endpoint?param1=value1&param2=value2
       */
      val query = uri.query() // query object <=> Map[String, String]

      if(query.isEmpty) {
        val guitarFuture = (guitarDB ? FindAllGuitar).mapTo[List[Guitar]]

        guitarFuture.map { guitar =>
          HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitar.toJson.prettyPrint
            )
          )
        }
      } else {
        // fetch guitar associated to the guitar id
        getGuitar(query)
      }

    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity: HttpEntity, _) =>
      // entities are a Source[ByteString]
      val strictEntityFuture = entity.toStrict(3 seconds)
      strictEntityFuture.flatMap {strictEntity =>

        val guitarJsonString = strictEntity.data.utf8String
        val guitar = guitarJsonString.parseJson.convertTo[Guitar]

        val guitarCreatedFuture: Future[GuitarCreated] = (guitarDB ? CreateGuitar(guitar)).mapTo[GuitarCreated]
        guitarCreatedFuture.map { _ =>
          HttpResponse(StatusCodes.OK)
        }
      }

    case request: HttpRequest =>
      request.discardEntityBytes()
      Future{
        HttpResponse(status = StatusCodes.NotFound)
      }
  }

  Http().bindAndHandleAsync(requestHandler, "localhost", 8080)
}
