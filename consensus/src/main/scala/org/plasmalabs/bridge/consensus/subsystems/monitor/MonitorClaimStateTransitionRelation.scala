package org.plasmalabs.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import cats.implicits._
import org.bitcoins.core.protocol.Bech32Address
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import org.plasmalabs.bridge.consensus.shared.{BTCConfirmationThreshold, BTCRetryThreshold}
import org.plasmalabs.bridge.consensus.subsystems.monitor.{
  EndTransition,
  FSMTransition,
  FSMTransitionTo,
  MConfirmingBTCClaim,
  MWaitingForClaim,
  PeginStateMachineState
}

trait MonitorClaimStateTransitionRelation extends TransitionToEffect {

  def handleBlockchainEventClaim[F[_]: Async: Logger](
    currentState:    ClaimState,
    blockchainEvent: BlockchainEvent
  )(
    t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
    btcRetryThreshold:        BTCRetryThreshold,
    btcConfirmationThreshold: BTCConfirmationThreshold
  ): Option[FSMTransition] =
    ((currentState, blockchainEvent) match {
      case (
            cs: MConfirmingBTCClaim,
            ev: NewBTCBlock
          ) =>
        // check that the confirmation threshold has been passed
        if (isAboveConfirmationThresholdBTC(ev.height, cs.claimBTCBlockHeight))
          // we have successfully claimed the BTC
          Some(
            EndTransition[F](
              info"Successfully confirmed claim transaction" >> t2E(
                currentState,
                blockchainEvent
              )
            )
          )
        else if (ev.height <= cs.claimBTCBlockHeight)
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          // we need to go back to waiting for claim
          Some(
            FSMTransitionTo(
              currentState,
              MWaitingForClaim(
                None,
                cs.secret,
                cs.currentWalletIdx,
                cs.btcTxId,
                cs.btcVout,
                cs.scriptAsm,
                cs.amount,
                cs.claimAddress
              ),
              warn"Backtracking we are seeing block ${ev.height}, which is smaller or equal to the block where the BTC was claimed (${cs.claimBTCBlockHeight})" >> t2E(
                currentState,
                blockchainEvent
              )
            )
          )
        else
          None
      case (
            cs: MWaitingForClaim,
            ev: NewBTCBlock
          ) =>
        // if we the someStartBtcBlockHeight is empty, we need to set it
        // if it is not empty, we need to check if the number of blocks since waiting is bigger than the threshold
        cs.someStartBtcBlockHeight match {
          case None =>
            Some(
              FSMTransitionTo(
                currentState,
                MWaitingForClaim(
                  Some(ev.height),
                  cs.secret,
                  cs.currentWalletIdx,
                  cs.btcTxId,
                  cs.btcVout,
                  cs.scriptAsm,
                  cs.amount,
                  cs.claimAddress
                ),
                t2E(currentState, blockchainEvent)
              )
            )
          case Some(startBtcBlockHeight) =>
            if (btcRetryThreshold.underlying < (ev.height - startBtcBlockHeight))
              Some(
                FSMTransitionTo(
                  currentState,
                  MWaitingForClaim(
                    Some(ev.height), // this will reset the counter
                    cs.secret,
                    cs.currentWalletIdx,
                    cs.btcTxId,
                    cs.btcVout,
                    cs.scriptAsm,
                    cs.amount,
                    cs.claimAddress
                  ),
                  t2E(currentState, blockchainEvent)
                )
              )
            else
              None
        }

      case (
            cs: MWaitingForClaim,
            BTCFundsDeposited(depositBTCBlockHeight, scriptPubKey, _, _, _)
          ) =>
        val bech32Address = Bech32Address.fromString(cs.claimAddress)
        if (scriptPubKey == bech32Address.scriptPubKey.asmHex) {
          // the funds were successfully deposited to the claim address
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingBTCClaim(
                depositBTCBlockHeight,
                cs.secret,
                cs.currentWalletIdx,
                cs.btcTxId,
                cs.btcVout,
                cs.scriptAsm,
                cs.amount,
                cs.claimAddress
              ),
              t2E(
                currentState,
                blockchainEvent
              )
            )
          )
        } else None
      case (
            _: MWaitingForClaim,
            _
          ) =>
        None // No transition
      case (
            _: MConfirmingBTCClaim,
            _
          ) =>
        None // No transition
    })
}
