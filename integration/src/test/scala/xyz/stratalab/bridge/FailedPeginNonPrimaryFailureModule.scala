package xyz.stratalab.bridge

import cats.effect.IO
import xyz.stratalab.bridge.shared.{TimeoutError, UnknownError}

trait FailedPeginNonPrimaryFailureModule { self: BridgeIntegrationSpec =>
  val errorMessage = "Timeout occurred!"

  def failedPeginNonPrimaryFailure(): IO[Unit] = {
    assertIO(
      (for {
        bridge <- IO(startServer.apply()) // Get the bridge fixture

        _ <- bridge.killFiber(1)
        _ <- bridge.killFiber(2)
        _ <- bridge.killFiber(3)
        _ <- getNewAddress
        startSessionResponse <- startSession(1).handleErrorWith {
          case _: TimeoutError => IO.pure(Left(TimeoutError(errorMessage)))
        }
        result <- startSessionResponse match {
          case Left(TimeoutError(x)) => IO.pure(x)
          case Right(_) => IO.pure(errorMessage + errorMessage)
        }
        _ <- IO.raiseError(UnknownError("Shouldn't happen!"))
      } yield result).handleError(_ => errorMessage),
      errorMessage
    )
  }

}