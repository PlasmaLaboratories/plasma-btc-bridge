package org.plasmalabs.bridge

import cats.effect.IO
import org.plasmalabs.bridge.shared.StartPeginSessionResponse
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._
import org.plasmalabs.bridge.userBitcoinWallet
import cats.effect.std.Mutex
import org.plasmalabs.bridge.getCurrentUtxosFromAddress
import org.plasmalabs.bridge.getNewAddress
import org.plasmalabs.bridge.mintStrataBlock

trait SuccessfulPeginWithConcurrentSessionsModule {

  self: BridgeIntegrationSpec =>

  def successfulPeginWithConcurrentSessions(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    def initBitcoinWalletById(id: Int) = for {
      _          <- initUserBitcoinWallet(userBitcoinWallet(id))
      newAddress <- getNewAddress(userBitcoinWallet(id))
      _          <- generateToAddress(1, 2, newAddress, userBitcoinWallet(id)) // minting new blocks
      _          <- IO.sleep(10.second)
    } yield (id, newAddress)

    def getSessionById(id: Int) = for {
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

    def sendBitcoinTransactions(sessionResponses: List[(Int, String, StartPeginSessionResponse)]) =
      for {

        amountResponses <- sessionResponses.parTraverse { sessionResponseWithId =>
          for {
            _ <- info"Getting Amount for User ${sessionResponseWithId._1}"
            (id, newAddress, sessionResponse) = sessionResponseWithId
            txIdAndBTCAmount <- extractGetTxIdAndAmount(userBitcoinWallet(id))
            (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount

            bitcoinTx <- createTx(
              txId,
              sessionResponse.escrowAddress,
              btcAmount
            )
            signedTxHex <- signTransaction(bitcoinTx, userBitcoinWallet(id))

          } yield (id, newAddress, sessionResponse, txId, btcAmount, btcAmountLong, signedTxHex)
        }

        _ <- IO.sleep(10.second)

        _ <- amountResponses.parTraverse { amountResponse =>
          for {
            _ <- info"User ${amountResponse._1} Sending Bitcoin Transaction"
            _ <- sendTransaction(amountResponse._7)
          } yield ()
        }

        newAddress <- getNewAddress
        _ <- generateToAddress(1, numberOfSessions * 8, newAddress) // here all should be confirmed, maybe TODO: track

        bitcoinTransactionResponses <- amountResponses.parTraverse { amountResponse =>
          for {
            confirmationsAndBlockHeight <- getTxConfirmationsAndBlockHeight(amountResponse._1, amountResponse._4)
            (confirmations, blockHeight) = confirmationsAndBlockHeight
            (id, newAddress, sessionResponse, txId, btcAmount, btcAmountLong, _) = amountResponse
          } yield (id, newAddress, sessionResponse, txId, btcAmount, btcAmountLong, confirmations, blockHeight)
        }

        _ <- info"Created Bitcoin Transactions with blockheights ${bitcoinTransactionResponses.map(_._8)}"
        _ <- info"Created Bitcoin Transactions with confirmations ${bitcoinTransactionResponses.map(_._7)}"

        _ <- IO.sleep(5.second)

      } yield bitcoinTransactionResponses

    def trackPeginSession(
      id:              Int,
      sessionResponse: StartPeginSessionResponse,
      newAddress:      String,
      btcAmountLong:   Long,
      lock:            Mutex[IO]
    ) = for {

      _ <- info"Tracking session for User ${id}"
      mintingStatusResponse <-
        (for {
          status <- checkMintingStatus(sessionResponse.sessionID)
          _      <- info"Current minting status: ${status.mintingStatus}"
          _      <- mintStrataBlock(1, 1)
          _      <- generateToAddress(1, 1, newAddress, userBitcoinWallet(id))
          _      <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")

      _ <- createVkFile(userVkFile(id))
      _ <- importVks(id)

      utxo <- lock.lock.surround {
        for {
          _          <- fundRedeemAddressTx(id, mintingStatusResponse.address)
          _          <- proveFundRedeemAddressTx(id, userFundRedeemTx(id), userFundRedeemTxProved(id))
          broadCast1 <- broadcastFundRedeemAddressTx(userFundRedeemTxProved(id))
          _          <- info"User ${id} - Result from broadcast 1 ${broadCast1}"
          _          <- mintStrataBlock(1, 2)

          utxo <- getCurrentUtxosFromAddress(id, mintingStatusResponse.address)
            .iterateUntil(_.contains("LVL"))
          _ <- mintStrataBlock(1, 1)

          _ <- IO.sleep(3.second)

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

      _ <- List.fill(8)(mintStrataBlock(1, 1)).sequence //

      _ <- getCurrentUtxosFromAddress(id, currentAddress)
        .iterateUntil(_.contains("Asset"))

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
        _ <- pwd
        _ <- mintStrataBlock(
          1,
          1
        )
        lock <- Mutex.apply[IO]

        // initialize bitcoin wallet, generate blocks to fund the wallets and retrieve amounts available
        bitcoinWallets <- (1 to numberOfSessions).toList.parTraverse { id =>
          for {
            bitcoinWallet <- initBitcoinWalletById(id)
          } yield bitcoinWallet
        }

        // make the funds of the newly created bitcoin wallets accessible
        newAddressOfTestWallet <- getNewAddress
        _                      <- generateToAddress(1, 101, newAddressOfTestWallet)

        _ <- IO.sleep(10.second)

        // start the session for the current wallet, passing down the rest of the bitcoinResponses
        sessionResponses <- bitcoinWallets.parTraverse { bitcoinResponse =>
          for {
            _ <- info"Getting session for User ${bitcoinResponse._1}"
            (id, newAddress) = bitcoinResponse
            sessionResponse <- getSessionById(id)
          } yield (id, newAddress, sessionResponse)
        }

        _ <- mintStrataBlock(1, 1)

        bitcoinTransactionResponses <- sendBitcoinTransactions(sessionResponses)

        successfulSessions <- bitcoinTransactionResponses.parTraverse { response =>
          (for {
            _ <- info"Tracking pegin session for User ${response._1}"
            (id, newAddress, sessionResponse, _, btcAmount, btcAmountLong, _, _) = response
            _ <- trackPeginSession(id, sessionResponse, newAddress, btcAmountLong, lock).timeout(250.second)
          } yield 1).handleErrorWith { error =>
            error"Error during tracking session: ${error.getMessage()}"
            IO.pure(0)
          }
        }
      } yield successfulSessions.sum,
      numberOfSessions
    )
  }
}
