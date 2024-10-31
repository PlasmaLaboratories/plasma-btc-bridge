package org.plasmalabs.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

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
        )
        _           <- initUserBitcoinWallet(walletName) // password and user name is fixed 
        newAddress  <- getNewAddress(walletName) // for just created wallet
        _           <- generateToAddress(1, 101, newAddress) // checked
        _           <- mintStrataBlock(1, 1) // plasma
        _           <- initStrataWallet(id) // plasma
        _                <- addFellowship(id) // plasma
        secret               <- addSecret(id) // secret to redeem on plasma side
        newAddress       <- getNewAddress(walletName) // needs wallet name
        pkey <- getPKey 
        txIdAndBTCAmount <- extractGetTxIdAndAmount(walletName) // needs wallet name
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(pkey, secret) // currently all are using different id's
        _ <- addTemplate(
          id,
          secret,
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        )
        bitcoinTx <- createTx( // creates an unsigned tx so no wallet needed here 
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx, walletName) // with wallet name
        _           <- sendTransaction(signedTxHex, walletName) // with wallet name 
        _           <- IO.sleep(5.second)
        _           <- generateToAddress(1, 8, newAddress) // 
        mintingStatusResponse <-
          (for {
            status <- checkMintingStatus(startSessionResponse.sessionID)
            _      <- info"Current minting status: ${status.mintingStatus}"
            _      <- mintStrataBlock(1, 1)
            _      <- generateToAddress(1, 1, newAddress)
            _      <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(response => response.mintingStatus == "PeginSessionStateMintingTBTC")
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
          .iterateUntil(response => response.mintingStatus == "PeginSessionStateSuccessfulPegin" )
        _ <- info"User ${id} - Session Status2: ${response.mintingStatus}"
        _ <- info"Session ${startSessionResponse.sessionID} was successfully removed"
      } yield true).handleErrorWith { error =>
        error"Session $id failed with error: ${error.getMessage}" >>
          IO.pure(false)
      }
    
    }

    assertIO(
      for {
        _ <- deleteFiles(numberOfSessions)
        results <- (1 to numberOfSessions).toList.parTraverse {sessionId => 
          successfulPeginForSession(sessionId).timeout(180.seconds).handleErrorWith(_ => IO.pure(false))
        }
        successCount = results.count(identity)
      } yield successCount,
      numberOfSessions
    )
  }

}
