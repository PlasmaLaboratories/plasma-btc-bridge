package xyz.stratalab.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._
import xyz.stratalab.bridge.checkMintingStatus

trait FailedPeginNonPrimaryFailureModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNonPrimaryFailure(): IO[Unit] = {

    assertIO(
      for {
        _ <- killFiber(1)
        _ <- killFiber(2)
        _ <- killFiber(3)
        newAddress <- getNewAddress
        startSessionResponse <- startSession(1)
        _ <- IO.println("sessionResponse")
        _ <- IO.println(startSessionResponse)
        _ <- generateToAddress(1, 102, newAddress)
        _ <- checkMintingStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            generateToAddress(1, 1, newAddress) >> IO
              .sleep(5.second) >> IO.pure(x)
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