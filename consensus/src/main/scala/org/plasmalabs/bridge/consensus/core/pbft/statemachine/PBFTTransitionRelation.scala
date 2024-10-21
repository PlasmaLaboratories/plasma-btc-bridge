package xyz.stratalab.bridge.consensus.core.pbft.statemachine

object PBFTTransitionRelation {
  import cats.implicits._

  def handlePBFTEvent(
    currentState: PBFTState,
    pbftEvent:    PBFTEvent
  ): Option[PBFTState] =
    (currentState, pbftEvent) match {
      case (
            cs: PSWaitingForBTCDeposit,
            ev: PostDepositBTCEvt
          ) =>
        PSMintingTBTC(
          startWaitingBTCBlockHeight = cs.height,
          currentWalletIdx = cs.currentWalletIdx,
          scriptAsm = cs.scriptAsm,
          redeemAddress = cs.redeemAddress,
          claimAddress = cs.claimAddress,
          btcTxId = ev.txId,
          btcVout = ev.vout,
          amount = ev.amount
        ).some
      case (
            cs: PSMintingTBTC,
            ev: PostRedemptionTxEvt
          ) =>
        PSClaimingBTC(
          someStartBtcBlockHeight = None,
          secret = ev.secret,
          currentWalletIdx = cs.currentWalletIdx,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          scriptAsm = cs.scriptAsm,
          amount = ev.amount,
          claimAddress = cs.claimAddress
        ).some
      case (_, _) => none
    }

}
