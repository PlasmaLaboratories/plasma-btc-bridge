package org.plasmalabs.bridge.consensus.subsystems.monitor

import org.bitcoins.core.currency.CurrencyUnit
import org.plasmalabs.bridge.consensus.shared.NodeCurrencyUnit

sealed trait PeginStateMachineState

sealed trait DepositState extends PeginStateMachineState
sealed trait ClaimState extends PeginStateMachineState
sealed trait MintingState extends PeginStateMachineState

case class MWaitingForBTCDeposit(
  currentBTCBlockHeight: Int,
  currentWalletIdx:      Int,
  scriptAsm:             String,
  escrowAddress:         String,
  redeemAddress:         String,
  claimAddress:          String
) extends DepositState

case class MConfirmingBTCDeposit(
  startBTCBlockHeight:   Int,
  depositBTCBlockHeight: Int,
  currentWalletIdx:      Int,
  scriptAsm:             String,
  escrowAddress:         String,
  redeemAddress:         String,
  claimAddress:          String,
  btcTxId:               String,
  btcVout:               Int,
  amount:                CurrencyUnit
) extends DepositState

case class MMintingTBTC(
  startBTCBlockHeight: Int,
  currentWalletIdx:    Int,
  scriptAsm:           String,
  redeemAddress:       String,
  claimAddress:        String,
  btcTxId:             String,
  btcVout:             Int,
  amount:              CurrencyUnit
) extends MintingState

case class MConfirmingTBTCMint(
  startBTCBlockHeight:    Int,
  depositTBTCBlockHeight: Long,
  currentWalletIdx:       Int,
  scriptAsm:              String,
  redeemAddress:          String,
  claimAddress:           String,
  btcTxId:                String,
  btcVout:                Int,
  utxoTxId:               String,
  utxoIndex:              Int,
  amount:                 CurrencyUnit
) extends MintingState

case class MWaitingForRedemption(
  currentTolpBlockHeight: Long,
  currentWalletIdx:       Int,
  scriptAsm:              String,
  redeemAddress:          String,
  claimAddress:           String,
  btcTxId:                String,
  btcVout:                Int,
  utxoTxId:               String,
  utxoIndex:              Int,
  amount:                 NodeCurrencyUnit
) extends MintingState

case class MConfirmingRedemption(
  startBTCBlockHeight:    Option[Int],
  depositBTCBlockHeight:  Option[Long],
  secret:                 String,
  currentTolpBlockHeight: Long,
  currentWalletIdx:       Int,
  scriptAsm:              String,
  redeemAddress:          String,
  claimAddress:           String,
  btcTxId:                String,
  btcVout:                Int,
  utxoTxId:               String,
  utxoIndex:              Int,
  amount:                 NodeCurrencyUnit
) extends MintingState

case class MWaitingForClaim(
  someStartBtcBlockHeight: Option[Int],
  secret:                  String,
  currentWalletIdx:        Int,
  btcTxId:                 String,
  btcVout:                 Int,
  scriptAsm:               String,
  amount:                  NodeCurrencyUnit,
  claimAddress:            String
) extends ClaimState

case class MConfirmingBTCClaim(
  claimBTCBlockHeight: Int,
  secret:              String,
  currentWalletIdx:    Int,
  btcTxId:             String,
  btcVout:             Int,
  scriptAsm:           String,
  amount:              NodeCurrencyUnit,
  claimAddress:        String
) extends ClaimState

sealed trait FSMTransition

case class FSMTransitionTo[F[_]](
  prevState: PeginStateMachineState,
  nextState: PeginStateMachineState,
  effect:    F[Unit]
) extends FSMTransition

case class EndTransition[F[_]](
  effect: F[Unit]
) extends FSMTransition
