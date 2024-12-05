package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.Async
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.{NonStandardScriptSignature, P2WSHWitnessV0, RawScriptPubKey}
import org.bitcoins.core.protocol.transaction.{Transaction, WitnessTransaction}
import org.bitcoins.core.script.constant.{OP_0, ScriptConstant}
import org.bitcoins.crypto.{ECDigitalSignature, _}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.plasmalabs.bridge.consensus.core.PeginWalletManager
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.SignatureServiceClient
import org.plasmalabs.bridge.consensus.core.utils.BitcoinUtils
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.shared.ReplicaId
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scodec.bits.ByteVector
import scala.concurrent.duration._
import cats.implicits._
import org.plasmalabs.bridge.consensus.service.SignatureMessage

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

  def primaryCollectSignatures[F[_]: Async: Logger](inputTxId: String)(implicit
    signatureClient: SignatureServiceClient[F]
  ) = for {
    signatures <- (1 until 7).toList.traverse { id =>
      for {
        signature <- signatureClient.getSignature(id, inputTxId)
        _         <- info"Signature received from client ${id}: ${signature}"
      } yield signature
    }
  } yield signatures

  def primaryBroadcastBitcoinTx[F[_]: Async: Logger](
    secret:           String,
    primarySignature: ECDigitalSignature,
    tx:               Transaction,
    srp:              RawScriptPubKey,
    otherSignatures:  List[SignatureMessage]
  )(implicit
    bitcoindInstance: BitcoindRpcClient
  ) = {
    val otherSignaturesAsECDigital =
      otherSignatures.map(signature => ECDigitalSignature.fromBytes(ByteVector(signature.signatureData.toByteArray)))

    val bridgeSigAsm =
      Seq(ScriptConstant.fromBytes(ByteVector(secret.getBytes().padTo(32, 0.toByte)))) ++ Seq(OP_0) ++
      Seq(ScriptConstant(primarySignature.hex)) ++
      Seq(ScriptConstant(otherSignaturesAsECDigital(0).hex)) ++
      Seq(ScriptConstant(otherSignaturesAsECDigital(1).hex)) ++
      Seq(ScriptConstant(otherSignaturesAsECDigital(2).hex)) ++
      Seq(ScriptConstant(otherSignaturesAsECDigital(3).hex)) ++
      Seq(OP_0)

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
    replica:            ReplicaId,
    signatureClient:    SignatureServiceClient[F],
    storageApi:         StorageApi[F]
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

      _ <- info"Saving current signature"
      _ <- storageApi.insertSignature(inputTxId, signature.hex, 1L)

      _ <-
        if (replica.id == 0) {
          for {
            _ <- info"We are the primary, collecting signatures"
            _ <- Async[F].sleep(1.second)

            otherSignatures <- primaryCollectSignatures(inputTxId)
            _               <- primaryBroadcastBitcoinTx(secret, signature, tx, srp, otherSignatures)
          } yield ()

        } else {
          for {
            _ <- info"We are not the primary, we just save our signature"
          } yield ()
        }

    } yield ()
  }

}
