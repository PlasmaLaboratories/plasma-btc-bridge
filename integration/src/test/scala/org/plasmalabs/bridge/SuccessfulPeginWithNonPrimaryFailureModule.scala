package org.plasmalabs.bridge

import cats.effect.IO
import cats.implicits._
import org.plasmalabs.bridge.userFundRedeemTxProved
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait SuccessfulPeginWithNonPrimaryFailureModule { self: BridgeIntegrationSpec =>

  def successfulPeginWithNonPrimaryFailure(): IO[Unit] =
    assertIO(
      for {
        _                <- killFiber(1)
        _                <- killFiber(2)
        _                <- pwd
        _                <- mintPlasmaBlock(1, 1)
        _                <- initPlasmaWallet(1)
        _                <- addFellowship(1)
        secret           <- addSecret(1)
        newAddress       <- getNewAddress
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(secret)
        _ <- addTemplate(
          1,
          secret,
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        )
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _           <- sendTransaction(signedTxHex)
        _           <- IO.sleep(5.second)
        _           <- generateToAddress(1, 8, newAddress)
        mintingStatusResponse <-
          (for {
            status <- checkMintingStatus(startSessionResponse.sessionID)
            _      <- info"Current minting status: ${status.mintingStatus}"
            _      <- mintPlasmaBlock(1, 1)
            _      <- generateToAddress(1, 1, newAddress)
            _      <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")
        _ <- createVkFile(userVkFile(1))
        _ <- importVks(1)
        _ <- fundRedeemAddressTx(1, mintingStatusResponse.address)
        _ <- proveFundRedeemAddressTx(1)
        _ <- broadcastFundRedeemAddressTx(userFundRedeemTxProved(1))
        _ <- mintPlasmaBlock(1, 1)
        utxo <- getCurrentUtxosFromAddress(1, mintingStatusResponse.address)
          .iterateUntil(_.contains("LVL"))
        groupId = extractGroupId(utxo)
        seriesId = extractSeriesId(utxo)
        currentAddress <- currentAddress(1)
        _ <- redeemAddressTx(
          1,
          currentAddress,
          btcAmountLong,
          groupId,
          seriesId
        )
        _ <- proveRedeemAddressTx(1)
        _ <- broadcastFundRedeemAddressTx(userRedeemTxProved(1))
        _ <- List.fill(8)(mintPlasmaBlock(1, 1)).sequence
        _ <- getCurrentUtxosFromAddress(1, currentAddress)
          .iterateUntil(_.contains("Asset"))
        _ <- generateToAddress(1, 3, newAddress)
        _ <- checkMintingStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            generateToAddress(
              1,
              1,
              newAddress
            ) >> warn"x.mintingStatus = ${x.mintingStatus}" >> IO
              .sleep(5.second) >> IO.pure(x)
          )
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateSuccessfulPegin"
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} was successfully removed"
        _ <-
          searchLogs(sessionStateTransitionLogNeedles(startSessionResponse.sessionID)).assertEquals(Set.empty[String])
      } yield (),
      ()
    )

  private def sessionStateTransitionLogNeedles(sessionID: String): Set[String] =
    List("consensus-00", "consensus-03", "consensus-04", "consensus-05", "consensus-06")
      .flatMap(instanceName =>
        List(
          s"$instanceName - Transitioning session $sessionID from MWaitingForBTCDeposit to MConfirmingBTCDeposit",
          s"$instanceName - Transitioning session $sessionID from MConfirmingBTCDeposit to MMintingTBTC",
          s"$instanceName - Transitioning session $sessionID from MMintingTBTC to MConfirmingTBTCMint",
          // TODO: MWaitingForRedemption?
          s"$instanceName - Transitioning session $sessionID from MConfirmingTBTCMint to MConfirmingRedemption",
          s"$instanceName - Transitioning session $sessionID from MConfirmingRedemption to MWaitingForClaim",
          // TODO: Why does this transition take place?
          s"$instanceName - Transitioning session $sessionID from MWaitingForClaim to MWaitingForClaim",
          s"$instanceName - Transitioning session $sessionID from MWaitingForClaim to MConfirmingBTCClaim",
          s"$instanceName - Session $sessionID ended successfully",
        )
      )
      .toSet
}
