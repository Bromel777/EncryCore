package encry.network

import java.net.InetSocketAddress
import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout
import encry.EncryApp._
import encry.network.NetworkController.ReceivableMessages.{DataFromPeer, RegisterMessagesHandler, SendToNetwork}
import encry.network.message.{GetPeersSpec, Message, PeersSpec}
import PeerManager._
import PeerManager.ReceivableMessages.{AddOrUpdatePeer, RandomPeers}
import encry.utils.Logging
import scala.concurrent.duration._
import scala.language.postfixOps

class PeerSynchronizer extends Actor with Logging {

  implicit val timeout: Timeout = Timeout(settings.network.syncTimeout.getOrElse(5 seconds))

  override def preStart: Unit = {
    super.preStart()
    networkController ! RegisterMessagesHandler(Seq(GetPeersSpec, PeersSpec), self)
    val msg: Message[Unit] = Message[Unit](GetPeersSpec, Right(Unit), None)
    context.system.scheduler
      .schedule(2.seconds, settings.network.syncInterval)(networkController ! SendToNetwork(msg, SendToRandom))
  }

  override def receive: Receive = {
    case DataFromPeer(spec, peers: Seq[InetSocketAddress]@unchecked, remote)
      if spec.messageCode == PeersSpec.messageCode =>
      peers.filter(checkPossibilityToAddPeer).foreach(isa =>
        peerManager ! AddOrUpdatePeer(isa, None, Some(remote.direction)))
      logDebug(s"Got new peers: [${peers.mkString(",")}] from ${remote.socketAddress}")
    case DataFromPeer(spec, _, remote) if spec.messageCode == GetPeersSpec.messageCode =>
      (peerManager ? RandomPeers(3))
        .mapTo[Seq[InetSocketAddress]]
        .foreach { peers =>
          val correctPeers: Seq[InetSocketAddress] = peers.filter(address => {
            if (remote.socketAddress.getAddress.isSiteLocalAddress) true
            else !address.getAddress.isSiteLocalAddress && address != remote.socketAddress
          })
          logInfo(s"Remote is side local: ${remote.socketAddress} : ${remote.socketAddress.getAddress.isSiteLocalAddress}")
          networkController ! SendToNetwork(Message(PeersSpec, Right(correctPeers), None), SendToPeer(remote))
          logDebug(s"Send to ${remote.socketAddress} peers message which contains next peers: ${peers.mkString(",")}")
        }
    case nonsense: Any => logWarn(s"PeerSynchronizer: got something strange $nonsense")
  }
}