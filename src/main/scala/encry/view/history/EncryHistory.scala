package encry.view.history

import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.block.header.{EncryBlockHeader, EncryHeaderChain}
import encry.modifiers.history.block.payload.EncryBlockPayload
import encry.settings.{Algos, ConsensusSettings}
import encry.view.history.storage.HistoryStorage
import encry.view.history.storage.processors.{BlockHeadersProcessor, BlockPayloadProcessor}
import io.iohk.iodb.Store
import scorex.core.ModifierId
import scorex.core.consensus.{History, ModifierSemanticValidity}
import scorex.core.consensus.History.{HistoryComparisonResult, ModifierIds}
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58

import scala.util.{Failure, Try}

/**
  * History implementation. It is processing persistent modifiers generated locally or coming from network.
  * Depending on chosen node settings, it will process modifiers in a different way, different processors define how to
  * process different type of modifiers.
  *
  * HeadersProcessor: processor of block headers. It's the same for all node settings
  * BlockTransactionsProcessor: Processor of BlockTransactions. BlockTransactions may
  *   1. Be downloaded from other peers (verifyTransactions == true)
  *   2. Be ignored by history (verifyTransactions == false)
  */
trait EncryHistory extends History[EncryPersistentModifier, EncrySyncInfo, EncryHistory]
  with BlockHeadersProcessor
  with BlockPayloadProcessor
  with ScorexLogging {

  protected val consensusSettings: ConsensusSettings

  protected val storage: Store

  override protected lazy val historyStorage: HistoryStorage = new HistoryStorage(storage)

  def bestHeaderOpt: Option[EncryBlockHeader] = bestHeaderIdOpt.flatMap(typedModifierById[EncryBlockHeader])

  // Compares node`s `SyncInfo` with another`s.
  override def compare(other: EncrySyncInfo): History.HistoryComparisonResult.Value = {
    bestHeaderIdOpt match {
      case Some(id) if other.lastHeaderIds.lastOption.exists(_ sameElements id) =>
        HistoryComparisonResult.Equal
      case Some(id) if other.lastHeaderIds.exists(_ sameElements id) =>
        HistoryComparisonResult.Older
      case Some(_) if other.lastHeaderIds.isEmpty =>
        HistoryComparisonResult.Younger
      case Some(_) =>
        // Compare headers chain
        val ids = other.lastHeaderIds
        ids.view.reverse.find(m => contains(m)) match {
          case Some(_) =>
            HistoryComparisonResult.Younger
          case None => HistoryComparisonResult.Nonsense
        }
      case None =>
        log.warn("Trying to compare with other node while our history is empty")
        HistoryComparisonResult.Older
    }
  }

  /**
    * @param info other's node sync info
    * @param size max return size
    * @return Ids of headerss, that node with info should download and apply to synchronize
    */
  override def continuationIds(info: EncrySyncInfo, size: Int): Option[ModifierIds] = Try {
    if (isEmpty) {
      info.startingPoints
    } else if (info.lastHeaderIds.isEmpty) {
      val heightFrom = Math.min(headersHeight, size - 1)
      val startId = headerIdsAtHeight(heightFrom).head
      val startHeader = typedModifierById[EncryBlockHeader](startId).get
      val headers = headerChainBack(size, startHeader, _ => false)
      assert(headers.headers.exists(_.height == 0), "Should always contain genesis header")
      headers.headers.flatMap(h => Seq((EncryBlockHeader.modifierTypeId, h.id)))
    } else {
      val ids = info.lastHeaderIds
      val lastHeaderInOurBestChain: ModifierId = ids.view.reverse.find(m => isInBestChain(m)).get
      val theirHeight = heightOf(lastHeaderInOurBestChain).get
      val heightFrom = Math.min(headersHeight, theirHeight + size)
      val startId = headerIdsAtHeight(heightFrom).head
      val startHeader = typedModifierById[EncryBlockHeader](startId).get
      val headerIds = headerChainBack(size, startHeader, h => h.parentId sameElements lastHeaderInOurBestChain)
        .headers.map(h => EncryBlockHeader.modifierTypeId -> h.id)
      headerIds
    }
  }.toOption

  /**
    * @return all possible forks, that contains specified header
    */
  private[history] def continuationHeaderChains(header: EncryBlockHeader): Seq[EncryHeaderChain] = {
    def loop(acc: Seq[EncryBlockHeader]): Seq[EncryHeaderChain] = {
      val bestHeader = acc.last
      val currentHeight = heightOf(bestHeader.id).get
      val nextLevelHeaders = headerIdsAtHeight(currentHeight + 1).map(id => typedModifierById[EncryBlockHeader](id).get)
        .filter(_.parentId sameElements bestHeader.id)
      if (nextLevelHeaders.isEmpty) Seq(EncryHeaderChain(acc))
      else nextLevelHeaders.map(h => acc :+ h).flatMap(chain => loop(chain))
    }

    loop(Seq(header))
  }

  override def append(modifier: EncryPersistentModifier): Try[(EncryHistory, History.ProgressInfo[EncryPersistentModifier])] = {
    log.debug(s"Trying to append modifier ${Base58.encode(modifier.id)} of type ${modifier.modifierTypeId} to history...")
    testApplicable(modifier).map { _ =>
      modifier match {
        case header: EncryBlockHeader => (this, process(header))
        case payload: EncryBlockPayload => (this, process(payload))
      }
    }
  }

  private def testApplicable(modifier: EncryPersistentModifier): Try[Unit] = {
    modifier match {
      case header: EncryBlockHeader => validate(header)
      case payload: EncryBlockPayload => validate(payload)
      case mod: Any => Failure(new Error(s"Modifier $mod is of incorrect type."))
    }
  }

  // Checks whether the `modifier` is applicable to the `history`.
  override def applicable(modifier: EncryPersistentModifier): Boolean = testApplicable(modifier).isSuccess

  def lastHeaders(count: Int): EncryHeaderChain = bestHeaderOpt
    .map(bestHeader => headerChainBack(count, bestHeader, _ => false)).getOrElse(EncryHeaderChain.empty)

  // Gets EncryPersistentModifier by it's id if it is in history.
  override def modifierById(id: ModifierId): Option[EncryPersistentModifier] = {
    val modifier = historyStorage.modifierById(id)
    assert(modifier.forall(_.id sameElements id), s"Modifier $modifier id is incorrect, ${Algos.encode(id)} expected")
    modifier
  }

  // Gets EncryPersistentModifier of type T by it's id if it is in history.
  def typedModifierById[T <: EncryPersistentModifier](id: ModifierId): Option[T] = modifierById(id) match {
    case Some(m: T@unchecked) if m.isInstanceOf[T] => Some(m)
    case _ => None
  }

  // TODO:
  def syncInfo(answer: Boolean): EncrySyncInfo = if (isEmpty) {
    EncrySyncInfo(answer, Seq())
  } else {
    EncrySyncInfo(answer, lastHeaders(EncrySyncInfo.MaxBlockIds).headers.map(_.id))
  }

  override def isSemanticallyValid(modifierId: ModifierId): ModifierSemanticValidity.Value = {
    historyStorage.db.get(validityKey(modifierId)) match {
      case Some(b) if b.data.headOption.contains(1.toByte) => ModifierSemanticValidity.Valid
      case Some(b) if b.data.headOption.contains(0.toByte) => ModifierSemanticValidity.Invalid
      case None if contains(modifierId) => ModifierSemanticValidity.Unknown
      case None => ModifierSemanticValidity.Absent
      case m =>
        log.error(s"Incorrect validity status: $m")
        ModifierSemanticValidity.Absent
    }
  }
}

object EncryHistory