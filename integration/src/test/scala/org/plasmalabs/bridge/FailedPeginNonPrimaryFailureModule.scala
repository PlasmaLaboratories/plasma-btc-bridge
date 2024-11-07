package org.plasmalabs.bridge

import cats.effect.IO
import org.plasmalabs.bridge.shared.{TimeoutError, UnknownError}

trait FailedPeginNonPrimaryFailureModule { self: BridgeIntegrationSpec =>
  val errorMessage = "Timeout occurred!"

  def failedPeginNonPrimaryFailure(): IO[Unit] =
    (for {
      _ <- killFiber(1)
      _ <- killFiber(2)
      _ <- killFiber(3)
      result <- (for {
        _ <- getNewAddress
        startSessionResponse <- startSession(1).handleErrorWith { case _: TimeoutError =>
          IO.pure(Left(TimeoutError(errorMessage)))
        }
        result <- startSessionResponse match {
          case Left(TimeoutError(x)) => IO.pure(x)
          case Right(_)              => IO.pure(errorMessage + errorMessage)
        }
        _ <- IO.raiseError(UnknownError("Shouldn't happen!"))
      } yield result).handleError(_ => errorMessage)

      // Assert result
      _ <- assertIO(IO.pure(result), errorMessage)
    } yield ())
}
