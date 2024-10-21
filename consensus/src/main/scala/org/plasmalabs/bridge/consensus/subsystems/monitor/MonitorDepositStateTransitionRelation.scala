package xyz.stratalab.bridge.consensus.subsystems.monitor

import org.bitcoins.core.protocol.Bech32Address
import xyz.stratalab.bridge.consensus.shared.{BTCConfirmationThreshold, BTCWaitExpirationTime}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{
  EndTransition,
  FSMTransition,
  FSMTransitionTo,
  MConfirmingBTCDeposit,
  MWaitingForBTCDeposit,
  PeginStateMachineState
}

trait MonitorDepositStateTransitionRelation extends TransitionToEffect {

  def handleBlockchainEventDeposit[F[_]](
    currentState:    DepositState,
    blockchainEvent: BlockchainEvent
  )(
    t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
    btcWaitExpirationTime:    BTCWaitExpirationTime,
    btcConfirmationThreshold: BTCConfirmationThreshold
  ): Option[FSMTransition] =
    ((currentState, blockchainEvent) match {
      case (
            cs: MWaitingForBTCDeposit,
            ev: BTCFundsDeposited
          ) =>
        val bech32Address = Bech32Address.fromString(cs.escrowAddress)
        if (ev.scriptPubKey == bech32Address.scriptPubKey.asmHex) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingBTCDeposit(
                cs.currentBTCBlockHeight,
                ev.fundsDepositedHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.escrowAddress,
                cs.redeemAddress,
                cs.claimAddress,
                ev.txId,
                ev.vout,
                ev.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else
          None
      case (
            cs: MWaitingForBTCDeposit,
            ev: NewBTCBlock
          ) =>
        if (btcWaitExpirationTime.underlying < (ev.height - cs.currentBTCBlockHeight))
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: MConfirmingBTCDeposit,
            ev: NewBTCBlock
          ) =>
        // check that the confirmation threshold has been passed
        if (isAboveConfirmationThresholdBTC(ev.height, cs.depositBTCBlockHeight))
          Some(
            FSMTransitionTo(
              currentState,
              MMintingTBTC(
                cs.startBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        else if (ev.height <= cs.depositBTCBlockHeight) {
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          Some(
            FSMTransitionTo(
              currentState,
              MWaitingForBTCDeposit(
                cs.startBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.escrowAddress,
                cs.redeemAddress,
                cs.claimAddress
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            _: MConfirmingBTCDeposit,
            _
          ) =>
        None // No transition
      case (
            _: MWaitingForBTCDeposit,
            _
          ) =>
        None // No transition
    })
}
