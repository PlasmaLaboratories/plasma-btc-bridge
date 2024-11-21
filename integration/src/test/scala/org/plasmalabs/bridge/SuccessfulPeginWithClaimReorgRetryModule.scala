package org.plasmalabs.bridge

import cats.effect.IO
import org.plasmalabs.bridge.{checkMintingStatus, userFundRedeemTxProved, userVkFile}

import scala.concurrent.duration._

trait SuccessfulPeginWithClaimReorgRetryModule {

  self: BridgeIntegrationSpec =>

  def successfulPeginWithClaimErrorRetry(): IO[Unit] = {
    import org.typelevel.log4cats.syntax._
    import cats.implicits._

    assertIO(
      for {
        _                <- mintPlasmaBlock(1, 1)
        bridgeNetwork    <- computeBridgeNetworkName
        ipBitcoin02      <- extractIpBtc(2, bridgeNetwork._1)
        ipBitcoin01      <- extractIpBtc(1, bridgeNetwork._1)
        _                <- pwd
        _                <- initPlasmaWallet(2)
        _                <- addFellowship(2)
        secret           <- addSecret(2)
        newAddress       <- getNewAddress
        _                <- generateToAddress(1, 1, newAddress)
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(secret)
        _                    <- info"minHeight: ${startSessionResponse.minHeight}"
        _                    <- info"maxHeight: ${startSessionResponse.maxHeight}"
        _ <- addTemplate(
          2,
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
        _ <- createVkFile(userVkFile(2))
        _ <- importVks(2)
        _ <- fundRedeemAddressTx(
          2,
          mintingStatusResponse.address
        )
        _ <- proveFundRedeemAddressTx(2)
        _ <- broadcastFundRedeemAddressTx(userFundRedeemTxProved(2))
        _ <- mintPlasmaBlock(1, 1)
        _ <- IO.sleep(1.second)
        _ <- mintPlasmaBlock(1, 1)
        _ <- IO.sleep(1.second)
        _ <- mintPlasmaBlock(1, 1)
        _ <- IO.sleep(1.second)
        utxo <- getCurrentUtxosFromAddress(
          2,
          mintingStatusResponse.address
        )
          .iterateUntil(_.contains("LVL"))
        groupId = extractGroupId(utxo)
        seriesId = extractSeriesId(utxo)
        currentAddress <- currentAddress(2)
        // disconnect networks
        _ <- setNetworkActive(2, false)
        _ <- setNetworkActive(1, false)
        _ <- redeemAddressTx(
          2,
          currentAddress,
          btcAmountLong,
          groupId,
          seriesId
        )
        _ <- proveRedeemAddressTx(2)
        // broadcast
        _ <- broadcastFundRedeemAddressTx(userRedeemTxProved(2))
        _ <- mintPlasmaBlock(1, 1)
        _ <- getCurrentUtxosFromAddress(2, currentAddress)
          .iterateUntil(_.contains("Asset"))
        _ <- mintPlasmaBlock(1, 7)
        _ <- List
          .fill(5)((for {
            status <- checkMintingStatus(startSessionResponse.sessionID)
            _      <- info"Current minting status: ${status.mintingStatus}"
            _      <- generateToAddress(1, 1, newAddress)
            _      <- IO.sleep(1.second)
          } yield status))
          .sequence
        _ <- generateToAddress(2, 8, newAddress)
        // reconnect networks
        _ <- setNetworkActive(2, true)
        _ <- setNetworkActive(1, true)
        // force connection
        _ <- forceConnection(1, ipBitcoin02, 18444)
        _ <- forceConnection(2, ipBitcoin01, 18444)
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _      <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionWaitingForClaim"
          )
        _ <- (for {
          x <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- generateToAddress(1, 2, newAddress)
          _ <- IO.sleep(5.second)
        } yield x)
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateSuccessfulPegin"
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} went back to PeginSessionWaitingForClaim again"
        _ <-
          searchLogs(sessionStateTransitionLogNeedles(startSessionResponse.sessionID)).assertEquals(Set.empty[String])
      } yield (),
      ()
    )
  }

  private def sessionStateTransitionLogNeedles(sessionID: String): Set[String] =
    List("consensus-00", "consensus-01", "consensus-02", "consensus-03", "consensus-04", "consensus-05", "consensus-06")
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
          s"$instanceName - Transitioning session $sessionID from MConfirmingBTCClaim to MWaitingForClaim",
          s"$instanceName - Session $sessionID ended successfully",
        )
      )
      .toSet

}
