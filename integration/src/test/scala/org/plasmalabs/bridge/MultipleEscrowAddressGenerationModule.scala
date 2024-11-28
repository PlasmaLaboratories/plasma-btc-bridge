package org.plasmalabs.bridge

import cats.effect.IO
import org.plasmalabs.bridge.shared.StartPeginSessionResponse
import org.plasmalabs.bridge.mintPlasmaBlock
import fs2.Stream

import scala.concurrent.duration._

trait MultipleEscrowAddressGenerationModule {

  self: BridgeIntegrationSpec =>

  private def mockPlasmaMintingStream: Stream[IO, Unit] =
    Stream
      .eval(
        mintPlasmaBlock(node = 1, nbBlocks = 1)
      )
      .flatMap(_ => Stream.sleep[IO](3.second))
      .repeat

  private def getSessionById(userId: Int): IO[(String, String)] = {
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

    } yield (startSessionResponse.escrowAddress, startSessionResponse.sessionID)
  }

  def multipleCorrectEscrowAddressGeneration(numberOfSessions: Int): IO[Unit] = {
    import cats.implicits._


    // TODO: Once normal pegin passes with multi sig 5 out of 7 
    // peginWallet Manager should increase everytime we start a new session
    assertIO(
      for {
        _ <- deleteOutputFiles(numberOfSessions)
        _ <- pwd
        _ <- IO.asyncForIO.start(mockPlasmaMintingStream.compile.drain)

        successfulSessions <- (1 to numberOfSessions).toList
          .traverse { userId =>
            for {
              _ <- getSessionById(userId)
            } yield 1
          }
      } yield successfulSessions.sum,
      numberOfSessions
    )
  }
}