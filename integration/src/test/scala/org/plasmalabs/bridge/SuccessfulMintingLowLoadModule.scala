package org.plasmalabs.bridge

import cats.effect.IO
import cats.effect.std.Queue
import org.plasmalabs.bridge.shared.StartPeginSessionResponse
import org.plasmalabs.bridge.{getCurrentUtxosFromAddress, getNewAddress, mintPlasmaBlock, userBitcoinWallet}
import org.typelevel.log4cats.syntax._
import fs2.Stream

import scala.concurrent.duration._

trait SuccessfulMintingLowLoadModule {

  self: BridgeIntegrationSpec =>

  private def initBitcoinWalletById(
    userId:              Int,
    bitcoinMintingQueue: Queue[IO, (String, String, Int)],
    blocksToConfirm:     Int = 7
  ): IO[Unit] = for {
    _          <- initUserBitcoinWallet(userBitcoinWallet(userId))
    newAddress <- getNewAddress(userBitcoinWallet(userId))
    _          <- bitcoinMintingQueue.offer(newAddress, userBitcoinWallet(userId), blocksToConfirm)
  } yield ()

  private def mockBitcoinMintingStream(
    bitcoinMintingQueue: Queue[IO, (String, String, Int)],
    newAddress:          String
  ): Stream[IO, Unit] =
    Stream
      .eval(
        bitcoinMintingQueue.offer((newAddress, "testwallet", 1))
      )
      .flatMap(_ => Stream.sleep[IO](1.second))
      .repeat

  private def mockPlasmaMintingStream: Stream[IO, Unit] =
    Stream
      .eval(
        mintPlasmaBlock(node = 1, nbBlocks = 1)
      )
      .flatMap(_ => Stream.sleep[IO](3.second))
      .repeat

  private def runBitcoinMintingStream(bitcoinMintingQueue: Queue[IO, (String, String, Int)]): Stream[IO, Unit] =
    Stream
      .fromQueueUnterminated(bitcoinMintingQueue)
      .evalMap { request =>
        for {
          _ <- debug"Processing Bitcoin Minting during test"
          (newAddress, walletName, numberOfBlocks) = request
          _ <- generateToAddress(1, numberOfBlocks, newAddress, walletName)
          _ <- IO.sleep(0.5.second)
        } yield ()
      }

  private def getSessionById(userId: Int): IO[(String, String)] = {
    def retry(userSecret: String): IO[StartPeginSessionResponse] =
      (for {
        startSessionResponse <- startSession(userSecret, 5000 + (userId % 7) * 2)
      } yield startSessionResponse).handleErrorWith { _ =>
        IO.sleep(1.second) >> retry(userSecret)
      }

    for {
      _                    <- initPlasmaWallet(userId)
      _                    <- addFellowship(userId)
      userSecret           <- addSecret(userId)
      startSessionResponse <- retry(userSecret)
      _ <- addTemplate(
        userId,
        userSecret,
        startSessionResponse.minHeight,
        startSessionResponse.maxHeight
      )

    } yield (startSessionResponse.escrowAddress, startSessionResponse.sessionID)
  }

  private def sendBitcoinTransaction(
    userId:        Int,
    escrowAddress: String
  ): IO[Long] = {

    def retry(signedTxHex: String): IO[Unit] = (for {
      _ <- sendTransaction(signedTxHex)
    } yield ()).handleErrorWith { _ =>
      IO.sleep(1.second) >> retry(signedTxHex)
    }

    for {
      txIdAndBTCAmount <- extractGetTxIdAndAmount(walletName = userBitcoinWallet(userId))
      (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount

      bitcoinTx <- createTx(
        txId,
        escrowAddress,
        btcAmount
      )
      signedTxHex <- signTransaction(bitcoinTx, userBitcoinWallet(userId))

      _ <- retry(signedTxHex)
    } yield btcAmountLong
  }

  private def trackMinting(
    userId:        Int,
    sessionID:     String,
    btcAmountLong: Long
  ) = for {

    _ <- info"User ${userId} - Tracking minting"
    mintingStatusResponse <-
      (for {
        status <- checkMintingStatus(sessionID, 5000 + (userId % 7) * 2)
        _      <- info"User ${userId} - Current minting status: ${status.mintingStatus}"
        _      <- IO.sleep(3.second)

      } yield status).iterateUntil(response =>
        response.mintingStatus == "PeginSessionStateMintingTBTC" || response.mintingStatus == "PeginSessionStateTimeout"
      )

    _ <- mintingStatusResponse.mintingStatus match {
      case "PeginSessionStateTimeout" =>
        error"User ${userId} - Raising error because session timed out!" >> IO.raiseError(
          new Error("Session timed out")
        )
      case _ => IO.unit
    }
    _ <- info"User ${userId} - Session correctly went to minting tbtc after receiving BTC Deposit"

    _ <- (for {
      utxos <- getCurrentUtxosFromAddress(userId, mintingStatusResponse.address)
      value = extractValue(utxos)
      _ <- IO.sleep(3.second)
    } yield value)
      .handleErrorWith { e =>
        error"User ${userId} - Error during checking if funds arrived ${e.getMessage}" >> IO.pure(-1.toLong)
      }
      .iterateUntil(_ == btcAmountLong)

    _ <- info"User ${userId} - UTXOs at minting response address have the expected value. Removing the Session. "
  } yield 1

  def successfulMintingLowLoad(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    assertIO(
      for {
        _ <- deleteOutputFiles(numberOfSessions)
        _ <- pwd
        _ <- IO.asyncForIO.start(mockPlasmaMintingStream.compile.drain)

        bitcoinMintingQueue <- Queue.unbounded[IO, (String, String, Int)]
        _                   <- IO.asyncForIO.start(runBitcoinMintingStream(bitcoinMintingQueue).compile.drain)

        _ <- (1 to numberOfSessions).toList.traverse { id =>
          for {
            bitcoinWallet <- initBitcoinWalletById(id, bitcoinMintingQueue)
          } yield bitcoinWallet
        }

        newAddress <- getNewAddress
        _          <- bitcoinMintingQueue.offer((newAddress, "testwallet", 100))

        _ <- IO.sleep(2.second)
        _ <- IO.asyncForIO.start(mockBitcoinMintingStream(bitcoinMintingQueue, newAddress).compile.drain)

        successfulSessions <- (1 to numberOfSessions)
          .grouped(5)
          .toList
          .traverse { batch =>
            for {
              batchResult <- batch.toList.parTraverse { userId =>
                for {
                  sessionVariables <- getSessionById(userId)
                  btcAmountLong    <- sendBitcoinTransaction(userId, sessionVariables._1)
                  trackingResult   <- trackMinting(userId, sessionVariables._2, btcAmountLong)

                } yield trackingResult
              }
            } yield batchResult
          }
      } yield successfulSessions.flatten.sum,
      numberOfSessions
    )
  }
}
