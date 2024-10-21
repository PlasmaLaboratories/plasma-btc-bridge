package org.plasmalabs.bridge.consensus.core.utils

import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.number.{Int32, UInt32}
import org.bitcoins.core.protocol.script.{
  EmptyScriptPubKey,
  NonStandardScriptSignature,
  P2WPKHWitnessSPKV0,
  P2WSHWitnessV0,
  RawScriptPubKey,
  ScriptPubKey,
  ScriptSignature
}
import org.bitcoins.core.protocol.transaction.{
  Transaction,
  TransactionInput,
  TransactionOutPoint,
  TransactionOutput,
  WitnessTransaction
}
import org.bitcoins.core.protocol.{Bech32Address, CompactSizeUInt}
import org.bitcoins.core.script.bitwise.{OP_EQUAL, OP_EQUALVERIFY}
import org.bitcoins.core.script.constant.{ScriptConstant, ScriptNumber, ScriptNumberOperation, ScriptToken}
import org.bitcoins.core.script.control.{OP_ELSE, OP_ENDIF, OP_NOTIF}
import org.bitcoins.core.script.crypto.{OP_CHECKSIG, OP_CHECKSIGVERIFY, OP_SHA256}
import org.bitcoins.core.script.locktime.OP_CHECKSEQUENCEVERIFY
import org.bitcoins.core.script.splice.OP_SIZE
import org.bitcoins.core.util.{BitcoinScriptUtil, BytesUtil}
import org.bitcoins.core.wallet.builder.SubtractFeeFromOutputsFinalizer
import org.bitcoins.core.wallet.fee.{FeeUnit, SatoshisPerVirtualByte}
import org.bitcoins.core.wallet.utxo.{ConditionalPath, SegwitV0NativeInputInfo}
import org.bitcoins.crypto.{ECPublicKey, _}
import scodec.bits.ByteVector
import org.plasmalabs.bridge.consensus.shared.BTCWaitExpirationTime

import java.security.MessageDigest

object BitcoinUtils {

  def buildScriptAsm(
    userPKey:         ECPublicKey,
    bridgePKey:       ECPublicKey,
    secretHash:       ByteVector,
    relativeLockTime: Long
  ): Seq[ScriptToken] = {
    val pushOpsUser = BitcoinScriptUtil.calculatePushOp(userPKey.bytes)
    val pushOpsBridge = BitcoinScriptUtil.calculatePushOp(bridgePKey.bytes)
    val pushOpsSecretHash =
      BitcoinScriptUtil.calculatePushOp(secretHash)
    val pushOp32 =
      BitcoinScriptUtil.calculatePushOp(ScriptNumber.apply(32))

    val scriptOp =
      BitcoinScriptUtil.minimalScriptNumberRepresentation(
        ScriptNumber(relativeLockTime)
      )

    val scriptNum: Seq[ScriptToken] =
      if (scriptOp.isInstanceOf[ScriptNumberOperation]) {
        Seq(scriptOp)
      } else {
        val pushOpsLockTime =
          BitcoinScriptUtil.calculatePushOp(ScriptNumber(relativeLockTime))
        pushOpsLockTime ++ Seq(
          ScriptConstant(ScriptNumber(relativeLockTime).bytes)
        )
      }

    pushOpsUser ++ Seq(
      ScriptConstant.fromBytes(userPKey.bytes),
      OP_CHECKSIG,
      OP_NOTIF
    ) ++ pushOpsBridge ++ Seq(
      ScriptConstant.fromBytes(bridgePKey.bytes),
      OP_CHECKSIGVERIFY,
      OP_SIZE
    ) ++ pushOp32 ++ Seq(
      ScriptNumber.apply(32),
      OP_EQUALVERIFY,
      OP_SHA256
    ) ++ pushOpsSecretHash ++ Seq(
      ScriptConstant.fromBytes(secretHash),
      OP_EQUAL
    ) ++ Seq(OP_ELSE) ++ scriptNum ++ Seq(
      OP_CHECKSEQUENCEVERIFY,
      OP_ENDIF
    )

  }

  // or(and(pk(A),older(1000)),and(pk(B),sha256(H)))
  def createDescriptor(
    bridgePKey: String,
    userPKey:   String,
    secretHash: String
  ) =
    s"wsh(andor(pk($userPKey),older(1000),and_v(v:pk($bridgePKey),sha256($secretHash))))"

  def serializeForSignature(
    txTo:        Transaction,
    inputAmount: CurrencyUnit, // amount in the output of the previous transaction (what we are spending)
    inputScript: Seq[ScriptToken]
  ): ByteVector = {
    val hashPrevouts: ByteVector = {
      val prevOuts = txTo.inputs.map(_.previousOutput)
      val bytes: ByteVector = BytesUtil.toByteVector(prevOuts)
      CryptoUtil.doubleSHA256(bytes).bytes // result is in little endian
    }

    val hashSequence: ByteVector = {
      val sequences = txTo.inputs.map(_.sequence)
      val littleEndianSeq =
        sequences.foldLeft(ByteVector.empty)(_ ++ _.bytes.reverse)
      CryptoUtil
        .doubleSHA256(littleEndianSeq)
        .bytes // result is in little endian
    }

    val hashOutputs: ByteVector = {
      val outputs = txTo.outputs
      val bytes = BytesUtil.toByteVector(outputs)
      CryptoUtil.doubleSHA256(bytes).bytes // result is in little endian
    }

    val scriptBytes = BytesUtil.toByteVector(inputScript)

    val i = txTo.inputs.head
    val serializationForSig: ByteVector =
      txTo.version.bytes.reverse ++ hashPrevouts ++ hashSequence ++
      i.previousOutput.bytes ++ CompactSizeUInt.calc(scriptBytes).bytes ++
      scriptBytes ++ inputAmount.bytes ++ i.sequence.bytes.reverse ++
      hashOutputs ++ txTo.lockTime.bytes.reverse ++ Int32(
        HashType.sigHashAll.num
      ).bytes.reverse
    serializationForSig
  }

  def createRedeemingTx(
    inputTxId:    String,
    inputTxVout:  Long,
    inputAmount:  CurrencyUnit,
    feePerByte:   CurrencyUnit,
    claimAddress: String
  ) = {
    val inputAmountSatoshis = inputAmount.satoshis
    val outpoint = TransactionOutPoint(
      DoubleSha256DigestBE.apply(inputTxId),
      UInt32(inputTxVout)
    )
    val inputs = Vector(
      TransactionInput.apply(outpoint, ScriptSignature.empty, UInt32.zero)
    )
    val bech32Address = Bech32Address.fromString(claimAddress)
    val outputs = Vector(
      TransactionOutput(
        inputAmountSatoshis,
        bech32Address.scriptPubKey
      )
    )
    val builderResult = Transaction.newBuilder
      .++=(inputs)
      .++=(outputs)
      .result()
    val feeRate = SatoshisPerVirtualByte(feePerByte.satoshis)
    val inputInfo = SegwitV0NativeInputInfo.apply(
      outpoint,
      inputAmountSatoshis,
      P2WSHWitnessV0.apply(EmptyScriptPubKey),
      ConditionalPath.NoCondition
    )
    val finalizer = SubtractFeeFromOutputsFinalizer(
      Vector(inputInfo),
      feeRate,
      Vector(ScriptPubKey.apply(bech32Address.scriptPubKey.asm))
    )
    finalizer.buildTx(builderResult)
  }

  /**
   * Calculate the reclaim fee for a given Transaction.
   */
  def calculateBtcReclaimFee(tx: Transaction, feePerByte: FeeUnit): CurrencyUnit = feePerByte.calc(tx)

  /**
   * Estimate the reclaim fee for a future reclaim transaction. This fee must be deposited by the user to the escrow address in addition to the BTC that is meant to be wrapped.
   * This fee will be used when the escrow address is being spent from; for example, during user reclaim (sad path).
   *
   * Virtual bytes include witness data, so we must include the signatures in the transaction prior to calculating
   *
   * This is an estimation since we use dummy values for any unavailable information (txOut reference, keys, etc)
   */
  def estimateBtcReclaimFee(inputAmount: CurrencyUnit, feePerByte: FeeUnit, network: NetworkParameters)(implicit
    btcWaitExpirationTime: BTCWaitExpirationTime
  ): CurrencyUnit = {
    val dummyClaimAddr = Bech32Address
      .apply(
        P2WPKHWitnessSPKV0(ECPublicKey.dummy),
        network
      )
      .value
    val dummyUserPrivKey = ECPrivateKey.freshPrivateKey
    val dummyUnprovenTx = createRedeemingTx(
      DoubleSha256DigestBE.empty.hex,
      0L,
      inputAmount.satoshis,
      feePerByte.currencyUnit,
      dummyClaimAddr
    )
    val dummyScript = RawScriptPubKey(
      buildScriptAsm(
        dummyUserPrivKey.publicKey,
        ECPublicKey.dummy,
        ByteVector(MessageDigest.getInstance("SHA-256").digest("dummy".getBytes)),
        btcWaitExpirationTime.underlying
      )
    )
    val serializedTxForSignature = serializeForSignature(dummyUnprovenTx, inputAmount.satoshis, dummyScript.asm)
    val signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)
    val userSignature = ECDigitalSignature(
      dummyUserPrivKey.sign(signableBytes).bytes ++ ByteVector.fromByte(HashType.sigHashAll.byte)
    )
    val userSig = NonStandardScriptSignature.fromAsm(
      Seq(
        ScriptConstant(userSignature.hex)
      )
    )
    val witTx = WitnessTransaction
      .toWitnessTx(dummyUnprovenTx)
      .updateWitness(
        0,
        P2WSHWitnessV0(
          dummyScript,
          userSig
        )
      )
    calculateBtcReclaimFee(witTx, feePerByte)
  }

}
