package encry.consensus

import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.mempool.EncryBaseTransaction
import encry.settings.Algos
import io.circe.Json
import io.circe.syntax._
import scorex.core.block.Block.Timestamp
import scorex.core.serialization.JsonSerializable
import scorex.crypto.authds.{ADDigest, SerializedAdProof}

class PowCandidateBlock(val parentOpt: Option[EncryBlockHeader],
                        val adProofBytes: SerializedAdProof,
                        val stateRoot: ADDigest,
                        val transactions: Seq[EncryBaseTransaction],
                        val timestamp: Timestamp,
                        val difficulty: Difficulty) extends JsonSerializable {

  override lazy val json: Json = Map(
    // TODO: Add other fields serialization.
    "parentId" -> parentOpt.map(p => Algos.encode(p.id)).getOrElse("None").asJson,
  ).asJson
}