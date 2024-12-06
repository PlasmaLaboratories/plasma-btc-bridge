package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.Async
import cats.implicits._
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.{NonStandardScriptSignature, P2WSHWitnessV0, RawScriptPubKey}
import org.bitcoins.core.protocol.transaction.{Transaction, WitnessTransaction}
import org.bitcoins.core.script.constant.{OP_0, ScriptConstant}
import org.bitcoins.crypto.{ECDigitalSignature, _}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.plasmalabs.bridge.consensus.core.PeginWalletManager
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.SignatureServiceClient
import org.plasmalabs.bridge.consensus.core.utils.BitcoinUtils
import org.plasmalabs.bridge.consensus.service.SignatureMessage
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.shared.ReplicaId
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scodec.bits.ByteVector

import scala.concurrent.duration._

trait WaitingForRedemption {

  /**
   * Initiates the claiming process for deposited funds from the escrow address by signing and collecting signatures from other replicas.
   *
   * @param secret The secret hash that needs to be provided additionally to signatures
   * @param claimAddress The Bitcoin address where the claimed funds will be sent to
   * @param currentWalletIdx The current wallet index used for the signatures
   * @param inputTxId The transaction ID of the input being spent
   * @param vout The output index in the input transaction
   * @param scriptAsm The Multisig script
   * @param amountInSatoshis The amount to be claimed in satoshis
   *
   * The process follows these steps:
   * 1. Creates the transaction inputs and signing materials
   * 2. Signs the transaction with the current replica's key
   * 3. Stores the signature
   * 4. If primary replica (id = 0):
   *    - Collects signatures from other replicas
   *    - Broadcasts the fully signed transaction
   * 5. If non-primary replica:
   *    - Simply saves its signature for later collection from primary
   */
  def startClaimingProcess[F[_]: Async: Logger](
    secret:           String,
    claimAddress:     String,
    currentWalletIdx: Int,
    inputTxId:        String,
    vout:             Long,
    scriptAsm:        String,
    amountInSatoshis: CurrencyUnit,
    currentPrimary:   Int
  )(implicit
    bitcoindInstance:   BitcoindRpcClient,
    pegInWalletManager: PeginWalletManager[F],
    feePerByte:         CurrencyUnit,
    replica:            ReplicaId,
    signatureClient:    SignatureServiceClient[F],
    storageApi:         StorageApi[F]
  ): F[Unit]
}

object WaitingForRedemptionOps {

  def startClaimingProcess[F[_]: Async: Logger](
    secret:           String,
    claimAddress:     String,
    currentWalletIdx: Int,
    inputTxId:        String,
    vout:             Long,
    scriptAsm:        String,
    amountInSatoshis: CurrencyUnit,
    currentPrimary:   Int
  )(implicit
    bitcoindInstance:   BitcoindRpcClient,
    pegInWalletManager: PeginWalletManager[F],
    feePerByte:         CurrencyUnit,
    replica:            ReplicaId,
    signatureClient:    SignatureServiceClient[F],
    storageApi:         StorageApi[F]
  ): F[Unit] = {

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

      _ <- storageApi.insertSignature(inputTxId, signature.hex, 1L)
      _ <- replica.id match {
        case `currentPrimary` =>
          for {
            otherSignatures <- primaryCollectSignatures(currentPrimary, inputTxId)
            _               <- primaryBroadcastBitcoinTx(secret, signature, tx, srp, otherSignatures, currentPrimary)
          } yield ()
        case _ => Async[F].unit
      }

    } yield ()
  }

  private def createInputs(
    claimAddress:     String,
    inputTxId:        String,
    vout:             Long,
    scriptAsm:        String,
    amountInSatoshis: CurrencyUnit
  )(implicit
    feePerByte: CurrencyUnit
  ): (DoubleSha256Digest, Transaction, RawScriptPubKey) = {

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

  private def primaryCollectSignatures[F[_]: Async: Logger](primaryId: Int, inputTxId: String)(implicit
    signatureClient: SignatureServiceClient[F]
  ): F[List[SignatureMessage]] = {
    def collectSignatureForReplica(id: Int): F[SignatureMessage] =
      for {
        signature <- signatureClient.getSignature(id, inputTxId)
      } yield signature

    def collectSignaturesRecursive(
      remainingIds:    Set[Int],
      validSignatures: List[SignatureMessage],
      attempt:         Int,
      maxAttempts:     Int = 5 // TODO: add to config
    ): F[List[SignatureMessage]] =
      // Threshold needs to be 4 for 5 out of 7 multisig, 1 signature comes from the primary
      if (validSignatures.length >= 4) {
        for {
          _ <- info"Signature Threshold achieved with ${validSignatures.length} valid signatures"
        } yield validSignatures
      } else {
        for {
          _             <- info"Start to collect signatures for txId: ${inputTxId}"
          newSignatures <- remainingIds.toList.traverse(collectSignatureForReplica)
          newValidSignatures = newSignatures.filter(_.replicaId != -1)

          _ <- Async[F].sleep(1.second)
          result <- collectSignaturesRecursive(
            remainingIds -- newValidSignatures.map(_.replicaId),
            validSignatures ++ newValidSignatures,
            attempt + 1,
            maxAttempts
          )
        } yield result
      }

    val initialIds: Set[Int] = (0 until 7).toSet.filter(_ != primaryId)
    for {
      _          <- Async[F].sleep(1.second) // wait for other replicas to save their signature
      signatures <- collectSignaturesRecursive(initialIds, List.empty, 0)
    } yield signatures
  }

  private def primaryBroadcastBitcoinTx[F[_]: Async](
    secret:           String,
    primarySignature: ECDigitalSignature,
    tx:               Transaction,
    srp:              RawScriptPubKey,
    otherSignatures:  List[SignatureMessage],
    primaryReplicaId: Int
  )(implicit
    bitcoindInstance: BitcoindRpcClient
  ): F[Unit] = {
    val allSignatures = (List((primaryReplicaId, primarySignature)) ++
      otherSignatures.map(signature =>
        (signature.replicaId, ECDigitalSignature.fromBytes(ByteVector(signature.signatureData.toByteArray)))
      )).sortBy(_._1).map(_._2)

    val bridgeSigAsm =
      Seq(ScriptConstant.fromBytes(ByteVector(secret.getBytes().padTo(32, 0.toByte)))) ++ Seq(OP_0) ++
      allSignatures.take(5).map(sig => ScriptConstant(sig.hex)) ++
      Seq(OP_0) // dummy Signature needed

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
      _ <- Async[F].start(
        Async[F].delay(bitcoindInstance.sendRawTransaction(txWit))
      )
    } yield ()
  }
}
