package org.plasmalabs.bridge

import cats.effect.IO
import org.plasmalabs.bridge.checkMintingStatus
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedPeginNoDepositModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoDeposit(): IO[Unit] =
    assertIO(
      for {
        newAddress           <- getNewAddress
        startSessionResponse <- startSession()
        _                    <- generateToAddress(1, 102, newAddress)
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
        _ <-
          searchLogs(sessionStateTransitionLogNeedles(startSessionResponse.sessionID)).assertEquals(Set.empty[String])
      } yield (),
      ()
    )

  private def sessionStateTransitionLogNeedles(sessionID: String): Set[String] =
    List("consensus-00", "consensus-01", "consensus-02", "consensus-03", "consensus-04", "consensus-05", "consensus-06")
      .flatMap(instanceName =>
        List(
          s"$instanceName - Session $sessionID ended successfully",
        )
      )
      .toSet
}
