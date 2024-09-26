package xyz.stratalab.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import xyz.stratalab.bridge.consensus.shared.BTCConfirmationThreshold
import xyz.stratalab.bridge.consensus.shared.BTCRetryThreshold
import xyz.stratalab.bridge.consensus.shared.BTCWaitExpirationTime
import xyz.stratalab.bridge.consensus.shared.StrataConfirmationThreshold
import xyz.stratalab.bridge.consensus.shared.StrataWaitExpirationTime
import xyz.stratalab.bridge.consensus.subsystems.monitor.FSMTransition
import xyz.stratalab.bridge.consensus.subsystems.monitor.PeginStateMachineState
import org.typelevel.log4cats.Logger

object MonitorTransitionRelation
    extends TransitionToEffect
    with MonitorDepositStateTransitionRelation
    with MonitorClaimStateTransitionRelation
    with MonitorMintingStateTransitionRelation {

  def handleBlockchainEvent[F[_]: Async: Logger](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  )(
      t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
      btcRetryThreshold: BTCRetryThreshold,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      toplWaitExpirationTime: StrataWaitExpirationTime,
      btcConfirmationThreshold: BTCConfirmationThreshold,
      toplConfirmationThreshold: StrataConfirmationThreshold,
      groupId: GroupId,
      seriesId: SeriesId
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
