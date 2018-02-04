package encry.api.http.routes

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import encry.account.Address
import encry.modifiers.state.box.PubKeyInfoBox
import encry.view.EncryViewReadersHolder.{GetReaders, Readers}
import encry.view.state.UtxoStateReader
import io.circe.Json
import io.circe.syntax._
import scorex.core.settings.RESTApiSettings

import scala.concurrent.Future

case class StateInfoApiRoute(readersHolder: ActorRef, nodeViewActorRef: ActorRef,
                             restApiSettings: RESTApiSettings, digest: Boolean)(implicit val context: ActorRefFactory)
  extends EncryBaseApiRoute with FailFastCirceSupport {

  override val route: Route = pathPrefix("state") {
    getKeyInfoByAddressR
  }

  override val settings: RESTApiSettings = restApiSettings

  private def getState: Future[UtxoStateReader] = (readersHolder ? GetReaders).mapTo[Readers].map(_.s.get)

  private def getKeyInfoByAddress(address: Address): Future[Option[Json]] = getState.map {
    _.boxesByAddress(address).map(bxs => bxs.filter(_.isInstanceOf[PubKeyInfoBox]))
  }.map(_.map(_.map(_.json).asJson))

  def getKeyInfoByAddressR: Route = (accountAddress & pathPrefix("keys") & get) { addr =>
    getKeyInfoByAddress(addr).okJson()
  }
}
