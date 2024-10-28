package org.plasmalabs.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait SuccessfulPeginWithConcurrentSessionsModule {

  // self BridgeIntegrationSpec
  self: BridgeIntegrationSpec =>

  def successfulPeginWithConcurrentSessions(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    def successfulPeginForSession (id: Int) : IO[Unit] = {
      import cats.implicits._
      for {
        _ <- pwd
        _ <- mintStrataBlock(
          1,
          1
        ) // this will update the current topl height on the node, node should not work without this
        _                <- initStrataWallet(id)
        _                <- addFellowship(id)
        _                <- addSecret(id)
        newAddress       <- getNewAddress
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(id)
        _ <- addTemplate(
          id,
          shaSecretMap(id),
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        )
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _           <- sendTransaction(signedTxHex)
        _           <- IO.sleep(5.second)
        _           <- generateToAddress(id, 8, newAddress)
        mintingStatusResponse <-
          (for {
            status <- checkMintingStatus(startSessionResponse.sessionID)
            _      <- info"Current minting status: ${status.mintingStatus}"
            _      <- mintStrataBlock(1, 1)
            _      <- generateToAddress(id, 1, newAddress)
            _      <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(_.mintingStatus == "PeginSessionStateMintingTBTC")
        _ <- createVkFile(vkFile)
        _ <- importVks(id)
        _ <- fundRedeemAddressTx(id, mintingStatusResponse.address)
        _ <- proveFundRedeemAddressTx(
          id,
          s"fundRedeemTx${id}.pbuf",
          s"fundRedeemTxProved${id}.pbuf"
        )
        _ <- broadcastFundRedeemAddressTx(s"fundRedeemTxProved${id}.pbuf")
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
          s"redeemTx${id}.pbuf",
          s"redeemTxProved${id}.pbuf"
        )
        _ <- broadcastFundRedeemAddressTx(s"redeemTxProved${id}.pbuf")
        _ <- List.fill(8)(mintStrataBlock(1, 1)).sequence
        _ <- getCurrentUtxosFromAddress(1, currentAddress)
          .iterateUntil(_.contains("Asset"))
        _ <- generateToAddress(id, 3, newAddress)
        _ <- checkMintingStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            generateToAddress(
              id,
              1,
              newAddress
            ) >> warn"x.mintingStatus = ${x.mintingStatus}" >> IO
              .sleep(5.second) >> IO.pure(x)
          )
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateSuccessfulPegin"
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} was successfully removed"
      } yield ()
    
    }

    assertIO(
      for {
        _ <- (1 to numberOfSessions).toList.parTraverse {
        sessionId => successfulPeginForSession(sessionId)
      }} yield (),
      ()
    )
  }

}
