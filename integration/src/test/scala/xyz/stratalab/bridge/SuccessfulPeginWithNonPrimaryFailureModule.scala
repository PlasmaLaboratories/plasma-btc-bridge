package xyz.stratalab.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._
import scala.concurrent.duration._
import cats.implicits._

trait SuccessfulPeginWithNonPrimaryFailureModule { self: BridgeIntegrationSpec =>
  def successfulPeginWithNonPrimaryFailure(): IO[Unit] = {
    val bridgeFixture = startServer.apply()

    (for {
      // Kill replicas before test
      _ <- bridgeFixture.killFiber(1)
      _ <- bridgeFixture.killFiber(2)

      // Run test
      _ <- pwd
      _ <- mintStrataBlock(1, 1)
      _ <- initStrataWallet(1)
      _ <- addFellowship(1)
      _ <- addSecret(1)
      newAddress <- getNewAddress
      txIdAndBTCAmount <- extractGetTxIdAndAmount
      (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
      startSessionResponse <- startSession(1)
      _ <- addTemplate(
        1,
        shaSecretMap(1),
        startSessionResponse.minHeight,
        startSessionResponse.maxHeight
      )
      bitcoinTx <- createTx(
        txId,
        startSessionResponse.escrowAddress,
        btcAmount
      )
      signedTxHex <- signTransaction(bitcoinTx)
      _ <- sendTransaction(signedTxHex)
      _ <- IO.sleep(5.second)
      _ <- generateToAddress(1, 8, newAddress)
      mintingStatusResponse <-
        (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- info"Current minting status: ${status.mintingStatus}"
          _ <- mintStrataBlock(1, 1)
          _ <- generateToAddress(1, 1, newAddress)
          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")
      _ <- createVkFile(vkFile)
      _ <- importVks(1)
      _ <- fundRedeemAddressTx(1, mintingStatusResponse.address)
      _ <- proveFundRedeemAddressTx(
        1,
        "fundRedeemTx.pbuf",
        "fundRedeemTxProved.pbuf"
      )
      _ <- broadcastFundRedeemAddressTx("fundRedeemTxProved.pbuf")
      _ <- mintStrataBlock(1, 1)
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
      _ <- proveFundRedeemAddressTx(
        1,
        "redeemTx.pbuf",
        "redeemTxProved.pbuf"
      )
      _ <- broadcastFundRedeemAddressTx("redeemTxProved.pbuf")
      _ <- List.fill(8)(mintStrataBlock(1, 1)).sequence
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
      _ <- info"Session ${startSessionResponse.sessionID} was successfully removed"
    } yield ())
      .guarantee(
        // Restore replicas after test
        bridgeFixture.restoreFiber(1) >>
        bridgeFixture.restoreFiber(2)
      )
      .map(_ => ())
  }
}