import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import spray.json._

case class Player(nickname: String, characterClass: String, level: Int)

object GameAreaMap {
  case object GetAllPlayer
  case class GetPlayer(nickname: String)
  case class GetPlayerByClass(characterClass: String)
  case class AddPlayer(player: Player)
  case class RemovePlayer(player: Player)
  case object OperationSuccess
}

class GameAreaMap extends Actor with ActorLogging {
  import GameAreaMap._

  var players: Map[String, Player] = Map[String, Player]()

  override def receive: Receive = {
    case GetAllPlayer =>
      log.info("Getting all players")
      sender() ! players.values.toList

    case GetPlayer(nickname: String) =>
      log.info(s"Getting player having nickname :: $nickname")
      sender() ! players.get(nickname)

    case GetPlayerByClass(characterClass: String) =>
      log.info(s"Getting player having characterclass :: $characterClass")
      sender() ! players.values.toList.filter(_.characterClass == characterClass)

    case AddPlayer(player: Player) =>
      log.info(s"Trying to add player :: $player")
      players = players + (player.nickname -> player)
      sender() ! OperationSuccess

    case RemovePlayer(player: Player) =>
      log.info(s"Trying to remove player $player")
      players = players - player.nickname
      sender() ! OperationSuccess

    case OperationSuccess =>
      log.info("Operation success")
  }
}

trait PlayerJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val playerJsonFormat: RootJsonFormat[Player] = jsonFormat3(Player)
}

object MarshallingJSON extends App with PlayerJsonProtocol{

  implicit val system: ActorSystem = ActorSystem("MarshallingJSON")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher
  import GameAreaMap._

  val gameAreaMapActor = system.actorOf(Props[GameAreaMap], "gameAreaMapActor")
  val playersList = List(
    Player("martin", "Warrior", 7),
    Player("Anand", "Elf", 5),
    Player("Azad", "Wizard", 40)
  )

  playersList.foreach { player =>
    gameAreaMapActor ! AddPlayer(player)
  }

  /*
    - GET /api/player, returns all the players in the map, as JSON
    - GET /api/player/(nickname), returns the player with the given nickname (as JSON)
    - GET /api/player?nickname=X, does the same
    - GET /api/player/class/(charClass), returns all the players with the given character class
    - POST /api/player with JSON payload, adds the player to the map
    - DELETE /api/player with JSON payload, removes the player from the map
   */

  implicit val timeout: Timeout = Timeout(2.seconds)

  val gameAreaMapRouteSkel =
    pathPrefix("api" / "player") {
      get {
        path("class" / Segment) { characterClass =>
          val playerByClassFuture = (gameAreaMapActor ? GetPlayerByClass(characterClass)).mapTo[List[Player]]
          complete(playerByClassFuture)
        } ~
        (path(Segment) | parameter("nickname")) { nickname =>
          val playerOptionFuture = (gameAreaMapActor ? GetPlayer(nickname)).mapTo[Option[Player]]
          complete(playerOptionFuture)
        } ~
        pathEndOrSingleSlash {
          val playersFuture = (gameAreaMapActor ? GetAllPlayer).mapTo[List[Player]]
          complete(playersFuture)
        }
      } ~
      post {
        entity(as[Player]) { player =>
          complete((gameAreaMapActor ? AddPlayer(player)).map(_ => StatusCodes.OK))
        }
      } ~
      delete {
        entity(as[Player]) { player =>
          complete((gameAreaMapActor ? RemovePlayer(player)).map(_ => StatusCodes.OK))
        }
      }
    }

  Http().bindAndHandle(gameAreaMapRouteSkel, "localhost", 8080)
}









