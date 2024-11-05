package org.plasmalabs.bridge

import cats.effect.IO

import scala.concurrent.duration._
import org.plasmalabs.bridge.checkMintingStatus

trait SuccessfulPeginWithClaimReorgRetryModule {

  self: BridgeIntegrationSpec =>

  def successfulPeginWithClaimErrorRetry(): IO[Unit] = {
    import org.typelevel.log4cats.syntax._
    import cats.implicits._

    assertIO(
      for {
        _                <- mintStrataBlock(1, 1)
        bridgeNetwork    <- computeBridgeNetworkName
        ipBitcoin02      <- extractIpBtc(2, bridgeNetwork._1)
        ipBitcoin01      <- extractIpBtc(1, bridgeNetwork._1)
        _                <- pwd
        _                <- initStrataWallet(2)
        _                <- addFellowship(2)
        secret                <- addSecret(2)
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
            _      <- mintStrataBlock(1, 1)
            _      <- generateToAddress(1, 1, newAddress)
            _      <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")
        _ <- createVkFile(vkFile)
        _ <- importVks(2)
        _ <- fundRedeemAddressTx(
          2,
          mintingStatusResponse.address
        )
        _ <- proveFundRedeemAddressTx(
          2,
          "fundRedeemTx.pbuf",
          "fundRedeemTxProved.pbuf"
        )
        _ <- broadcastFundRedeemAddressTx("fundRedeemTxProved.pbuf")
        _ <- mintStrataBlock(1, 1)
        _ <- IO.sleep(1.second)
        _ <- mintStrataBlock(1, 1)
        _ <- IO.sleep(1.second)
        _ <- mintStrataBlock(1, 1)
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
        _ <- proveFundRedeemAddressTx(
          2,
          "redeemTx.pbuf",
          "redeemTxProved.pbuf"
        )
        // broadcast
        _ <- broadcastFundRedeemAddressTx("redeemTxProved.pbuf")
        _ <- mintStrataBlock(1, 1)
        _ <- getCurrentUtxosFromAddress(2, currentAddress)
          .iterateUntil(_.contains("Asset"))
        _ <- mintStrataBlock(1, 7)
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
      } yield (),
      ()
    )
  }

}
