package org.plasmalabs.bridge.consensus.subsystems.monitor

import org.bitcoins.core.currency.CurrencyUnit
import org.plasmalabs.bridge.consensus.shared.BifrostCurrencyUnit

sealed trait BlockchainEvent

case class BTCFundsWithdrawn(txId: String, vout: Long) extends BlockchainEvent

case class NewBTCBlock(height: Int) extends BlockchainEvent

case class SkippedBTCBlock(height: Int) extends BlockchainEvent

case class SkippedStrataBlock(height: Long) extends BlockchainEvent

case class NewStrataBlock(height: Long) extends BlockchainEvent

case class BTCFundsDeposited(
  fundsDepositedHeight: Int,
  scriptPubKey:         String,
  txId:                 String,
  vout:                 Int,
  amount:               CurrencyUnit
) extends BlockchainEvent

case class BifrostFundsDeposited(
  currentStrataBlockHeight: Long,
  address:                  String,
  utxoTxId:                 String,
  utxoIndex:                Int,
  amount:                   BifrostCurrencyUnit
) extends BlockchainEvent

case class BifrostFundsWithdrawn(
  fundsWithdrawnHeight: Long,
  txId:                 String,
  txIndex:              Int,
  secret:               String,
  amount:               BifrostCurrencyUnit
) extends BlockchainEvent
