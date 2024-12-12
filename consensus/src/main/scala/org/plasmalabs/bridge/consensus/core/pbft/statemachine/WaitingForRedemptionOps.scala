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
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.OutOfBandServiceClient
import org.plasmalabs.bridge.consensus.core.utils.BitcoinUtils
import org.plasmalabs.bridge.consensus.service.SignatureMessage
import org.plasmalabs.bridge.consensus.shared.persistence.{OutOfBandAlgebra, StorageApi}
import org.plasmalabs.bridge.shared.ReplicaId
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scodec.bits.ByteVector

import scala.concurrent.duration._

trait WaitingForRedemption {

  def signTx[F[_]: Async](walletId: Int, bytesToSign: ByteVector)(implicit
    walletManager: PeginWalletManager[F]
  ): F[ECDigitalSignature]

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
    currentPrimary:   Int,
    threshold:        Int = 4
  )(implicit
    bitcoindInstance:       BitcoindRpcClient,
    pegInWalletManager:     PeginWalletManager[F],
    feePerByte:             CurrencyUnit,
    replica:                ReplicaId,
    outOfBandServiceClient: OutOfBandServiceClient[F],
    storageApi:             StorageApi[F]
  ): F[Unit]
}

object WaitingForRedemptionOps {

  def signTx[F[_]: Async](walletId: Int, bytesToSign: ByteVector)(implicit
    walletManager: PeginWalletManager[F]
  ): F[ECDigitalSignature] = for {
    signature <- walletManager.underlying.signForIdx(walletId, bytesToSign)
  } yield signature

  def startClaimingProcess[F[_]: Async: Logger](
    secret:           String,
    claimAddress:     String,
    currentWalletIdx: Int,
    inputTxId:        String,
    vout:             Long,
    scriptAsm:        String,
    amountInSatoshis: CurrencyUnit,
    currentPrimary:   Int,
    multiSigM:        Int,
    multiSigN:        Int
  )(implicit
    bitcoindInstance:       BitcoindRpcClient,
    pegInWalletManager:     PeginWalletManager[F],
    feePerByte:             CurrencyUnit,
    replica:                ReplicaId,
    outOfBandServiceClient: OutOfBandServiceClient[F],
    outOfBandAlgebra:       OutOfBandAlgebra[F]
  ): F[Unit] = {

    val (signableBytes, tx, srp) = createInputs(
      claimAddress,
      inputTxId,
      vout,
      scriptAsm,
      amountInSatoshis
    )

    for {
      signature <- signTx(currentWalletIdx, signableBytes.bytes)

      _ <- outOfBandAlgebra.insertClaimSignature(inputTxId, signature.hex, 1L)
      _ <- replica.id match {
        case `currentPrimary` =>
          for {
            otherSignatures <- primaryCollectSignatures(
              currentPrimary,
              inputTxId,
              multiSigM - 1,
              multiSigN,
              currentWalletIdx,
              signableBytes.bytes
            )
            _ <- primaryBroadcastBitcoinTx(secret, signature, tx, srp, otherSignatures, currentPrimary)
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

  private def primaryCollectSignatures[F[_]: Async: Logger](
    primaryId:   Int,
    inputTxId:   String,
    threshold:   Int,
    multiSigN:   Int,
    walletId:    Int,
    bytesToSign: ByteVector
  )(implicit
    outOfBandServiceClient: OutOfBandServiceClient[F]
  ): F[List[SignatureMessage]] = {

    def collectSignaturesRecursive(
      remainingIds:    Set[Int],
      validSignatures: List[SignatureMessage],
      attempt:         Int,
      maxAttempts:     Int = 5 // TODO: add to config
    ): F[List[SignatureMessage]] =
      // Threshold needs to be m - 1 for m out of n multisig, 1 signature comes from the primary
      if (validSignatures.length >= threshold) {
        for {
          _ <- info"Signature Threshold achieved with ${validSignatures.length} valid signatures"
        } yield validSignatures
      } else {
        for {
          _ <- info"Start to collect signatures for txId: ${inputTxId}"
          newSignatures <- remainingIds.toList.traverse { id =>
            outOfBandServiceClient.getSignature(id, walletId, bytesToSign)
          }
          newValidSignatures = newSignatures
            .filter(signatureOption =>
              signatureOption.nonEmpty && signatureOption.get.result.isSignatureMessage && signatureOption.get.result.signatureMessage.get.replicaId != -1
            )
            .map(_.get.result.signatureMessage.get)

          _ <- Async[F].sleep(1.second)
          result <- collectSignaturesRecursive(
            remainingIds -- newValidSignatures.map(_.replicaId),
            validSignatures ++ newValidSignatures,
            attempt + 1,
            maxAttempts
          )
        } yield result
      }

    val initialIds: Set[Int] = (0 until multiSigN).toSet.filter(_ != primaryId)
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
        (signature.replicaId, ECDigitalSignature.fromBytes(ByteVector(signature.signature.toByteArray)))
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
