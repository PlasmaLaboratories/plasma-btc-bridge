package xyz.stratalab.bridge

import cats.effect.IO
import xyz.stratalab.bridge.shared.{TimeoutError, UnknownError}

trait FailedPeginNonPrimaryFailureModule { self: BridgeIntegrationSpec =>
  val errorMessage = "Timeout occurred!"

  def failedPeginNonPrimaryFailure(): IO[Unit] = {

    (for {
      // Kill replicas before test
      _ <- killFiber(1)
      _ <- killFiber(2)
      _ <- killFiber(3)

      // Run test
      result <- (for {
        _ <- getNewAddress
        startSessionResponse <- startSession(1).handleErrorWith {
          case _: TimeoutError => IO.pure(Left(TimeoutError(errorMessage)))
        }
        result <- startSessionResponse match {
          case Left(TimeoutError(x)) => IO.pure(x)
          case Right(_) => IO.pure(errorMessage + errorMessage)
        }
        _ <- IO.raiseError(UnknownError("Shouldn't happen!"))
      } yield result).handleError(_ => errorMessage)

      // Assert result
      _ <- assertIO(IO.pure(result), errorMessage)
    } yield ())
      .guarantee(
        // Restore replicas after test
        restoreFiber(1) >>
        restoreFiber(2) >>
        restoreFiber(3)
      )
  }
}