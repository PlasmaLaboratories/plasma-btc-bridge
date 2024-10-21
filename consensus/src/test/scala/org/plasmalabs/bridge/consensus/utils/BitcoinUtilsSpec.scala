package xyz.stratalab.bridge.consensus.core.utils

import munit.CatsEffectSuite
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.currency.{Bitcoins, SatoshisLong}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.script.{
  NonStandardScriptSignature,
  P2WPKHWitnessSPKV0,
  P2WSHWitnessV0,
  RawScriptPubKey,
  ScriptSignature
}
import org.bitcoins.core.protocol.transaction.{TransactionOutput, _}
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto.{CryptoUtil, ECDigitalSignature, ECPrivateKey, ECPublicKey, HashType, _}
import scodec.bits.ByteVector
import xyz.stratalab.bridge.consensus.core.utils.BitcoinUtils._
import xyz.stratalab.bridge.consensus.shared.BTCWaitExpirationTime

import java.security.MessageDigest

class BitcoinUtilsSpec extends CatsEffectSuite {

  test("Fee Estimation > Verify specified Fee Rate is used in transaction") {
    implicit val btcWaitExpirationTime: BTCWaitExpirationTime = new BTCWaitExpirationTime(1000)

    val desiredAmt = Bitcoins(10)

    val DesiredFeeRate = SatoshisPerVirtualByte(25.satoshis)

    val fee = estimateBtcReclaimFee(desiredAmt, DesiredFeeRate, RegTest)
    println("calculated fee: ", fee)

    // Create test transaction
    val dummyInputAmt = Bitcoins((desiredAmt.satoshis + fee.satoshis).satoshis)
    val dummyUserPrivKey = ECPrivateKey.freshPrivateKey
    val dummyBridgeKey = ECPublicKey.dummy
    val dummyHash = ByteVector(MessageDigest.getInstance("SHA-256").digest("secret".getBytes))
    val escrowScript =
      buildScriptAsm(dummyUserPrivKey.publicKey, dummyBridgeKey, dummyHash, btcWaitExpirationTime.underlying)
    val sequence: UInt32 = UInt32(1000L & TransactionConstants.sequenceLockTimeMask.toLong)
    val dummyInput =
      TransactionInput(TransactionOutPoint(DoubleSha256DigestBE.empty, 0), ScriptSignature.empty, sequence)
    val dummyOutput = TransactionOutput(desiredAmt, P2WPKHWitnessSPKV0(ECPublicKey.dummy))
    val dummyUnprovenTx = BaseTransaction(
      TransactionConstants.validLockVersion,
      Vector(dummyInput),
      Vector(dummyOutput),
      TransactionConstants.lockTime
    )
    val serializedTxForSignature = serializeForSignature(dummyUnprovenTx, dummyInputAmt.satoshis, escrowScript)
    val signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)
    val userSignature =
      ECDigitalSignature(dummyUserPrivKey.sign(signableBytes).bytes ++ ByteVector.fromByte(HashType.sigHashAll.byte))
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
          RawScriptPubKey(escrowScript),
          userSig
        )
      )
    val ActualFeeRate = SatoshisPerVirtualByte.calc(dummyInputAmt, witTx)
    println(ActualFeeRate)
    assertEquals(ActualFeeRate, DesiredFeeRate)

  }

}
