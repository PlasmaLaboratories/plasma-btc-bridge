package org.plasmalabs.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._
import org.plasmalabs.bridge.shared.TimeoutError

trait SuccessfulPeginWithConcurrentSessionsModule {

  // self BridgeIntegrationSpec
  self: BridgeIntegrationSpec =>

  def successfulPeginWithConcurrentSessions(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    def successfulPeginForSession (id: Int) : IO[Boolean] = {
      import cats.implicits._
      val idFormatted = f"$id%02d"
      val walletName =  s"user-wallet${idFormatted}"
      val vkFileName = s"key${idFormatted}.txt"

      (for {
        _ <- pwd
        _ <- mintStrataBlock(
          1,
          1
        ) // this will update the current topl height on the node, node should not work without this
        _           <- initUserBitcoinWallet(walletName)
        newAddress  <- getNewAddress(walletName)
        _           <- generateToAddress(1, 101, newAddress)
        _           <- mintStrataBlock(1, 1)
        _           <- initStrataWallet(id)
        _                <- addFellowship(id)
        _                <- addSecret(id)
        newAddress       <- getNewAddress(walletName)
        txIdAndBTCAmount <- extractGetTxIdAndAmount(walletName)
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(1)
        _ <- addTemplate(
          id,
          shaSecretMap(1),
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        )
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx, walletName)
        _           <- sendTransaction(signedTxHex, walletName)
        _           <- IO.sleep(5.second)
        _           <- generateToAddress(1, 8, newAddress)
        mintingStatusResponse <-
          (for {
            status <- checkMintingStatus(startSessionResponse.sessionID)
            _      <- info"Current minting status: ${status.mintingStatus}"
            _      <- mintStrataBlock(1, 1)
            _      <- generateToAddress(1, 1, newAddress)
            _      <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(response => response.mintingStatus == "PeginSessionStateMintingTBTC" || response.mintingStatus == "PeginSessionStateTimeout")
            .flatMap(result => 
              result.mintingStatus match {
                case "PeginSessionStateTimeout" => IO.raiseError(TimeoutError(""))
                case _ => IO.pure(result)
              }

            )
        _ <- info"User ${id} - Session Status1: ${mintingStatusResponse.mintingStatus}"
        _ <- createVkFile(vkFileName)
        _ <- importVks(id, vkFileName)
        _ <- fundRedeemAddressTx(id, mintingStatusResponse.address, s"fundRedeemTx${idFormatted}.pbuf")
        _ <- proveFundRedeemAddressTx(
          id,
          s"fundRedeemTx${idFormatted}.pbuf",
          s"fundRedeemTxProved${idFormatted}.pbuf",
        )
        _ <- broadcastFundRedeemAddressTx(s"fundRedeemTxProved${idFormatted}.pbuf")
        _ <- mintStrataBlock(1, 1)
        utxo <- getCurrentUtxosFromAddress(id, mintingStatusResponse.address)
          .iterateUntil(_.contains("LVL"))
        groupId = extractGroupId(utxo)
        seriesId = extractSeriesId(utxo)
        currentAddress <- currentAddress(id)
        _ <- redeemAddressTx(
          id,
          currentAddress,
          btcAmountLong,
          groupId,
          seriesId,
          s"redeemTx${idFormatted}.pbuf"
        )
        _ <- proveFundRedeemAddressTx(
          id,
          s"redeemTx${idFormatted}.pbuf",
          s"redeemTxProved${idFormatted}.pbuf"
        )
        _ <- broadcastFundRedeemAddressTx(s"redeemTxProved${idFormatted}.pbuf")
        _ <- List.fill(8)(mintStrataBlock(1, 1)).sequence
        _ <- getCurrentUtxosFromAddress(1, currentAddress)
          .iterateUntil(_.contains("Asset"))
        _ <- generateToAddress(1, 3, newAddress)
        response <- checkMintingStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            generateToAddress(
              1,
              1,
              newAddress
            ) >> warn"x.mintingStatus = ${x.mintingStatus}" >> IO
              .sleep(5.second) >> IO.pure(x)
          )
          .iterateUntil(
            response => response.mintingStatus == "PeginSessionStateSuccessfulPegin" || response.mintingStatus == "PeginSessionStateTimeout"
          )
          .flatMap(result => 
              result.mintingStatus match {
                case "PeginSessionStateTimeout" => IO.raiseError(TimeoutError(""))
                case _ => IO.pure(result)
              }

            )
            _ <- info"User ${id} - Session Status2: ${response.mintingStatus}"

        _ <-
          info"Session ${startSessionResponse.sessionID} was successfully removed"
      } yield true).handleErrorWith { error =>
        error"Session $id failed with error: ${error.getMessage}" >>
          IO.pure(false)
      }
    
    }

    assertIO(
      for {
        _ <- deleteFiles(numberOfSessions)
        results <- (1 to numberOfSessions).toList.parTraverse {sessionId => 
          successfulPeginForSession(sessionId)
        }
        successCount = results.count(identity)
      } yield successCount,
      numberOfSessions
    )
  }

}
