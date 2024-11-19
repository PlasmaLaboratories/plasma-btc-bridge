package org.plasmalabs.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedPeginNoMintModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoMint(): IO[Unit] =
    assertIO(
      for {
        _                <- mintPlasmaBlock(1, 1)
        _                <- IO.sleep(1.second)
        newAddress       <- getNewAddress
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession()
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _           <- sendTransaction(signedTxHex)
        _           <- generateToAddress(1, 52, newAddress)
        _ <- checkMintingStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            for {
              _ <- generateToAddress(1, 5, newAddress)
              _ <- IO.sleep(1.second)
            } yield x
          )
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateTimeout"
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} was successfully removed"
        _ <-
          searchLogs(sessionStateTransitionLogNeedles(startSessionResponse.sessionID)).assertEquals(Set.empty[String])
      } yield (),
      ()
    )

  private def sessionStateTransitionLogNeedles(sessionID: String): Set[String] =
    List("consensus-00", "consensus-01", "consensus-02", "consensus-03", "consensus-04", "consensus-05", "consensus-06")
      .flatMap(instanceName =>
        List(
          s"$instanceName - Transitioning session $sessionID from MWaitingForBTCDeposit to MConfirmingBTCDeposit",
          s"$instanceName - Transitioning session $sessionID from MConfirmingBTCDeposit to MMintingTBTC",
          s"$instanceName - Session $sessionID ended successfully",
        )
      )
      .toSet
}
