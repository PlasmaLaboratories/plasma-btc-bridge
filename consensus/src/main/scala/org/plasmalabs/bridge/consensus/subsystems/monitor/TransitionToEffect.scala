package org.plasmalabs.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import cats.implicits._
import com.google.protobuf.ByteString
import org.plasmalabs.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  PlasmaConfirmationThreshold,
  PlasmaWaitExpirationTime
}
import org.plasmalabs.bridge.consensus.subsystems.monitor.{
  MConfirmingBTCDeposit,
  MConfirmingTBTCMint,
  MMintingTBTC,
  MWaitingForBTCDeposit,
  MWaitingForRedemption,
  PeginStateMachineState
}
import org.plasmalabs.bridge.shared.{
  ClientId,
  PostClaimTxOperation,
  PostDepositBTCOperation,
  PostRedemptionTxOperation,
  SessionId,
  StateMachineServiceGrpcClient,
  TimeoutDepositBTCOperation,
  TimeoutTBTCMintOperation
}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

trait TransitionToEffect {

  def isAboveConfirmationThresholdBTC(
    currentHeight: Int,
    startHeight:   Int
  )(implicit btcConfirmationThreshold: BTCConfirmationThreshold) =
    currentHeight - startHeight > btcConfirmationThreshold.underlying

  def isAboveConfirmationThresholdPlasma(
    currentHeight: Long,
    startHeight:   Long
  )(implicit toplConfirmationThreshold: PlasmaConfirmationThreshold) =
    currentHeight - startHeight > toplConfirmationThreshold.underlying

  def transitionToEffect[F[_]: Async: Logger](
    currentState:    PeginStateMachineState,
    blockchainEvent: BlockchainEvent
  )(implicit
    clientId:                  ClientId,
    session:                   SessionId,
    consensusClient:           StateMachineServiceGrpcClient[F],
    toplWaitExpirationTime:    PlasmaWaitExpirationTime,
    toplConfirmationThreshold: PlasmaConfirmationThreshold,
    btcConfirmationThreshold:  BTCConfirmationThreshold
  ) =
    (blockchainEvent match {
      case SkippedPlasmaBlock(height) =>
        error"Error the processor skipped Plasma block $height"
      case SkippedBTCBlock(height) =>
        error"Error the processor skipped BTC block $height"
      case NewPlasmaBlock(height) =>
        debug"New Plasma block $height"
      case NewBTCBlock(height) =>
        debug"New BTC block $height"
      case _ =>
        Async[F].unit
    }) >>
    ((currentState, blockchainEvent) match {
      case (
            _: MWaitingForBTCDeposit,
            ev: NewBTCBlock
          ) =>
        Async[F]
          .start(
            consensusClient.timeoutDepositBTC(
              TimeoutDepositBTCOperation(
                session.id,
                ev.height
              )
            )
          )
          .void
      case (
            cs: MConfirmingBTCDeposit,
            ev: NewBTCBlock
          ) =>
        if (isAboveConfirmationThresholdBTC(ev.height, cs.depositBTCBlockHeight)) {
          Async[F]
            .start(
              consensusClient.postDepositBTC(
                PostDepositBTCOperation(
                  session.id,
                  ev.height,
                  cs.btcTxId,
                  cs.btcVout,
                  ByteString.copyFrom(cs.amount.satoshis.toBigInt.toByteArray)
                )
              )
            )
            .void
        } else {
          Async[F].unit
        }
      case (
            _: MMintingTBTC,
            ev: NewBTCBlock
          ) =>
        Async[F]
          .start(
            consensusClient.timeoutTBTCMint(
              TimeoutTBTCMintOperation(
                session.id,
                ev.height
              )
            )
          )
          .void
      case (
            cs: MConfirmingTBTCMint,
            be: NewPlasmaBlock
          ) =>
        if (
          isAboveConfirmationThresholdPlasma(
            be.height,
            cs.depositTBTCBlockHeight
          )
        ) {
          Async[F].unit
        } else if ( // FIXME: check that this is the right time to wait
          toplWaitExpirationTime.underlying < (be.height - cs.depositTBTCBlockHeight)
        )
          Async[F]
            .start(
              consensusClient.timeoutTBTCMint(
                TimeoutTBTCMintOperation(
                  session.id
                )
              )
            )
            .void
        else if (be.height <= cs.depositTBTCBlockHeight)
          Async[F].unit
        else
          Async[F].unit
      case (
            cs: MConfirmingRedemption,
            ev: NewPlasmaBlock
          ) =>
        import org.plasmalabs.sdk.syntax._
        if (isAboveConfirmationThresholdPlasma(ev.height, cs.currentTolpBlockHeight))
          Async[F]
            .start(
              debug"Posting redemption transaction to network" >>
              consensusClient.postRedemptionTx(
                PostRedemptionTxOperation(
                  session.id,
                  cs.secret,
                  cs.currentTolpBlockHeight,
                  cs.utxoTxId,
                  cs.utxoIndex,
                  cs.btcTxId,
                  cs.btcVout,
                  ByteString.copyFrom(int128AsBigInt(cs.amount.amount).toByteArray)
                )
              )
            )
            .void
        else Async[F].unit
      case (
            _: MWaitingForRedemption,
            ev: NewPlasmaBlock
          ) =>
        Async[F]
          .start(
            Async[F]
              .start(
                consensusClient.timeoutTBTCMint(
                  TimeoutTBTCMintOperation(
                    session.id,
                    0,
                    ev.height
                  )
                )
              )
              .void
          )
          .void
      case (
            cs: MConfirmingBTCClaim,
            ev: NewBTCBlock
          ) =>
        if (isAboveConfirmationThresholdBTC(ev.height, cs.claimBTCBlockHeight))
          Async[F]
            .start(
              consensusClient.postClaimTx(
                PostClaimTxOperation(
                  session.id,
                  ev.height,
                  cs.btcTxId,
                  cs.btcVout
                )
              )
            )
            .void
        else
          Async[F].unit
      case (_, _) => Async[F].unit
    })

}
