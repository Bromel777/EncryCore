package encry.modifiers.history.block.payload

import encry.crypto.Address
import encry.local.TestHelper
import encry.modifiers.mempool.{EncryBaseTransaction, PaymentTransaction}
import org.scalatest.FunSuite
import scorex.core.ModifierId
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.utils.NetworkTime
import scorex.crypto.encode.{Base16, Base58}

class EncryBlockPayloadSerializerTest extends FunSuite {

  test("testParseBytes & testToBytes") {

    val factory = TestHelper
    val keys = factory.getOrGenerateKeys(factory.Props.keysFilePath).slice(0, 10)

    val txs = keys.map { key =>
      val proposition = key.publicImage
      val fee = factory.Props.txFee
      val timestamp = NetworkTime.time()
      val useBoxes = IndexedSeq(factory.genAssetBox(Address @@ key.publicImage.address)).map(_.id)
      val outputs = IndexedSeq((Address @@ factory.Props.recipientAddr, factory.Props.boxValue))
      val sig = PrivateKey25519Companion.sign(
        key,
        PaymentTransaction.getMessageToSign(proposition, fee, timestamp, useBoxes, outputs)
      )
      PaymentTransaction(proposition, fee, timestamp, sig, useBoxes, outputs)
    }

    val txsSer: Array[Byte] = txs.flatMap(tx => tx.serializer.toBytes(tx)).toArray

    val blockPayload = new EncryBlockPayload(ModifierId @@ Array.fill(32)(19: Byte), txs)

    val blockPayloadSerialized = EncryBlockPayloadSerializer.toBytes(blockPayload)

    val blockPayloadDeserialized = EncryBlockPayloadSerializer.parseBytes(blockPayloadSerialized)

    assert(blockPayloadDeserialized.isSuccess, "Deserialization failed.")

    assert(blockPayload.id sameElements blockPayloadDeserialized.get.id, "Payload id mismatch.")

    assert(txs.size == blockPayloadDeserialized.get.transactions.size, "Transactions quantity mismatch.")
  }


}