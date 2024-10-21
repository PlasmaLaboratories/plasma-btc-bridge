package org.plasmalabs.bridge.stubs

import cats.effect.IO
import cats.effect.kernel.{Outcome, Resource}
import org.plasmalabs.bridge.consensus.core.pbft.{RequestIdentifier, RequestStateManager}

import scala.annotation.nowarn

class BaseRequestStateManager extends RequestStateManager[IO] {

  override def createStateMachine(
    requestIdentifier: RequestIdentifier
  ): IO[Unit] = ???

  override def startProcessingEvents(): Resource[IO, IO[Outcome[IO, Throwable, Unit]]] = ???

  @nowarn
  def createStateMachine(viewNumber: Long, sequenceNumber: Long): IO[Unit] =
    IO.unit

}
