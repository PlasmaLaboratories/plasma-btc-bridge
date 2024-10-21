package xyz.stratalab.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import org.typelevel.log4cats.Logger
import xyz.stratalab.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  BTCRetryThreshold,
  BTCWaitExpirationTime,
  StrataConfirmationThreshold,
  StrataWaitExpirationTime
}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{FSMTransition, PeginStateMachineState}
import xyz.stratalab.sdk.models.{GroupId, SeriesId}

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
    toplWaitExpirationTime:    StrataWaitExpirationTime,
    btcConfirmationThreshold:  BTCConfirmationThreshold,
    toplConfirmationThreshold: StrataConfirmationThreshold,
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
