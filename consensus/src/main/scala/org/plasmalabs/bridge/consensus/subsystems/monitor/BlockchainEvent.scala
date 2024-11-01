package org.plasmalabs.bridge.consensus.subsystems.monitor

import org.bitcoins.core.currency.CurrencyUnit
import org.plasmalabs.bridge.consensus.shared.NodeCurrencyUnit

sealed trait BlockchainEvent

case class BTCFundsWithdrawn(txId: String, vout: Long) extends BlockchainEvent

case class NewBTCBlock(height: Int) extends BlockchainEvent

case class SkippedBTCBlock(height: Int) extends BlockchainEvent

case class SkippedPlasmaBlock(height: Long) extends BlockchainEvent

case class NewPlasmaBlock(height: Long) extends BlockchainEvent

case class BTCFundsDeposited(
  fundsDepositedHeight: Int,
  scriptPubKey:         String,
  txId:                 String,
  vout:                 Int,
  amount:               CurrencyUnit
) extends BlockchainEvent

case class NodeFundsDeposited(
  currentPlasmaBlockHeight: Long,
  address:                  String,
  utxoTxId:                 String,
  utxoIndex:                Int,
  amount:                   NodeCurrencyUnit
) extends BlockchainEvent

case class NodeFundsWithdrawn(
  fundsWithdrawnHeight: Long,
  txId:                 String,
  txIndex:              Int,
  secret:               String,
  amount:               NodeCurrencyUnit
) extends BlockchainEvent
