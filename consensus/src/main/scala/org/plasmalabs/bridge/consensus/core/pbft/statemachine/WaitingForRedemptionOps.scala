package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.Async
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.{NonStandardScriptSignature, P2WSHWitnessV0, RawScriptPubKey}
import org.bitcoins.core.protocol.transaction.WitnessTransaction
import org.bitcoins.core.script.constant.{OP_0, ScriptConstant}
import org.bitcoins.crypto._
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.plasmalabs.bridge.consensus.core.PeginWalletManager
import org.plasmalabs.bridge.consensus.core.utils.BitcoinUtils
import scodec.bits.ByteVector

object WaitingForRedemptionOps {

  def startClaimingProcess[F[_]: Async](
    secret:           String,
    claimAddress:     String,
    currentWalletIdx: Int,
    inputTxId:        String,
    vout:             Long,
    scriptAsm:        String,
    amountInSatoshis: CurrencyUnit
  )(implicit
    bitcoindInstance:   BitcoindRpcClient,
    pegInWalletManager: PeginWalletManager[F],
    feePerByte:         CurrencyUnit
  ) = {

    import cats.implicits._

    val tx = BitcoinUtils.createRedeemingTx(
      inputTxId,
      vout,
      amountInSatoshis,
      feePerByte,
      claimAddress
    )
    val srp = RawScriptPubKey.fromAsmHex(scriptAsm)
    val serializedTxForSignature =
      BitcoinUtils.serializeForSignature(
        tx,
        amountInSatoshis.satoshis,
        srp.asm
      )
    val signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)
    for {
      signature <- pegInWalletManager.underlying.signForIdx(
        currentWalletIdx,
        signableBytes.bytes
      )
      bridgeSig = NonStandardScriptSignature.fromAsm(
        Seq(
          ScriptConstant.fromBytes(
            ByteVector(secret.getBytes().padTo(32, 0.toByte))
          ),
          ScriptConstant(
            signature.hex
          ), // signature of bridge
          OP_0
        )
      )
      txWit = WitnessTransaction
        .toWitnessTx(tx)
        .updateWitness(
          0,
          P2WSHWitnessV0(
            srp,
            bridgeSig
          )
        )
      _ <- Async[F].start(
        Async[F].delay(bitcoindInstance.sendRawTransaction(txWit))
      )
    } yield ()
  }

}
