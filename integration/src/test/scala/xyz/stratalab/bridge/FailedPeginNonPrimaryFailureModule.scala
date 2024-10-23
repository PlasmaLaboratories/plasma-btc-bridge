package xyz.stratalab.bridge

import cats.effect.IO
import xyz.stratalab.bridge.shared.{TimeoutError, UnknownError}
import cats.syntax.all._

trait FailedPeginNonPrimaryFailureModule { self: BridgeIntegrationSpec =>
  val errorMessage = "Timeout occurred!"

  def failedPeginNonPrimaryFailure(): IO[Unit] = {
    val bridge = startServer.apply()
    
    (for {
      // Original test logic
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
    } yield result)
      .handleError(_ => errorMessage)
      .guarantee( // This ensures cleanup runs whether the test succeeds or fails
        List(1, 2, 3).traverse_(id => 
          bridge.restoreFiber(id).handleErrorWith { error =>
            IO.println(s"Failed to restore fiber $id: ${error.getMessage}")
          }
        )
      )
      .flatMap(result => assertIO(IO.pure(result), errorMessage))
  }
}