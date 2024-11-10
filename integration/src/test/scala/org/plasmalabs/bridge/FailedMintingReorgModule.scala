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
        _                    <- mintPlasmaBlock(1, 1)
        bridgeNetworkAndName <- computeBridgeNetworkName
        _                    <- pwd
        _                    <- initPlasmaWallet(2)
        _                    <- addFellowship(2)
        secret               <- addSecret(2)
        newAddress           <- getNewAddress
        _                    <- generateToAddress(1, 1, newAddress)
        txIdAndBTCAmount     <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(secret)
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
            _ <- mintPlasmaBlockDocker(1, 1)
            _ <- IO.sleep(1.second)
          } yield ())
          .sequence
        _ <- info"Session ${startSessionResponse.sessionID} went to PeginSessionMintingTBTCConfirmation"
        _ <- mintPlasmaBlockDocker(
          2,
          100
        ) // (changed to 100 because of new recover threshold) TODO: does this have to be done with List...sequence()?
        _ <- connectBridge(bridgeNetworkAndName._2, "node02")
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _      <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateMintingTBTC" // TODO: is this correct? Shouldn't we wait for PeginSessionWaitingForClaim instead?
          )

        // TODO: Reset Wallet state
        _ <-
          info"Session ${startSessionResponse.sessionID} went back to PeginSessionWaitingForClaim again"
      } yield (),
      ()
    )
  }

}
