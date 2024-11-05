package org.plasmalabs.bridge

import cats.effect.IO
import org.plasmalabs.bridge.shared.StartPeginSessionResponse
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._
import org.plasmalabs.bridge.userBitcoinWallet
import cats.effect.std.Mutex

trait SuccessfulPeginWithConcurrentSessionsModule {

  // self BridgeIntegrationSpec
  self: BridgeIntegrationSpec =>

  def successfulPeginWithConcurrentSessions(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    def initBitcoinWalletById(id: Int) = for {
      _ <- info"initializing Bitcoin Wallet for User ${id}"
      _          <- initUserBitcoinWallet(userBitcoinWallet(id))
      newAddress <- getNewAddress(userBitcoinWallet(id))
      _          <- generateToAddress(1, 101, newAddress, userBitcoinWallet(id) )
      _          <- mintStrataBlock(1, 1)

      txIdAndBTCAmount <- extractGetTxIdAndAmount(userBitcoinWallet(id))
      (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
      _ <- info"User ${id}: txId: ${txId}, btcAmount: ${btcAmount}, btcAmountLong: ${btcAmountLong}"
    } yield (id, newAddress, txId, btcAmount, btcAmountLong)

    def getSessionById(id: Int) = for {
      _ <- pwd
      _ <- mintStrataBlock(
        1,
        1
      )
      _                    <- initStrataWallet(id)
      _                    <- addFellowship(id)
      secret               <- addSecret(id)
      startSessionResponse <- startSession(secret)
      _ <- addTemplate(
        id,
        secret,
        startSessionResponse.minHeight,
        startSessionResponse.maxHeight
      )
    } yield startSessionResponse

    def trackPeginSession(
      id:              Int,
      sessionResponse: StartPeginSessionResponse,
      newAddress:      String,
      btcAmountLong:   Long, 
      txId: String, 
      btcAmount:  BigDecimal, 
      lock: Mutex[IO]
    ) = for {
      _ <- info"User ${id} will create the following transaction ${createTxSeq(txId, sessionResponse.escrowAddress, btcAmount)}"
        bitcoinTx <- createTx(
          txId,
          sessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx, userBitcoinWallet(id))
        _           <- sendTransaction(signedTxHex)

      _           <- IO.sleep(5.second)

      _ <- info"Tracking session for User ${id}"
      _ <- generateToAddress(1, 8, newAddress, userBitcoinWallet(id)) 
      mintingStatusResponse <-
        (for {
          status <- checkMintingStatus(sessionResponse.sessionID)
          _      <- info"Current minting status: ${status.mintingStatus}"
          _      <- mintStrataBlock(1, 1)
          _      <- generateToAddress(1, 1, newAddress, userBitcoinWallet(id))
          _      <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")

      _          <- createVkFile(userVkFile(id))
      _          <- importVks(id)

      utxo <- lock.lock.surround {
        for {
          _          <- fundRedeemAddressTx(id, mintingStatusResponse.address)
          _          <- proveFundRedeemAddressTx(id, userFundRedeemTx(id), userFundRedeemTxProved(id))
          broadCast1 <- broadcastFundRedeemAddressTx(userFundRedeemTxProved(id))
          _          <- info"User ${id} - Result from broadcast 1 ${broadCast1}"
          _          <- mintStrataBlock(1, 1)

         utxo <- getCurrentUtxosFromAddress(id, mintingStatusResponse.address)
         .iterateUntil(_.contains("LVL"))
        } yield utxo
      }
      
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

      broadCast2 <- broadcastFundRedeemAddressTx(userRedeemTxProved(id))
      _          <- info"User ${id} - Result from broadcast 2 ${broadCast2}"

      _ <- List.fill(8)(mintStrataBlock(1, 1)).sequence
      _ <- getCurrentUtxosFromAddress(id, currentAddress)
        .iterateUntil(_.contains("Asset"))

      _ <- info"User ${id} - Tracking minting status for successful"
      _ <- generateToAddress(1, 3, newAddress, userBitcoinWallet(id))
      _ <- checkMintingStatus(sessionResponse.sessionID)
        .flatMap(x =>
          generateToAddress(
            1,
            1,
            newAddress, 
            userBitcoinWallet(id)
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
        lock <- Mutex.apply[IO]

        bitcoinResponses <- (1 to numberOfSessions).toList.parTraverse {
          id => for {
            bitcoinResponse <- initBitcoinWalletById(id)
          } yield bitcoinResponse
        } // (id, newAddress, txId, btcAmount, btcAmountLong)

        sessionResponses <- bitcoinResponses.parTraverse { bitcoinResponse =>
          for {
            _ <- info"Getting session for User ${bitcoinResponse._1}"
            (id, newAddress, txId, btcAmount, btcAmountLong) = bitcoinResponse
            response <- getSessionById(id)
          } yield (id, newAddress, txId, btcAmount, btcAmountLong, response)
        }
        _           <- IO.sleep(5.second)

        _ <- sessionResponses.parTraverse { sessionResponse =>
          for {
            _ <- info"Tracking pegin session for User ${sessionResponse._1}"
            (id, newAddress, txId, btcAmount, btcAmountLong,response) = sessionResponse

            _ <- trackPeginSession(id,response,
              newAddress,
              btcAmountLong, 
              txId, 
              btcAmount, 
              lock
            )
          } yield ()
        }
      } yield (),
      ()
    )
  }
}
