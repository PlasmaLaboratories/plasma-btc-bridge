package org.plasmalabs.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._
import org.plasmalabs.bridge.shared.StartPeginSessionResponse

trait SuccessfulPeginWithConcurrentSessionsModule {

  // self BridgeIntegrationSpec
  self: BridgeIntegrationSpec =>

  def successfulPeginWithConcurrentSessions(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    def getSessionById(id: Int) = for {
      _ <- pwd
        _ <- mintStrataBlock(
          1,
          1
        )
      _                <- initStrataWallet(id)
      _                <- addFellowship(id)
      secret                <- addSecret(id)
      newAddress       <- getNewAddress
      startSessionResponse <- startSession(secret)
      _ <- addTemplate(
          id,
          secret,
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        )
    } yield (startSessionResponse, newAddress, id)

    def trackPeginSession(id: Int, sessionResponse: StartPeginSessionResponse, newAddress: String, btcAmountLong: Long) = for {
      _ <- info"Tracking session for User ${id}"
      _           <- generateToAddress(1, 8, newAddress) // 8 is number of blocks 
        mintingStatusResponse <-
          (for {
            status <- checkMintingStatus(sessionResponse.sessionID)
            _      <- info"Current minting status: ${status.mintingStatus}"
            _      <- mintStrataBlock(1, 1)
            _      <- generateToAddress(1, 1, newAddress)
            _      <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")

      _ <- createVkFile(userVkFile(id))
      _ <- importVks(id)
      _ <- fundRedeemAddressTx(id, mintingStatusResponse.address)
      _ <- proveFundRedeemAddressTx(id, userFundRedeemTx(id), userFundRedeemTxProved(id))
      _ <- info"User ${id} - Broadcasting ${userFundRedeemTxProved(id)}"
      _ <- broadcastFundRedeemAddressTx(userFundRedeemTxProved(id))
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
          seriesId
        )
      _ <- proveFundRedeemAddressTx(
          id,
          userRedeemTx(id),
          userRedeemTxProved(id)
        )

      _ <- info"User ${id} - Broadcasting ${userRedeemTxProved(id)}"
      _ <- broadcastFundRedeemAddressTx(userRedeemTxProved(id))
      _ <- List.fill(8)(mintStrataBlock(1, 1)).sequence
      _ <- getCurrentUtxosFromAddress(id, currentAddress)
          .iterateUntil(_.contains("Asset"))

      _ <- info"User ${id} - Tracking minting status for successful"
      _ <- generateToAddress(1, 3, newAddress)
      _ <- checkMintingStatus(sessionResponse.sessionID)
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
          info"User ${id} -  Session ${sessionResponse.sessionID} was successfully removed"
    } yield 1

    assertIO(
      for {
        _ <- deleteOutputFiles(numberOfSessions)
        
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount

        sessionResponses <- (1 to numberOfSessions).toList.parTraverse{
          id => for {
            response <- getSessionById(id)
          } yield response
        }
        
        bitcoinTx <- createTxMultiple(
          txId,
          sessionResponses.map(_._1.escrowAddress),
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _           <- sendTransaction(signedTxHex)
        _           <- IO.sleep(5.second)

        successfulSessions  <- sessionResponses.parTraverse {
          response => for {
            successful <- trackPeginSession(response._3,response._1, response._2, btcAmountLong / numberOfSessions)
          } yield successful
        }
        
      } yield successfulSessions.sum,
      numberOfSessions
    )
  }
}