package xyz.stratalab.bridge

import cats.effect.IO
import xyz.stratalab.bridge.shared.{TimeoutError, UnknownError}

trait FailedPeginNonPrimaryFailureModule { self: BridgeIntegrationSpec =>
  val errorMessage = "Timeout occurred!"

  def failedPeginNonPrimaryFailure(): IO[Unit] = {
    val  bridgeFixture = startServer.apply() 

    (for {
      // Kill replicas before test
      _ <- bridgeFixture.killFiber(1)
      _ <- bridgeFixture.killFiber(2)
      _ <- bridgeFixture.killFiber(3)

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
        bridgeFixture.restoreFiber(1) >>
        bridgeFixture.restoreFiber(2) >>
        bridgeFixture.restoreFiber(3)
      )
  }
}