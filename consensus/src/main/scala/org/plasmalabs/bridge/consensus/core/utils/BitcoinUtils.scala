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
import org.bitcoins.core.script.constant.{OP_5, OP_7, ScriptConstant, ScriptNumber, ScriptNumberOperation, ScriptToken}
import org.bitcoins.core.script.control.{OP_ELSE, OP_ENDIF, OP_NOTIF}
import org.bitcoins.core.script.crypto.{OP_CHECKMULTISIGVERIFY, OP_CHECKSIG, OP_SHA256}
import org.bitcoins.core.script.locktime.OP_CHECKSEQUENCEVERIFY
import org.bitcoins.core.script.splice.OP_SIZE
import org.bitcoins.core.util.{BitcoinScriptUtil, BytesUtil}
import org.bitcoins.core.wallet.builder.SubtractFeeFromOutputsFinalizer
import org.bitcoins.core.wallet.fee.{FeeUnit, SatoshisPerVirtualByte}
import org.bitcoins.core.wallet.utxo.{ConditionalPath, SegwitV0NativeInputInfo}
import org.bitcoins.crypto.{ECPublicKey, _}
import org.plasmalabs.bridge.consensus.shared.BTCWaitExpirationTime
import scodec.bits.ByteVector

import java.security.MessageDigest

trait BitcoinUtils[F[_]] {

  /**
   * Builds a Bitcoin script ASM sequence for bridge operations using a 5 out of 7 multisig.
   *
   * @param userPKey The user's public key
   * @param bridgePKeys Collection of bridge node IDs and their public keys
   * @param secretHash Hash of the secret used in the script
   * @param relativeLockTime Relative timelock value
   * @return Sequence of script tokens forming the complete Bitcoin script
   */
  def buildScriptAsm(
    userPKey:         ECPublicKey,
    bridgePKeys:      List[(Int, ECPublicKey)],
    secretHash:       ByteVector,
    relativeLockTime: Long
  ): Seq[ScriptToken]

  /**
   * Creates a descriptor string for the Bitcoin script. (Bitcoin Miniscript)
   * For generating bitcoin script: https://bitcoin.sipa.be/miniscript/
   *
   * @param bridgesPkey Collection of bridge node IDs and their public keys
   * @param userPKey User's public key as a hex string
   * @param secretHash Hash of the secret as a hex string
   * @return Descriptor string for the Bitcoin script
   */
  def createDescriptor(
    bridgesPkey: List[(Int, ECPublicKey)],
    userPKey:    String,
    secretHash:  String
  ): String

  /**
   * Creates a serialized representation of a transaction for signing.
   *
   * @param txTo Transaction to be signed
   * @param inputAmount Amount in the output being spent
   * @param inputScript Script tokens for the input
   * @return Serialized bytes for signing
   */
  def serializeForSignature(
    txTo:        Transaction,
    inputAmount: CurrencyUnit,
    inputScript: Seq[ScriptToken]
  ): ByteVector

  /**
   * Creates a redeeming transaction from the escrow address to the claim address.
   *
   * @param inputTxId ID of the input transaction
   * @param inputTxVout Output index in the input transaction
   * @param inputAmount Amount to redeem
   * @param feePerByte Fee rate per byte
   * @param claimAddress Address to send the redeemed funds
   * @return The constructed transaction
   */
  def createRedeemingTx(
    inputTxId:    String,
    inputTxVout:  Long,
    inputAmount:  CurrencyUnit,
    feePerByte:   CurrencyUnit,
    claimAddress: String
  ): Transaction

  /**
   * Calculates the reclaim fee for a transaction.
   *
   * @param tx Transaction to calculate fee for
   * @param feePerByte Fee rate per byte
   * @return Calculated fee as a currency unit
   */
  def calculateBtcReclaimFee(
    tx:         Transaction,
    feePerByte: FeeUnit
  ): CurrencyUnit

  /**
   * Estimates the reclaim fee for a future transaction.
   *
   * @param inputAmount Amount to be reclaimed
   * @param feePerByte Fee rate per byte
   * @param network Network parameters
   * @param btcWaitExpirationTime Expiration time for BTC wait
   * @return Estimated fee as a currency unit
   */
  def estimateBtcReclaimFee(
    inputAmount: CurrencyUnit,
    feePerByte:  FeeUnit,
    network:     NetworkParameters
  )(implicit btcWaitExpirationTime: BTCWaitExpirationTime): CurrencyUnit
}

object BitcoinUtils {

  def buildScriptAsm(
    userPKey:         ECPublicKey,
    bridgePKeys:      List[(Int, ECPublicKey)],
    secretHash:       ByteVector,
    relativeLockTime: Long
  ): Seq[ScriptToken] = {
    val pushOpsUser = BitcoinScriptUtil.calculatePushOp(userPKey.bytes)

    val bridgesPushOps: List[Seq[ScriptToken]] =
      bridgePKeys.toList.map(bridgePKey => BitcoinScriptUtil.calculatePushOp(bridgePKey._2.bytes))

    val pushOpsSecretHash =
      BitcoinScriptUtil.calculatePushOp(secretHash)
    val pushOp32 =
      BitcoinScriptUtil.calculatePushOp(ScriptNumber.apply(32))

    val scriptOp =
      BitcoinScriptUtil.minimalScriptNumberRepresentation(
        ScriptNumber(relativeLockTime)
      )

    val lockTime: Seq[ScriptToken] =
      if (scriptOp.isInstanceOf[ScriptNumberOperation]) {
        Seq(scriptOp)
      } else {
        val pushOpsLockTime =
          BitcoinScriptUtil.calculatePushOp(ScriptNumber(relativeLockTime))
        pushOpsLockTime ++ Seq(
          ScriptConstant(ScriptNumber(relativeLockTime).bytes)
        )
      }

    val bridgesSequence = bridgePKeys.zip(bridgesPushOps).flatMap { case (idKeyPair, pushOps) =>
      pushOps ++ Seq(
        ScriptConstant.fromBytes(idKeyPair._2.bytes)
      )
    }

    pushOpsUser ++ Seq(
      ScriptConstant.fromBytes(userPKey.bytes),
      OP_CHECKSIG,
      OP_NOTIF
    ) ++
    Seq(OP_5) ++
    bridgesSequence ++
    Seq(OP_7) ++
    Seq(
      OP_CHECKMULTISIGVERIFY,
      OP_SIZE
    ) ++
    pushOp32 ++
    Seq(
      ScriptNumber.apply(32),
      OP_EQUALVERIFY,
      OP_SHA256
    ) ++
    pushOpsSecretHash ++
    Seq(
      ScriptConstant.fromBytes(secretHash),
      OP_EQUAL
    ) ++
    Seq(OP_ELSE) ++
    lockTime ++
    Seq(
      OP_CHECKSEQUENCEVERIFY,
      OP_ENDIF
    )
  }

  // or(and(pk(A),older(1000)),and(thresh(5, pk(B1), pk(B2),pk(B3), pk(B4),pk(B5),pk(B6),pk(B7)),sha256(H)))
  def createDescriptor(
    bridgesPkey: List[(Int, ECPublicKey)],
    userPKey:    String,
    secretHash:  String
  ) =
    s"wsh(andor(pk(${userPKey}),older(1000),and_v(v:multi(5,${bridgesPkey.map(_._2.hex).mkString(",")}),sha256(${secretHash}))))"

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
  ): Transaction = {
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
        List((0, ECPublicKey.dummy)),
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
