package org.plasmalabs.bridge

import cats.effect.IO
import cats.effect.std.Queue
import org.plasmalabs.bridge.shared.StartPeginSessionResponse
import org.plasmalabs.bridge.{getCurrentUtxosFromAddress, getNewAddress, mintPlasmaBlock, userBitcoinWallet}
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait SuccessfulPeginHighVolumeModule {

  self: BridgeIntegrationSpec =>

  def successfulPeginHighVolume(numberOfSessions: Int): IO[Unit] = {
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

    def sendBitcoinTransaction(
      userId: Int, 
      newAddress: String, 
      sessionResponse:    StartPeginSessionResponse,
      bitcoinMintingQueue: Queue[IO, (String, String, Int)]
    ) =
      for {
          txIdAndBTCAmount <- extractGetTxIdAndAmount(walletName = userBitcoinWallet(userId))
          (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount

          bitcoinTx <- createTx(
            txId,
            sessionResponse.escrowAddress,
            btcAmount
          )
          signedTxHex <- signTransaction(bitcoinTx, userBitcoinWallet(userId))

            _ <- sendTransaction(signedTxHex)

        _ <- IO.sleep(1.second)

        _          <- bitcoinMintingQueue.offer((newAddress, userBitcoinWallet(userId), 8))

      } yield (userId, sessionResponse, newAddress, btcAmountLong)

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
          _      <- IO.sleep(1.second)

        } yield status)
          .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")

        _ <- info"User ${userId} - Session correctly went to minting tbtc after receiving BTC Deposit"

        _ <- (for{
          utxos <- getCurrentUtxosFromAddress(userId, mintingStatusResponse.address)
          value = extractValue(utxos)
          _ <- info"There are utxos with value ${value} at minting response address and are expecting ${btcAmountLong}"
          _      <- mintPlasmaBlock(node = 1, nbBlocks = 1)
          _      <- bitcoinMintingQueue.offer((newAddress, userBitcoinWallet(userId), 1))
          _ <- IO.sleep(1.second)
        } yield value.toLong).iterateUntil(_ == btcAmountLong)

        _ <- info"User ${userId} - UTXOs at minting response address have the expected value. Removing the Session. "

    } yield 1

    assertIO(
      for {
        _ <- deleteOutputFiles(numberOfSessions)
        _ <- pwd
        _ <- mintPlasmaBlock(
          1,
          3
        )

        bitcoinMintingQueue <- Queue.unbounded[IO, (String, String, Int)]
        _                   <- IO.asyncForIO.start(runBitcoinMintingStream(bitcoinMintingQueue).compile.drain)

        // initialize bitcoin wallets and fund them by minting blocks
        bitcoinWallets <- (1 to numberOfSessions).toList.traverse { id =>
          for {
            bitcoinWallet <- initBitcoinWalletById(id, bitcoinMintingQueue)
          } yield bitcoinWallet
        }

        // make funds accessible by minting 100 blocks
        newAddress <- getNewAddress
        _          <- bitcoinMintingQueue.offer((newAddress, "testwallet", 100))

        // request a session for each user
        successfulSessions <- bitcoinWallets
          .grouped(30)
          .toList
          .traverse { batch =>
            batch.parTraverse { bitcoinResponse =>
              for {
                _ <- info"Getting session for User ${bitcoinResponse._1}"
                sessionResponse <- getSessionById(bitcoinResponse._1)
                (userId, newAddress) = bitcoinResponse

                txResult <- sendBitcoinTransaction(userId, newAddress, sessionResponse, bitcoinMintingQueue)
                (_, _, _, btcAmountLong) = txResult
                trackingResult <- trackPeginSession(userId, sessionResponse, newAddress, btcAmountLong, bitcoinMintingQueue)

              } yield trackingResult
            }
          }
          .map(_.flatten)

      } yield successfulSessions.sum,
      numberOfSessions
    )
  }
}
