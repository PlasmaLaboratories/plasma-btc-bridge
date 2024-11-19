package org.plasmalabs.bridge

import cats.effect.IO
import cats.effect.std.Queue
import org.plasmalabs.bridge.shared.StartPeginSessionResponse
import org.plasmalabs.bridge.{
  getCurrentUtxosFromAddress,
  getNewAddress,
  mintPlasmaBlock,
  proveRedeemAddressTx,
  userBitcoinWallet
}
import org.typelevel.log4cats.syntax._

import java.nio.file.{Files, Paths}
import scala.concurrent.duration._
import scala.util.Random

trait SuccessfulPeginWithConcurrentSessionsModule {

  self: BridgeIntegrationSpec =>

  def successfulPeginWithConcurrentSessions(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    def runBitcoinMintingStream(bitcoinMintingQueue: Queue[IO, (String, String, Int)]): fs2.Stream[IO, Unit] =
      fs2.Stream
        .fromQueueUnterminated(bitcoinMintingQueue)
        .evalMap { request =>
          for {
            _ <- debug"Processing Bitcoin Minting during test"
            (newAddress, walletName, numberOfBlocks) = request
            _ <- generateToAddress(1, numberOfBlocks, newAddress, walletName)
            _ <- IO.sleep(1.second)
          } yield ()
        }

    def initBitcoinWalletById(userId: Int, bitcoinMintingQueue: Queue[IO, (String, String, Int)]) = for {
      _          <- initUserBitcoinWallet(userBitcoinWallet(userId))
      newAddress <- getNewAddress(userBitcoinWallet(userId))
      _          <- bitcoinMintingQueue.offer((newAddress, userBitcoinWallet(userId), 7))
      _          <- IO.sleep(1.second)
    } yield (userId, newAddress)

    def getSessionById(userId: Int) = for {
      _                    <- initPlasmaWallet(userId)
      _                    <- addFellowship(userId)
      userSecret           <- addSecret(userId)
      startSessionResponse <- startSession(userSecret)
      _ <- addTemplate(
        userId,
        userSecret,
        startSessionResponse.minHeight,
        startSessionResponse.maxHeight
      )

    } yield startSessionResponse

    def sendBitcoinTransactions(
      sessionResponses:    List[(Int, String, StartPeginSessionResponse)],
      bitcoinMintingQueue: Queue[IO, (String, String, Int)]
    ) =
      for {
        amountResponses <- sessionResponses.parTraverse { sessionResponseWithId =>
          for {
            _ <- info"Getting Amount for User ${sessionResponseWithId._1}"
            (userId, newAddress, sessionResponse) = sessionResponseWithId
            txIdAndBTCAmount <- extractGetTxIdAndAmount(walletName = userBitcoinWallet(userId))
            (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount

            bitcoinTx <- createTx(
              txId,
              sessionResponse.escrowAddress,
              btcAmount
            )
            signedTxHex <- signTransaction(bitcoinTx, userBitcoinWallet(userId))

          } yield (userId, newAddress, sessionResponse, txId, btcAmount, btcAmountLong, signedTxHex)
        }

        _ <- amountResponses.traverse { amountResponse =>
          for {
            _ <- sendTransaction(amountResponse._7)
          } yield ()
        }

        _ <- IO.sleep(1.second)

        newAddress <- getNewAddress
        _          <- bitcoinMintingQueue.offer((newAddress, "testwallet", numberOfSessions * 3))

        bitcoinTransactionResponses <- amountResponses.parTraverse { amountResponse =>
          for {
            confirmationsAndBlockHeight <- getTxConfirmationsAndBlockHeight(
              id = amountResponse._1,
              txId = amountResponse._4
            )
            (confirmations, blockHeight) = confirmationsAndBlockHeight
            (id, newAddress, sessionResponse, txId, btcAmount, btcAmountLong, _) = amountResponse

          } yield (id, newAddress, sessionResponse, txId, btcAmount, btcAmountLong, confirmations, blockHeight)
        }

        _ <- info"Created Bitcoin Transactions with blockheights ${bitcoinTransactionResponses.map(_._8)}"
        _ <- debug"Created Bitcoin Transactions with confirmations ${bitcoinTransactionResponses.map(_._7)}"

      } yield bitcoinTransactionResponses

    def attemptFundLvl(
      userId:  Int,
      address: String,
      time:    Int
    ): IO[String] = {
      def singleAttempt: IO[String] =
        for {
          _ <- IO {
            List(
              userFundRedeemTx(userId),
              userFundRedeemTxProved(userId)
            ).foreach { case (path) =>
              try
                Files.delete(Paths.get(path))
              catch {
                case _: Throwable => ()
              }
            }
          }

          _ <- IO.sleep(Random.between(1, 6).second)
          _ <- fundRedeemAddressTx(userId, address)
          _ <- proveFundRedeemAddressTx(userId)
          _ <- broadcastFundRedeemAddressTx(userFundRedeemTxProved(userId))
          _ <- mintPlasmaBlock(1, 2)

          utxoAttempt <- getCurrentUtxosFromAddress(userId, address)
            .iterateUntil(_.contains("LVL"))
            .timeout(time.second)

          _ <- mintPlasmaBlock(1, 1)
          _ <- IO.sleep(3.second)
        } yield utxoAttempt

      def retry: IO[String] =
        singleAttempt.handleErrorWith(_ => retry)

      retry
    }

    def trackPeginSession(
      userId:              Int,
      sessionResponse:     StartPeginSessionResponse,
      newAddress:          String,
      btcAmountLong:       Long,
      bitcoinMintingQueue: Queue[IO, (String, String, Int)]
    ) = for {

      _ <- info"Tracking session for User ${userId}"
      mintingStatusResponse <-
        (for {
          status <- checkMintingStatus(sessionResponse.sessionID)
          _      <- info"Current minting status: ${status.mintingStatus}"
          _      <- mintPlasmaBlock(node = 1, nbBlocks = 1)
          _      <- bitcoinMintingQueue.offer((newAddress, userBitcoinWallet(userId), 1))

          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")

      _ <- createVkFile(vkFile = userVkFile(userId))
      _ <- importVks(userId)

      utxo <- attemptFundLvl(userId, mintingStatusResponse.address, time = 15)

      groupId = extractGroupId(utxo)
      seriesId = extractSeriesId(utxo)

      currentAddress <- currentAddress(userId)

      _ <- redeemAddressTx(
        userId,
        currentAddress,
        btcAmountLong,
        groupId,
        seriesId
      )

      _ <- proveRedeemAddressTx(userId)

      _ <- broadcastFundRedeemAddressTx(userRedeemTxProved(userId))

      _ <- List.fill(8)(mintPlasmaBlock(1, 1)).sequence

      _ <- getCurrentUtxosFromAddress(userId, currentAddress)
        .iterateUntil(_.contains("Asset"))
      _ <- bitcoinMintingQueue.offer((newAddress, userBitcoinWallet(userId), 2))

      _ <- checkMintingStatus(sessionResponse.sessionID)
        .flatMap(x =>
          bitcoinMintingQueue.offer((newAddress, userBitcoinWallet(userId), 1))
          >> warn"x.mintingStatus = ${x.mintingStatus}" >> IO
            .sleep(3.second) >> IO.pure(x)
        )
        .iterateUntil(
          _.mintingStatus == "PeginSessionStateSuccessfulPegin"
        )
      _ <-
        info"User ${userId} -  Session ${sessionResponse.sessionID} was successfully removed"
    } yield 1

    assertIO(
      for {
        _ <- deleteOutputFiles(numberOfSessions)
        _ <- pwd
        _ <- mintPlasmaBlock(
          1,
          5
        )

        bitcoinMintingQueue <- Queue.unbounded[IO, (String, String, Int)]
        _                   <- IO.asyncForIO.start(runBitcoinMintingStream(bitcoinMintingQueue).compile.drain)

        // initialize bitcoin wallets and fund them by minting blocks
        bitcoinWallets <- (1 to numberOfSessions).toList.parTraverse { id =>
          for {
            bitcoinWallet <- initBitcoinWalletById(id, bitcoinMintingQueue)
          } yield bitcoinWallet
        }

        newAddress <- getNewAddress
        _          <- bitcoinMintingQueue.offer((newAddress, "testwallet", 100))

        // request a session for each user
        sessionResponses <- bitcoinWallets.parTraverse { bitcoinResponse =>
          for {
            _ <- info"User ${bitcoinResponse._1} - Getting session"
            (id, newAddress) = bitcoinResponse
            sessionResponse <- getSessionById(id)
          } yield (id, newAddress, sessionResponse)
        }

        bitcoinTransactionResponses <- sendBitcoinTransactions(sessionResponses, bitcoinMintingQueue)

        successfulSessions <- bitcoinTransactionResponses.parTraverse { response =>
          (for {
            _ <- info"Tracking pegin session for User ${response._1}"
            (id, newAddress, sessionResponse, _, btcAmount, btcAmountLong, _, _) = response
            _ <- trackPeginSession(id, sessionResponse, newAddress, btcAmountLong, bitcoinMintingQueue)
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
