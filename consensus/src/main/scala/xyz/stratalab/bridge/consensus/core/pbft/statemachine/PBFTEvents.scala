package xyz.stratalab.bridge.consensus.core.pbft.statemachine

import org.bitcoins.core.currency.CurrencyUnit
import xyz.stratalab.bridge.consensus.shared.NodeCurrencyUnit

trait PBFTEvent {
  val sessionId: String
}

/**
 * Event that is emitted when a BTC deposit is posted.
 *
 * @param sessionId
 *   the session id of the deposit.
 * @param height
 *   the height of the BTC network where the deposit took place.
 * @param txId
 *   the tx id of the deposit.
 * @param vout
 *   the vout of the deposit.
 * @param amount
 *   the amount of the deposit.
 */
case class PostDepositBTCEvt(
  sessionId: String,
  height:    Int,
  txId:      String,
  vout:      Int,
  amount:    CurrencyUnit
) extends PBFTEvent

/**
 * Event that is emitted when a TBTC redemption is posted to the chain.
 *
 * @param sessionId
 *   the session id of the redemption.
 * @param height
 *   the height of the TBTC network where the redemption took place.
 * @param utxoTxId
 *   the tx id of the redemption.
 * @param utxoIdx
 *   the index of the redemption.
 * @param amount
 *   the amount of the redemption.
 */
case class PostRedemptionTxEvt(
  sessionId: String,
  secret:    String,
  height:    Long,
  utxoTxId:  String,
  utxoIdx:   Int,
  amount:    NodeCurrencyUnit
) extends PBFTEvent

/**
 * Event that is emitted when a TBTC redemption is confirmed.
 *
 * @param sessionId
 *   the session id of the redemption.
 * @param height
 *   the height of the TBTC network where the redemption got confirmed.
 * @param txId
 *   the tx id of the redemption.
 * @param vout
 *   the vout of the redemption.
 */
case class PostClaimTxEvt(
  sessionId: String,
  height:    Int,
  txId:      String,
  vout:      Int
) extends PBFTEvent

case class TimeoutDeposit(
  sessionId: String
) extends PBFTEvent

case class TimeoutMinting(
  sessionId: String
) extends PBFTEvent
