package encry.local.miner

import akka.actor.{Actor, ActorRef, ActorRefFactory, PoisonPill, Props}
import encry.consensus.{PowCandidateBlock, PowConsensus}
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.mempool.{EncryBaseTransaction, TransactionFactory}
import encry.modifiers.state.box.{AssetBox, MonetaryBox}
import encry.settings.{Constants, EncryAppSettings}
import encry.view.history.{EncryHistory, Height}
import encry.view.mempool.EncryMempool
import encry.view.state.UtxoState
import encry.view.wallet.EncryWallet
import io.circe.{Encoder, Json}
import io.circe.syntax._
import io.iohk.iodb.ByteArrayWrapper
import scorex.core.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.SemanticallySuccessfulModifier
import scorex.core.utils.{NetworkTimeProvider, ScorexLogging}

import scala.collection._
import scala.collection.mutable.ArrayBuffer

class EncryMiner(settings: EncryAppSettings,
                 viewHolderRef: ActorRef,
                 readersHolderRef: ActorRef,
                 nodeId: Array[Byte],
                 timeProvider: NetworkTimeProvider) extends Actor with ScorexLogging {

  import EncryMiner._

  private val startTime = timeProvider.time()

  private var isMining = false
  private var candidateOpt: Option[PowCandidateBlock] = None
  private val miningWorkers: mutable.Buffer[ActorRef] = new ArrayBuffer[ActorRef]()

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[SemanticallySuccessfulModifier[_]])
  }

  override def postStop(): Unit = killAllWorkers()

  private def killAllWorkers(): Unit = {
    log.warn("Stopping miner's workers.")
    miningWorkers.foreach(_ ! PoisonPill)
    miningWorkers.clear()
  }

  private def unknownMessage: Receive = {
    case m =>
      log.warn(s"Unexpected message $m")
  }

  private def miningStatus: Receive = {
    case GetMinerStatus =>
      sender ! MinerStatus(isMining, candidateOpt)
  }

  private def startMining: Receive = {
    case StartMining if candidateOpt.nonEmpty && !isMining && settings.nodeSettings.mining =>
      log.info("Starting mining")
      isMining = true
      miningWorkers += EncryMiningWorker(settings, viewHolderRef, candidateOpt.get)(context)
      miningWorkers.foreach(_ ! candidateOpt.get)
    case StartMining if candidateOpt.isEmpty =>
      produceCandidate()
  }

  private def needNewCandidate(b: EncryBlock): Boolean = {
    val parentHeaderIdOpt = candidateOpt.flatMap(_.parentOpt).map(_.id)
    !parentHeaderIdOpt.exists(_.sameElements(b.header.id))
  }

  private def shouldStartMine(b: EncryBlock): Boolean = {
    settings.nodeSettings.mining && b.header.timestamp >= startTime
  }

  private def receiveSemanticallySuccessfulModifier: Receive = {
    /**
      * Case when we are already mining by the time modifier arrives and
      * get block from node view that has header's id which isn't equals to our candidate's parent id.
      * That means that our candidate is outdated. Should produce new candidate for ourselves.
      * Stop all current threads and re-run them with newly produced candidate.
      */
    case SemanticallySuccessfulModifier(mod: EncryBlock) if isMining && needNewCandidate(mod) =>
      produceCandidate()
    /**
      * Non obvious but case when mining is enabled, but miner doesn't started yet. Initialization case.
      * We've received block that been generated by somebody else or genesis while we doesn't start.
      * And this block was generated after our miner had been started. That means that we are ready
      * to start mining.
      * This block could be either genesis or generated by another node.
      */
    case SemanticallySuccessfulModifier(mod: EncryBlock) if shouldStartMine(mod) =>
      self ! StartMining

    case SemanticallySuccessfulModifier(_) => // Ignore other mods.
  }

  private def receiverCandidateBlock: Receive = {
    case c: PowCandidateBlock =>
      procCandidateBlock(c)
    case cEnv: CandidateEnvelope if cEnv.c.nonEmpty =>
      procCandidateBlock(cEnv.c.get)
  }

  override def receive: Receive =
    receiveSemanticallySuccessfulModifier orElse
    receiverCandidateBlock orElse
    miningStatus orElse
    startMining orElse
    unknownMessage

  private def procCandidateBlock(c: PowCandidateBlock): Unit = {
    log.debug(s"Got candidate block $c")
    candidateOpt = Some(c)
    if (!isMining) self ! StartMining
    miningWorkers.foreach(_ ! c)
  }

  private def createCandidate(history: EncryHistory,
                              pool: EncryMempool,
                              state: UtxoState,
                              vault: EncryWallet,
                              bestHeaderOpt: Option[EncryBlockHeader]): PowCandidateBlock = {
    val timestamp = timeProvider.time()
    val height = Height @@ (bestHeaderOpt.map(_.height).getOrElse(Constants.Chain.PreGenesisHeight) + 1)

    // `txsToPut` - valid, non-conflicting txs with respect to their fee amount.
    // `txsToDrop` - invalidated txs to be dropped from mempool.
    val (txsToPut, txsToDrop, _) = pool.takeAll.toSeq.sortBy(_.fee).reverse
      .foldLeft((Seq[EncryBaseTransaction](), Seq[EncryBaseTransaction](), Set[ByteArrayWrapper]())) {
        case ((validTxs, invalidTxs, bxsAcc), tx) =>
          val bxsRaw = tx.unlockers.map(u => ByteArrayWrapper(u.boxId))
          if ((validTxs.map(_.length).sum + tx.length) <= Constants.Chain.BlockMaxSize - 124) {
            if (state.validate(tx).isSuccess && bxsRaw.forall(k => !bxsAcc.contains(k)) && bxsRaw.size == bxsRaw.toSet.size) {
              (validTxs :+ tx, invalidTxs, bxsAcc ++ bxsRaw)
            } else {
              (validTxs, invalidTxs :+ tx, bxsAcc)
            }
          } else {
            (validTxs, invalidTxs, bxsAcc)
          }
      }

    // Remove stateful-invalid txs from mempool.
    pool.removeAsync(txsToDrop)

    val minerSecret = vault.keyManager.mainKey

    val openBxs: IndexedSeq[MonetaryBox] = txsToPut.foldLeft(IndexedSeq[AssetBox]())((buff, tx) =>
      buff ++ tx.newBoxes.foldLeft(IndexedSeq[AssetBox]()) { case (acc, bx) =>
        bx match {
          case ab: AssetBox if ab.isOpen => acc :+ ab
          case _ => acc
        }
      }) ++ vault.getAvailableCoinbaseBoxesAt(state.height)

    val coinbase = TransactionFactory.coinbaseTransactionScratch(minerSecret, timestamp, openBxs, height)

    val txs = txsToPut.sortBy(_.timestamp) :+ coinbase

    val (adProof, adDigest) = state.proofsForTransactions(txs).get
    val difficulty = bestHeaderOpt.map(parent => history.requiredDifficultyAfter(parent))
      .getOrElse(Constants.Chain.InitialDifficulty)
    val derivedFields = PowConsensus.getDerivedHeaderFields(bestHeaderOpt, adProof, txs)
    val blockSignature = minerSecret.sign(
      EncryBlockHeader.getMessageToSign(derivedFields._1, minerSecret.publicImage, derivedFields._2,
        derivedFields._3, adDigest, derivedFields._4, timestamp, derivedFields._5, difficulty))

    val candidate = new PowCandidateBlock(minerSecret.publicImage,
      blockSignature, bestHeaderOpt, adProof, adDigest, txs, timestamp, difficulty)

    log.debug(s"Sending candidate block with ${candidate.transactions.length - 1} transactions " +
      s"and 1 coinbase for height $height")

    candidate
  }

  def produceCandidate(): Unit =
    viewHolderRef ! GetDataFromCurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool, CandidateEnvelope] { v =>
      log.info("Starting candidate generation")
      val history = v.history
      val state = v.state
      val pool = v.pool
      val vault = v.vault
      val bestHeaderOpt = history.bestBlockOpt.map(_.header)

      if (bestHeaderOpt.isDefined || settings.nodeSettings.offlineGeneration) {
        val candidate = createCandidate(history, pool, state, vault, bestHeaderOpt)
        CandidateEnvelope.fromCandidate(candidate)
      } else {
        CandidateEnvelope.empty
      }
    }
}


object EncryMiner extends ScorexLogging {


  case object StartMining

  case object GetMinerStatus

  case class MinerStatus(isMining: Boolean, candidateBlock: Option[PowCandidateBlock]) {
    lazy val json: Json = Map(
      "isMining" -> isMining.asJson,
      "candidateBlock" -> candidateBlock.map(_.asJson).getOrElse("None".asJson)
    ).asJson
  }

  case class CandidateEnvelope(c: Option[PowCandidateBlock])

  object CandidateEnvelope {

    val empty = CandidateEnvelope(None)

    def fromCandidate(c: PowCandidateBlock): CandidateEnvelope = CandidateEnvelope(Some(c))
  }

  implicit val jsonEncoder: Encoder[MinerStatus] = (r: MinerStatus) =>
    Map(
      "isMining" -> r.isMining.asJson,
      "candidateBlock" -> r.candidateBlock.map(_.asJson).getOrElse("None".asJson)
    ).asJson
}

object EncryMinerRef {

  def props(settings: EncryAppSettings,
            viewHolderRef: ActorRef,
            readersHolderRef: ActorRef,
            nodeId: Array[Byte],
            timeProvider: NetworkTimeProvider): Props =
    Props(new EncryMiner(settings, viewHolderRef, readersHolderRef, nodeId, timeProvider))

  def apply(settings: EncryAppSettings,
            viewHolderRef: ActorRef,
            readersHolderRef: ActorRef,
            nodeId: Array[Byte],
            timeProvider: NetworkTimeProvider)
           (implicit context: ActorRefFactory): ActorRef =
    context.actorOf(props(settings, viewHolderRef, readersHolderRef, nodeId, timeProvider))

  def apply(settings: EncryAppSettings,
            viewHolderRef: ActorRef,
            readersHolderRef: ActorRef,
            nodeId: Array[Byte],
            timeProvider: NetworkTimeProvider,
            name: String)
           (implicit context: ActorRefFactory): ActorRef =
    context.actorOf(props(settings, viewHolderRef, readersHolderRef, nodeId, timeProvider), name)
}
