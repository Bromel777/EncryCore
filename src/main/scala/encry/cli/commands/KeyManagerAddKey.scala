package encry.cli.commands


import encry.view.history.EncryHistory
import encry.view.mempool.EncryMempool
import encry.view.state.UtxoState
import encry.view.wallet.EncryWallet
import scorex.core.NodeViewHolder

import scala.util.Try

object KeyManagerAddKey extends Command {

  override def execute(view: NodeViewHolder.CurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool],
                       args: Array[String]): Try[Unit] = Try {
    view.vault.keyManager.createNewKey()
  }
}
