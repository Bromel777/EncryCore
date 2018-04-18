package encry.view.state

import java.io.File

import akka.actor.ActorRef
import com.google.common.primitives.{Ints, Longs}
import encry.consensus.emission.TokenSupplyController
import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.ADProofs
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.mempool.EncryBaseTransaction
import encry.modifiers.state.StateModifierDeserializer
import encry.modifiers.state.box._
import encry.modifiers.state.box.proposition.HeightProposition
import encry.settings.{Algos, Constants}
import encry.view.history.Height
import io.iohk.iodb.{ByteArrayWrapper, LSMStore, Store}
import scorex.core.NodeViewHolder.ReceivableMessages.LocallyGeneratedModifier
import scorex.core.VersionTag
import scorex.core.transaction.box.Box.Amount
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.avltree.batch._
import scorex.crypto.authds.{ADDigest, ADKey, ADValue, SerializedAdProof}
import scorex.crypto.hash.{Blake2b256Unsafe, Digest32}

import scala.util.{Failure, Success, Try}

class UtxoState(override val version: VersionTag,
                override val height: Height,
                override val stateStore: Store,
                val lastBlockTimestamp: Long,
                nodeViewHolderRef: Option[ActorRef])
  extends EncryState[UtxoState] with UtxoStateReader with TransactionValidator {

  import UtxoState.metadata

  override def maxRollbackDepth: Int = 10

  private def onAdProofGenerated(proof: ADProofs): Unit = {
    if(nodeViewHolderRef.isEmpty) log.warn("Got proof while nodeViewHolderRef is empty")
    nodeViewHolderRef.foreach(_ ! LocallyGeneratedModifier(proof))
  }

  private[state] def applyTransactions(txs: Seq[EncryBaseTransaction],
                                       expectedDigest: ADDigest): Try[Unit] = Try {
    var appliedModCounter: Int = 0

    txs.foreach { tx =>
      // Carries out an exhaustive txs validation and then tries to apply it.
      if (validate(tx).isSuccess) {
        getStateChanges(tx).operations.map(ADProofs.toModification)
          .foldLeft[Try[Option[ADValue]]](Success(None)) { case (t, m) =>
          t.flatMap { _ =>
            appliedModCounter += 1
            persistentProver.performOneOperation(m)
          }
        }
      } else {
        if (appliedModCounter > 0) {
          persistentProver.rollback(rootHash)
            .ensuring(persistentProver.digest.sameElements(rootHash))
        }
        throw new Error(s"Error while applying modifier $tx.")
      }
    }

    log.debug(s"$appliedModCounter modifications applied")

    // Checks whether the outcoming result is the same as expected.
    if (!expectedDigest.sameElements(persistentProver.digest))
      throw new Error(s"Digest after txs application is wrong. ${Algos.encode(expectedDigest)} expected, " +
        s"${Algos.encode(persistentProver.digest)} given")
  }

  // State transition function `APPLY(S,TX) -> S'`.
  override def applyModifier(mod: EncryPersistentModifier): Try[UtxoState] = mod match {

    case block: EncryBlock =>
      log.debug(s"Applying block with header ${block.header.encodedId} to UtxoState with " +
        s"root hash ${Algos.encode(rootHash)} at height $height")

      applyTransactions(block.payload.transactions, block.header.stateRoot).map { _: Unit =>
        val md = metadata(VersionTag @@ block.id, block.header.stateRoot, Height @@ block.header.height, block.header.timestamp)
        val proofBytes = persistentProver.generateProofAndUpdateStorage(md)
        val proofHash = ADProofs.proofDigest(proofBytes)

        if (block.adProofsOpt.isEmpty) onAdProofGenerated(ADProofs(block.header.id, proofBytes))
        log.info(s"Valid modifier ${block.encodedId} with header ${block.header.encodedId} applied to UtxoState with " +
          s"root hash ${Algos.encode(rootHash)}")

        if (!stateStore.get(ByteArrayWrapper(block.id)).exists(_.data sameElements block.header.stateRoot))
          throw new Error("Storage kept roothash is not equal to the declared one.")
        else if (!stateStore.rollbackVersions().exists(_.data sameElements block.header.stateRoot))
          throw new Error("Unable to apply modification properly.")
        else if (!(block.header.adProofsRoot sameElements proofHash))
          throw new Error("Calculated proofHash is not equal to the declared one.")
        else if (!(block.header.stateRoot sameElements persistentProver.digest))
          throw new Error("Calculated stateRoot is not equal to the declared one.")

        new UtxoState(VersionTag @@ block.id, Height @@ block.header.height, stateStore, lastBlockTimestamp, nodeViewHolderRef)
      }.recoverWith[UtxoState] { case e =>
        log.warn(s"Error while applying block with header ${block.header.encodedId} to UTXOState with root" +
          s" ${Algos.encode(rootHash)}: ", e)
        Failure(e)
      }

    case header: EncryBlockHeader =>
      Success(new UtxoState(VersionTag @@ header.id, height, stateStore, lastBlockTimestamp, nodeViewHolderRef))

    case _ => Failure(new Error("Got Modifier of unknown type."))
  }

  def proofsForTransactions(txs: Seq[EncryBaseTransaction]): Try[(SerializedAdProof, ADDigest)] = {
    val rootHash = persistentProver.digest
    def rollback(): Try[Unit] = Try(
      persistentProver.rollback(rootHash).ensuring(_.isSuccess && persistentProver.digest.sameElements(rootHash))
    ).flatten

    Try {
      assert(txs.nonEmpty, "Got empty transaction sequence.")

      if (!(persistentProver.digest.sameElements(rootHash) &&
        storage.version.get.sameElements(rootHash) &&
        stateStore.lastVersionID.get.data.sameElements(rootHash))) Failure(new Error("Bad state version."))

      val mods = getAllStateChanges(txs).operations.map(ADProofs.toModification)

      // TODO: Refactoring.
      mods.foldLeft[Try[Option[ADValue]]](Success(None)) { case (t, m) =>
        t.flatMap(_ => {
          val opRes = persistentProver.performOneOperation(m)
          if (opRes.isFailure) log.warn(s"modification: $m, failure $opRes")
          opRes
        })
      }.get
      val proof = persistentProver.generateProofAndUpdateStorage()
      val digest = persistentProver.digest

      proof -> digest
    } match {
      case Success(result) => rollback().map(_ => result)
      case Failure(e) => rollback().flatMap(_ => Failure(e))
    }
  }

  override def rollbackTo(version: VersionTag): Try[UtxoState] = {
    val prover = persistentProver
    log.info(s"Rollback UtxoState to version ${Algos.encoder.encode(version)}")
    stateStore.get(ByteArrayWrapper(version)) match {
      case Some(v) =>
        val rollbackResult = prover.rollback(ADDigest @@ v.data).map { _ =>
          val stateHeight = stateStore.get(ByteArrayWrapper(UtxoState.bestHeightKey))
            .map(d => Ints.fromByteArray(d.data)).getOrElse(Constants.Chain.GenesisHeight)
          new UtxoState(version, Height @@ stateHeight, stateStore, lastBlockTimestamp, nodeViewHolderRef) {
            override protected lazy val persistentProver: PersistentBatchAVLProver[Digest32, Blake2b256Unsafe] = prover
          }
        }
        stateStore.clean(Constants.DefaultKeepVersions)
        rollbackResult
      case None =>
        Failure(new Error(s"Unable to get root hash at version ${Algos.encoder.encode(version)}"))
    }
  }

  override def rollbackVersions: Iterable[VersionTag] =
    persistentProver.storage.rollbackVersions.map(v =>
      VersionTag @@ stateStore.get(ByteArrayWrapper(Algos.hash(v))).get.data)

  override lazy val rootHash: ADDigest = persistentProver.digest

  /**
    * Carries out an exhaustive validation of the given transaction.
    *
    * Transaction validation algorithm:
    * 0. Check semantic validity of transaction
    *    For each box referenced in transaction:
    * 1. Check if box is in the state
    * 2. Parse box from the state storage
    * 3. Try to unlock the box, providing appropriate context and proof
    *    For all asset types:
    * 4. Make sure inputs.sum >= outputs.sum
   */
  override def validate(tx: EncryBaseTransaction): Try[Unit] =
    tx.semanticValidity.map { _: Unit =>

      val intrinsicId = ADKey @@ Array.fill(4)(-1: Byte)

      def balanceSheet(bxs: Traversable[EncryBaseBox]): Map[ADKey, Amount] =
        bxs.foldLeft(Map.empty[ADKey, Amount]) {
          case (cache, bx: AssetBox) if bx.isIntrinsic =>
            cache.get(intrinsicId).map { amount =>
              cache.updated(intrinsicId, amount + bx.amount)
            }.getOrElse(cache.updated(intrinsicId, bx.amount))
          case (cache, bx: AssetBox) =>
            val tokenId = bx.tokenIdOpt.get
            cache.get(tokenId).map { amount =>
              cache.updated(tokenId, amount + bx.amount)
            }.getOrElse(cache.updated(tokenId, bx.amount))
          case (cache, _) => cache
        }

      implicit val context: Context = Context(tx, height, lastBlockTimestamp, rootHash)

      if (tx.fee < tx.minimalFee && !tx.isCoinbase) throw new Error(s"Low fee in $tx")

      val bxs = tx.unlockers.flatMap(u => persistentProver.unauthenticatedLookup(u.boxId)
        .map(bytes => StateModifierDeserializer.parseBytes(bytes, u.boxId.head))
        .map(t => t.toOption -> u.proofOpt)).foldLeft(IndexedSeq[EncryBaseBox]()) { case (acc, (bxOpt, proofOpt)) =>
          bxOpt match {
            // If `proofOpt` from unlocker is `None` then `tx.signature` is used as a default proof.
            case Some(bx) if bx.proposition.unlockTry(proofOpt.getOrElse(tx.signature)).isSuccess => acc :+ bx
            case _ => throw new Error(s"Failed to spend some boxes referenced in $tx")
          }
        }

      val validBalance = {
        val debitB = balanceSheet(bxs)
        val creditB = balanceSheet(tx.newBoxes)
        creditB.forall { case (id, amount) => debitB.getOrElse(id, 0L) >= amount }
      }

      if (!validBalance) throw new Error(s"Non-positive balance in $tx")
    }
}

object UtxoState extends ScorexLogging {

  private lazy val bestVersionKey = Algos.hash("best_state_version")

  private lazy val bestHeightKey = Algos.hash("state_height")

  private lazy val lastBlockTimeKey = Algos.hash("last_block_timestamp")

  def create(stateDir: File, nodeViewHolderRef: Option[ActorRef]): UtxoState = {
    val stateStore = new LSMStore(stateDir, keepVersions = Constants.DefaultKeepVersions)
    val stateVersion = stateStore.get(ByteArrayWrapper(bestVersionKey))
      .map(_.data).getOrElse(EncryState.genesisStateVersion)
    val stateHeight = stateStore.get(ByteArrayWrapper(bestHeightKey))
      .map(d => Ints.fromByteArray(d.data)).getOrElse(Constants.Chain.PreGenesisHeight)
    val lastBlockTimestamp = stateStore.get(ByteArrayWrapper(lastBlockTimeKey))
      .map(d => Longs.fromByteArray(d.data)).getOrElse(0L)
    new UtxoState(VersionTag @@ stateVersion, Height @@ stateHeight, stateStore, lastBlockTimestamp, nodeViewHolderRef)
  }

  private def metadata(modId: VersionTag, stateRoot: ADDigest,
                       height: Height, blockTimestamp: Long): Seq[(Array[Byte], Array[Byte])] = {
    val idStateDigestIdxElem: (Array[Byte], Array[Byte]) = modId -> stateRoot
    val stateDigestIdIdxElem = Algos.hash(stateRoot) -> modId
    val bestVersion = bestVersionKey -> modId
    val stateHeight = bestHeightKey -> Ints.toByteArray(height)
    val lastBlockTimestamp = lastBlockTimeKey -> Longs.toByteArray(blockTimestamp)

    Seq(idStateDigestIdxElem, stateDigestIdIdxElem, bestVersion, stateHeight, lastBlockTimestamp)
  }

  def fromBoxHolder(bh: BoxHolder, stateDir: File, nodeViewHolderRef: Option[ActorRef]): UtxoState = {
    val p = new BatchAVLProver[Digest32, Blake2b256Unsafe](keyLength = EncryBox.BoxIdSize, valueLengthOpt = None)
    bh.sortedBoxes.foreach(b => p.performOneOperation(Insert(b.id, ADValue @@ b.bytes)).ensuring(_.isSuccess))

    val stateStore = new LSMStore(stateDir, keepVersions = Constants.DefaultKeepVersions)

    log.info(s"Generating UTXO State with ${bh.boxes.size} boxes")

    new UtxoState(EncryState.genesisStateVersion, Constants.Chain.PreGenesisHeight, stateStore, 0L, nodeViewHolderRef) {
      override protected lazy val persistentProver: PersistentBatchAVLProver[Digest32, Blake2b256Unsafe] =
        PersistentBatchAVLProver.create(
          p, storage, metadata(EncryState.genesisStateVersion, p.digest, Constants.Chain.PreGenesisHeight, 0L), paranoidChecks = true
        ).get.ensuring(_.digest sameElements storage.version.get)
    }
  }

  def supplyBoxesAt(height: Height, seed: Long): CoinbaseBox = {
    val supplyAmount: Long = TokenSupplyController.supplyAt(height)
    CoinbaseBox(HeightProposition(Height @@ (height + Constants.Chain.CoinbaseHeightLock)),
      seed * height, supplyAmount)
  }
}
