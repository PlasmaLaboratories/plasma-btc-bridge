package xyz.stratalab.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedPeginNoDepositWithReorgModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoDepositWithReorg(): IO[Unit] = {

    assertIO(
      for {
        newAddress <- getNewAddress
        _ <- generateToAddress(1, 1, newAddress)
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(1)
        bridgeNetwork <- computeBridgeNetworkName
        // parse
        ipBitcoin02 <- extractIpBtc(2, bridgeNetwork._1)
        // parse
        ipBitcoin01 <- extractIpBtc(1, bridgeNetwork._1)
        _ <- setNetworkActive(2, false)
        _ <- setNetworkActive(1, false)
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _ <- sendTransaction(signedTxHex)
        _ <- generateToAddress(1, 2, newAddress)
        _ <- IO.sleep(2.second)
        _ <- warn"We are in the waiting for escrow confirmation state"
        _ <- generateToAddress(2, 8, newAddress)
        // reconnect network
        _ <- setNetworkActive(2, true)
        _ <- setNetworkActive(1, true)
        // force connection
        _ <- forceConnection(1, ipBitcoin02, 18444)
        _ <- forceConnection(2, ipBitcoin01, 18444)
        _ <- generateToAddress(1, 102, newAddress)
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- IO.sleep(2.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateTimeout"
          )

        _ <- info"Session ${startSessionResponse.sessionID} went back to wait again"
      } yield (),
      ()
    )
  }
}
