package org.plasmalabs.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedRedemptionModule {

  self: BridgeIntegrationSpec =>

  def failedRedemption(): IO[Unit] = {
    import cats.implicits._
    assertIO(
      for {
        newAddress       <- getNewAddress
        _                <- generateToAddress(1, 1, newAddress)
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(1)
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _           <- sendTransaction(signedTxHex)
        _           <- generateToAddress(1, 8, newAddress)
        _           <- mintStrataBlock(1, 5)
        _ <-
          (for {
            status <- checkMintingStatus(startSessionResponse.sessionID)
            _      <- mintStrataBlock(1, 3)
            _      <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")
        _ <- info"We are in the waiting for redemption state"
        _ <- checkMintingStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            List.fill(5)(mintStrataBlock(1, 1)).sequence >> IO
              .sleep(1.second) >> IO.pure(x)
          )
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateTimeout"
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} was successfully removed"
      } yield (),
      ()
    )
  }

}
