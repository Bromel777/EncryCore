package encry.api.routes

import javax.ws.rs.Path

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import io.circe.Json
import io.circe.syntax._
import io.swagger.annotations.{Api, ApiOperation, ApiResponse, ApiResponses}
import encry.view.history.EncryHistory
import encry.view.mempool.EncryMempool
import encry.view.state.EncryState
import encry.view.wallet.EncryWallet
import encry.settings.Algos
import scorex.core.NodeViewHolder.GetDataFromCurrentView
import scorex.core.api.http.SuccessApiResponse
import scorex.core.settings.RESTApiSettings

@Path("/debug")
@Api(value = "/debug", produces = "application/json")
case class DebugApiRoute(nodeViewActorRef: ActorRef, override val settings: RESTApiSettings, nodeId: Array[Byte])
                        (implicit val context: ActorRefFactory) extends EncryBaseApiRoute {
  override val route: Route = pathPrefix("debug") {
    concat(status)
  }

  type S = EncryState[_]


  @Path("/status")
  @ApiOperation(value = "Current state of node", httpMethod = "GET")
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with current state")))
  def status: Route = path("status") {
    get {
      toJsonResponse {
        val request = GetDataFromCurrentView[EncryHistory, S, EncryWallet, EncryMempool, Json] { nvs =>
          val bestHeader = nvs.history.bestHeaderOpt
          val bestFullBlock = nvs.history.bestFullBlockOpt
          Map(
            "nodeId" -> Algos.encode(nodeId).asJson,
            "headers-height" -> bestHeader.map(_.height).getOrElse(-1).asJson,
            "full-height" -> bestFullBlock.map(_.header.height).getOrElse(-1).asJson,
            "best-header-id" -> bestHeader.map(_.encodedId).getOrElse("None").asJson,
            "best-full-header-id" -> bestFullBlock.map(_.header.encodedId).getOrElse("None").asJson,
            "utx-size" -> nvs.pool.size.asJson,
            "state-root" -> Algos.encode(nvs.state.rootHash()).asJson
          ).asJson
        }
        (nodeViewActorRef ? request).mapTo[Json].map { json =>
          SuccessApiResponse(json)
        }
      }
    }
  }

}
