package org.plasmalabs.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import org.plasmalabs.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  BTCRetryThreshold,
  BTCWaitExpirationTime,
  PlasmaConfirmationThreshold,
  PlasmaWaitExpirationTime
}
import org.plasmalabs.bridge.consensus.subsystems.monitor.{FSMTransition, PeginStateMachineState}
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import org.typelevel.log4cats.Logger

object MonitorTransitionRelation
    extends TransitionToEffect
    with MonitorDepositStateTransitionRelation
    with MonitorClaimStateTransitionRelation
    with MonitorMintingStateTransitionRelation {

  def handleBlockchainEvent[F[_]: Async: Logger](
    currentState:    PeginStateMachineState,
    blockchainEvent: BlockchainEvent
  )(
    t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
    btcRetryThreshold:         BTCRetryThreshold,
    btcWaitExpirationTime:     BTCWaitExpirationTime,
    toplWaitExpirationTime:    PlasmaWaitExpirationTime,
    btcConfirmationThreshold:  BTCConfirmationThreshold,
    toplConfirmationThreshold: PlasmaConfirmationThreshold,
    groupId:                   GroupId,
    seriesId:                  SeriesId
  ): Option[FSMTransition] =
    ((currentState, blockchainEvent) match {
      case (
            cs: DepositState,
            ev: BlockchainEvent
          ) =>
        handleBlockchainEventDeposit(cs, ev)(t2E)
      case (
            cs: ClaimState,
            ev: BlockchainEvent
          ) =>
        handleBlockchainEventClaim(cs, ev)(t2E)
      case (
            cs: MintingState,
            ev: BlockchainEvent
          ) =>
        handleBlockchainEventMinting(cs, ev)(t2E)
    })
}
