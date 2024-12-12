package org.plasmalabs.bridge

import cats.effect.IO
import org.plasmalabs.bridge.shared.StartPeginSessionResponse

import scala.concurrent.duration._
import org.typelevel.log4cats.syntax._

trait MultipleEscrowAddressGenerationModule {

  self: BridgeIntegrationSpec =>

  private def getSessionEscrowAddressById(userId: Int): IO[String] = {
    def retry(userSecret: String): IO[StartPeginSessionResponse] =
      (for {
        startSessionResponse <- startSession(userSecret, port = 5000 + (userId % 7) * 2)
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

    } yield startSessionResponse.escrowAddress
  }

  def multipleCorrectEscrowAddressGeneration(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._

    assertIO(
      for {
        _ <- deleteOutputFiles(numberOfSessions)
        _ <- pwd

        escrowAddresses <- (1 to numberOfSessions).toList
          .parTraverse { userId =>
            for {
              escrowAddress <- getSessionEscrowAddressById(userId)
            } yield escrowAddress
          }
        _ <- info"Should have received ${numberOfSessions} different escrow addresses: ${escrowAddresses}"
      } yield escrowAddresses.toSet.size,
      numberOfSessions
    )
  }
}