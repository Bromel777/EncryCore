package encry.modifiers.mempool

import encry.modifiers.mempool.EncryBaseTransaction._
import encry.modifiers.mempool.box.body.PaymentBoxBody
import encry.modifiers.mempool.box.{EncryPaymentBox, EncryPublicKeyNoncedBox}
import encry.settings.Algos
import com.google.common.primitives.{Bytes, Ints, Longs}
import io.circe.Json
import io.circe.syntax._
import scorex.core.serialization.Serializer
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.box.Box.Amount
import scorex.core.transaction.box.BoxUnlocker
import scorex.crypto.authds.ADKey
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Digest32

import scala.util.Try

case class EncryPaymentTransaction(override val fee: Amount,
                                   override val timestamp: Long,
                                   useOutputs: IndexedSeq[ADKey],
                                   createOutputs: IndexedSeq[(PublicKey25519Proposition, Amount)])
  extends EncryBaseTransaction[PublicKey25519Proposition, EncryPaymentBox] {

  // Type of actual Tx type.
  override val typeId: TxTypeId = 1.toByte

  override val unlockers: Traversable[BoxUnlocker[PublicKey25519Proposition]] = _

  override val newBoxes: Traversable[EncryPaymentBox] = createOutputs.zipWithIndex.map { case ((pkp, amount), idx) =>
    val nonce = nonceFromDigest(Algos.hash(hashNoNonces ++ Ints.toByteArray(idx)))
    EncryPaymentBox(pkp, nonce, PaymentBoxBody(amount))
  }

  override def serializer: Serializer[EncryPaymentTransaction] = EncryPaymentTransactionSerializer

  override def json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "inputs" -> useOutputs.map { id =>
      Map(
        "id" -> Algos.encode(id).asJson,
        "signature" -> "".asJson
      ).asJson
    }.asJson,
    "outputs" -> createOutputs.map { case (_, amount) =>
      Map(
        "script" -> "".asJson,
        "amount" -> amount.asJson
      ).asJson
    }.asJson
  ).asJson

  lazy val hashNoNonces: Digest32 = Algos.hash(
    Bytes.concat(scorex.core.utils.concatFixLengthBytes(useOutputs),
      scorex.core.utils.concatFixLengthBytes(createOutputs.map { case (pkp, amount) =>
        pkp.pubKeyBytes ++ Longs.toByteArray(amount)
      })
    )
  )

}

object EncryPaymentTransactionSerializer extends Serializer[EncryPaymentTransaction] {

  override def toBytes(obj: EncryPaymentTransaction): Array[Byte] = ???

  override def parseBytes(bytes: Array[Byte]): Try[EncryPaymentTransaction] = ???
}
