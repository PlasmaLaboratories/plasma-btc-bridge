package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import org.bitcoins.core.currency.CurrencyUnit
import org.plasmalabs.bridge.consensus.shared.{AssetToken, GroupToken, Lvl, NodeCurrencyUnit, SeriesToken}

sealed trait PBFTState {
  def toBytes: Array[Byte]
}

/**
 * State where we are waiting for a BTC deposit to be confirmed
 *
 * @param height
 *   height where we started waiting for the deposit
 * @param currentWalletIdx
 *   index of the current BTC wallet of the bridge
 * @param scriptAsm
 *   the script asm of the escrow address
 * @param escrowAddress
 *   the escrow address (on BTC)
 * @param redeemAddress
 *   the redeem address (on the Strata Network)
 * @param claimAddress
 *   the claim address (on the BTC), this is the address where the BTC will be
 *   sent to after redemption is confirmed
 */
case class PSWaitingForBTCDeposit(
  height:           Int,
  currentWalletIdx: Int,
  scriptAsm:        String,
  escrowAddress:    String,
  redeemAddress:    String,
  claimAddress:     String
) extends PBFTState {

  override def toBytes: Array[Byte] =
    BigInt(height).toByteArray ++
    BigInt(currentWalletIdx).toByteArray ++
    scriptAsm.getBytes ++
    escrowAddress.getBytes ++
    redeemAddress.getBytes ++
    claimAddress.getBytes

}

/**
 * State where we are minting TBTC.
 *
 * @param startWaitingBTCBlockHeight
 *   height where we started waiting for the deposit. We use this for timeout
 *   in case we are not able to never mint.
 * @param currentWalletIdx
 *   index of the current BTC wallet of the bridge
 * @param scriptAsm
 *   the script asm of the escrow address
 * @param redeemAddress
 *   the redeem address (on the Strata Network) where the TBTC will be sent to
 * @param claimAddress
 *   the claim address (on the BTC), this is the address where the BTC will be
 *   sent to after redemption is confirmed
 * @param btcTxId
 *   tx id of the BTC deposit
 * @param btcVout
 *   vout of the BTC deposit
 * @param amount
 *   amount of the BTC deposit
 */
case class PSMintingTBTC(
  startWaitingBTCBlockHeight: Int,
  currentWalletIdx:           Int,
  scriptAsm:                  String,
  redeemAddress:              String,
  claimAddress:               String,
  btcTxId:                    String,
  btcVout:                    Long,
  amount:                     CurrencyUnit
) extends PBFTState {

  override def toBytes: Array[Byte] =
    BigInt(startWaitingBTCBlockHeight).toByteArray ++
    BigInt(currentWalletIdx).toByteArray ++
    scriptAsm.getBytes ++
    redeemAddress.getBytes ++
    claimAddress.getBytes ++
    btcTxId.getBytes ++
    BigInt(btcVout).toByteArray ++
    amount.satoshis.toBigInt.toByteArray

}

/**
 * State where we are claiming BTC.
 *
 * @param someStartBtcBlockHeight
 *   Optional block where we started waiting for the claim transaction. This
 *   value is used to retry the claim transaction in case it fails, after a
 *   certain time.
 * @param secret
 *   The secret that is used to claim the BTC
 * @param currentWalletIdx
 *   The index of the current BTC wallet of the bridge
 * @param btcTxId
 *   The tx id of the BTC deposit, we will use this to claim
 * @param btcVout
 *   The vout of the BTC deposit, we will use this to claim
 * @param scriptAsm
 *   The script asm of the escrow address
 * @param amount
 *   The amount of the BTC deposit
 * @param claimAddress
 *   The address where the BTC will be sent to
 */
case class PSClaimingBTC(
  someStartBtcBlockHeight: Option[Int],
  secret:                  String,
  currentWalletIdx:        Int,
  btcTxId:                 String,
  btcVout:                 Long,
  scriptAsm:               String,
  amount:                  NodeCurrencyUnit,
  claimAddress:            String
) extends PBFTState {

  def toBytes: Array[Byte] =
    someStartBtcBlockHeight.map(BigInt(_).toByteArray).getOrElse(Array.empty) ++
    secret.getBytes ++
    BigInt(currentWalletIdx).toByteArray ++
    btcTxId.getBytes ++
    BigInt(btcVout).toByteArray ++
    scriptAsm.getBytes ++
    (amount match {
      case Lvl(amount) => amount.value.toByteArray
      case SeriesToken(seriesId, amount) =>
        seriesId.getBytes ++ amount.value.toByteArray
      case GroupToken(groupId, amount) =>
        groupId.getBytes ++ amount.value.toByteArray
      case AssetToken(groupId, seriesId, amount) =>
        groupId.getBytes ++ seriesId.getBytes ++ amount.value.toByteArray
    }) ++
    claimAddress.getBytes

}
