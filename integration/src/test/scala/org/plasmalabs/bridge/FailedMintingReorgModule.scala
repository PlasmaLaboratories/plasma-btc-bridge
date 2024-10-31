package org.plasmalabs.bridge

import cats.effect.IO

import scala.concurrent.duration._

trait FailedMintingReorgModule {

  self: BridgeIntegrationSpec =>

  def failedMintingReorgModule(): IO[Unit] = {
    import org.typelevel.log4cats.syntax._
    import cats.implicits._

    assertIO(
      for {
        _                    <- mintStrataBlock(1, 1)
        bridgeNetworkAndName <- computeBridgeNetworkName
        _                    <- pwd
        _                    <- initStrataWallet(2)
        _                    <- addFellowship(2)
        secret                    <- addSecret(2)
        newAddress           <- getNewAddress
        pkey <- getPKey 

        _                    <- generateToAddress(1, 1, newAddress)
        txIdAndBTCAmount     <- extractGetTxIdAndAmount()
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(pkey, shaSecretMap(1))
        _ <- addTemplate(
          2,
          secret,
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        )
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        // disconnect
        _ <- disconnectBridge(bridgeNetworkAndName._2, "node02")
        _ <- info"Disconnected bridge: ${bridgeNetworkAndName._2}"
        _ <- sendTransaction(signedTxHex)
        _ <- generateToAddress(1, 8, newAddress)
        _ <- List
          .fill(5)(for {
            _ <- mintStrataBlockDocker(1, 1)
            _ <- IO.sleep(1.second)
          } yield ())
          .sequence
        _ <- info"Session ${startSessionResponse.sessionID} went to PeginSessionMintingTBTCConfirmation"
        _ <- List.fill(10)(mintStrataBlockDocker(2, 1)).sequence
        _ <- connectBridge(bridgeNetworkAndName._2, "node02")
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _      <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateMintingTBTC"
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} went back to PeginSessionWaitingForClaim again"
      } yield (),
      ()
    )
  }

}
