package org.plasmalabs.bridge

import munit.CatsEffectSuite

class BridgeIntegrationSpec
    extends CatsEffectSuite
    with SuccessfulPeginModule
    with FailedPeginNoDepositModule
    with FailedPeginNoMintModule
    with FailedRedemptionModule
    with FailedPeginNoDepositWithReorgModule
    with SuccessfulPeginWithClaimReorgModule
    with SuccessfulPeginWithClaimReorgRetryModule
    with FailedMintingReorgModule
    with SuccessfulPeginWithConcurrentSessionsModule
    with BridgeSetupModule {

  import org.typelevel.log4cats.syntax._

  override def munitFixtures = List(startServer)

  // cleanupDir.test("Bridge should correctly peg-in BTC") { _ =>
  //   info"Bridge should correctly peg-in BTC" >> successfulPegin()
  // }

  // cleanupDir.test("Bridge should fail correctly when user does not send BTC") { _ =>
  //   info"Bridge should fail correctly when user does not send BTC" >> failedPeginNoDeposit()
  // }

  // cleanupDir.test("Bridge should fail correctly when tBTC not minted") { _ =>
  //   info"Bridge should fail correctly when tBTC not minted" >> failedPeginNoMint()
  // }

  // cleanupDir.test("Bridge should fail correctly when tBTC not redeemed") { _ =>
  //   info"Bridge should fail correctly when tBTC not redeemed" >> failedRedemption()
  // }

  // cleanupDir.test(
  //   "Bridge should correctly go back from PeginSessionWaitingForEscrowBTCConfirmation"
  // ) { _ =>
  //   info"Bridge should correctly go back from PeginSessionWaitingForEscrowBTCConfirmation" >> failedPeginNoDepositWithReorg()
  // }

  // cleanupDir.test(
  //   "Bridge should correctly go back from PeginSessionWaitingForClaimBTCConfirmation"
  // ) { _ =>
  //   info"Bridge should correctly go back from PeginSessionWaitingForClaimBTCConfirmation" >> successfulPeginWithClaimError()
  // }

  // cleanupDir.test(
  //   "Bridge should correctly retry if claim does not succeed"
  // ) { _ =>
  //   info"Bridge should correctly retry if claim does not succeed" >> successfulPeginWithClaimErrorRetry()
  // }

  // cleanupDir.test(
  //   "Bridge should correctly go back to minting if there is a reorg"
  // ) { _ =>
  //   info"Bridge should correctly go back to minting if there is a reorg" >> failedMintingReorgModule()
  // }

  cleanupDir.test(
    "Bridge should correctly pegin if there are multiple concurrent sessions"
  ) { _ =>
    info"Bridge should correctly pegin if there are multiple concurrent sessions" >> successfulPeginWithConcurrentSessions(2)
  }

}
