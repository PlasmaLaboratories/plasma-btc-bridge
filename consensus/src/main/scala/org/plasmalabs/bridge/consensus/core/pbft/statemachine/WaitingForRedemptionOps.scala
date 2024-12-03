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
import org.plasmalabs.bridge.consensus.core.pbft.{RequestIdentifier, RequestTimerManager, ViewManager}
import org.typelevel.log4cats.Logger
import org.plasmalabs.bridge.shared.ReplicaId
import org.bitcoins.crypto.{ECDigitalSignature, ECPublicKey, HashType}
import org.bitcoins.core.protocol.transaction.Transaction
import org.typelevel.log4cats.syntax._
    import cats.implicits._

object WaitingForRedemptionOps {

  def createInputs(
    claimAddress:     String,
    inputTxId:        String,
    vout:             Long,
    scriptAsm:        String,
    amountInSatoshis: CurrencyUnit
  )(implicit
    feePerByte: CurrencyUnit
  ) = {

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

    (signableBytes, tx, srp)
  }

  def primaryCollectSignatures[F[_]: Async: Logger] = {}

  def primaryBroadcastBitcoinTx[F[_]: Async: Logger](
    secret:    String,
    signature: ECDigitalSignature,
    tx:        Transaction,
    srp:       RawScriptPubKey
  )(implicit
    bitcoindInstance: BitcoindRpcClient
  ) = {

    val bridgeSigAsm =
      Seq(ScriptConstant.fromBytes(ByteVector(secret.getBytes().padTo(32, 0.toByte)))) ++ Seq(OP_0) ++ Seq(
        ScriptConstant(signature.hex)
      ) ++ Seq(
        OP_0
      )
    val bridgeSig = NonStandardScriptSignature.fromAsm(bridgeSigAsm)
    val txWit = WitnessTransaction
      .toWitnessTx(tx)
      .updateWitness(
        0,
        P2WSHWitnessV0(
          srp,
          bridgeSig
        )
      )
    for {
      _ <- info"I am the primary, I am broadcasting"
      _ <- Async[F].start(
        Async[F].delay(bitcoindInstance.sendRawTransaction(txWit))
      )
    } yield ()
  }

  def startClaimingProcess[F[_]: Async: Logger](
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
    feePerByte:         CurrencyUnit,
    replica:            ReplicaId
  ) = {

    val (signableBytes, tx, srp) = createInputs(
      claimAddress,
      inputTxId,
      vout,
      scriptAsm,
      amountInSatoshis
    )

    for {
      signature <- pegInWalletManager.underlying.signForIdx(
        currentWalletIdx,
        signableBytes.bytes
      )

      _ <- info"Signed Tx: ${inputTxId}"

      _ <-
        if (replica.id == 0) {
          for {
            _ <- info"We are the primary, collecting signatures"
            _ <- primaryBroadcastBitcoinTx(secret, signature, tx, srp)
          } yield ()

        } else {
          for {
            _ <- info"We are not the primary, broadcasting to primary"
          } yield ()
        }

    } yield ()
  }

}
