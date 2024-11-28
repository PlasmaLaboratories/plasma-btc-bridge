package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.Async
import cats.implicits._
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.{NonStandardScriptSignature, P2WSHWitnessV0, RawScriptPubKey}
import org.bitcoins.core.protocol.transaction.WitnessTransaction
import org.bitcoins.core.script.constant.{OP_0, ScriptConstant}
import org.bitcoins.crypto._
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.plasmalabs.bridge.consensus.core.PeginWalletManager
import org.plasmalabs.bridge.consensus.core.utils.BitcoinUtils
import org.plasmalabs.bridge.shared.ReplicaId
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scodec.bits.ByteVector

object WaitingForRedemptionOps {

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
    replica:            ReplicaId // TODO: will be needed for updating the correct index
  ) = {

    val unsignedTx = BitcoinUtils.createRedeemingTx(
      inputTxId,
      vout,
      amountInSatoshis,
      feePerByte,
      claimAddress
    )

    val srp = RawScriptPubKey.fromAsmHex(scriptAsm)

    val serializedTxForSignature = BitcoinUtils.serializeForSignature(
      unsignedTx,
      amountInSatoshis.satoshis,
      srp.asm
    )

    val signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)

    for {
      signature <- pegInWalletManager.underlying.signForIdx(
        currentWalletIdx,
        signableBytes.bytes
      )

      // TODO: Currently blocked by possible issues:
      // Public Key Account derivation path (shouldn't play a role)
      // Unclear where signatures should be inserted (we have a dummy variable and the OP_0 at the end of signatures)
      // Unclear which order of signatures, 0 $signature1 $signature2 2 $address1 $address2 2 OP_CHECKMULTISIG this is the flow usually
      // 0 $signature1 $signature2 serializedScript for the multisig path
      // Unsure if we need to provide 7 signatures (for 0 of 7 multisig provide none are needed)

      // bridgeSigAsm = Seq(ScriptConstant.fromBytes(ByteVector(secret.getBytes().padTo(32, 0.toByte))))++ Seq(OP_0) ++ Seq(OP_0) // for 0 of 7 multisig this works, change buildScriptAsm to check
      bridgeSigAsm =
        Seq(ScriptConstant.fromBytes(ByteVector(secret.getBytes().padTo(32, 0.toByte)))) ++  
        Seq.fill(replica.id)(OP_0) ++ 
        Seq(ScriptConstant(signature.hex)) ++ // make sure the signature is false, how do we do that? 
        Seq.fill(6 - replica.id)(OP_0) ++ 
        Seq(OP_0) ++
        Seq(OP_0) 

      _ <- info"Bridge asm: ${bridgeSigAsm}"

      bridgeSig = NonStandardScriptSignature.fromAsm(bridgeSigAsm)

      txWit: WitnessTransaction = WitnessTransaction
        .toWitnessTx(unsignedTx)
        .updateWitness(
          0,
          P2WSHWitnessV0(
            srp,
            bridgeSig
          )
        )

      _ <- info"TXwit : ${txWit.hex}"

      _ <- Async[F].start(Async[F].delay(bitcoindInstance.sendRawTransaction(txWit)))

    } yield ()
  }

}

// TODO: Use as base once 1 out of 7 works and we have the signature structure
// Preparation for partially signed tx, next step after 1 of 7 is working, we can use this structure to create a 5 of 7 multisig

// val unsignedTx = BitcoinUtils.createRedeemingTx(
//       inputTxId,
//       vout,
//       amountInSatoshis,
//       feePerByte,
//       claimAddress
//     )

//     val psbtFromUnsignedTx = PSBT.fromUnsignedTx(unsignedTx) // utxos should be included here already

//     val redeemScriptPubkey = RawScriptPubKey.fromAsmHex(scriptAsm)

//     val serializedTxForSignature = BitcoinUtils.serializeForSignature(
//       unsignedTx,
//       amountInSatoshis.satoshis,
//       redeemScriptPubkey.asm
//     )

//     val signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)

//     for {
//       signature <- pegInWalletManager.underlying.signForIdx(
//         currentWalletIdx,
//         signableBytes.bytes
//       )

//       bridgeWitnessScriptSeq = Seq(
//         ScriptConstant.fromBytes(ByteVector(secret.getBytes().padTo(32, 0.toByte)))
//       ) ++
//         Seq(OP_0) ++
//         Seq.fill(6 - replica.id)(OP_0) ++
//         Seq(ScriptConstant(signature.hex)) ++
//         Seq.fill(replica.id)(OP_0)

//       witnessScriptAsm = BytesUtil.toByteVector(bridgeWitnessScriptSeq)

//       witnessScriptPubkey = RawScriptPubKey.fromAsmHex(witnessScriptAsm.toHex)

//       // bridgeSig = NonStandardScriptSignature.fromAsm(bridgeWitnessScriptSeq)
//       digitalSignature = ECDigitalSignature(ScriptConstant(signature.hex).bytes)

//       pubkey <- pegInWalletManager.underlying.getCurrentPubKey()
//       pubkeyBytes = pubkey.toPublicKeyBytes()

//       bridgePartialSignature = PartialSignature(
//         pubkeyBytes,
//         digitalSignature
//       )

//       psbtWithInputs = psbtFromUnsignedTx
//         .addRedeemOrWitnessScriptToInput(redeemScriptPubkey, index = 0)
//         .addRedeemOrWitnessScriptToInput(witnessScriptPubkey, index = 0) // TODO: Check Index
//       psbtWithSigHashFlags = psbtWithInputs.addSigHashTypeToInput(HashType.sigHashAll, index = 0) // TODO: Check Index

//       psbtWithSignature = psbtWithSigHashFlags.addSignature(bridgePartialSignature, 0) // TODO: Check Index

//       bytes = psbtWithSignature.bytes

//       _ <- info"Bytes: We successfully created a partially signed transaction: ${bytes}"
//       _ <- info"Signature Object: We successfully created a partially signed transaction: ${psbtWithSignature}"

//       // TODO: Aggregate the txs and send it once combined
