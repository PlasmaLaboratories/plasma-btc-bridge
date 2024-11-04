package org.plasmalabs.bridge.stubs

import cats.effect.IO
import org.plasmalabs.bridge.consensus.pbft.{
  CheckpointRequest,
  CommitRequest,
  NewViewRequest,
  PrePrepareRequest,
  PrepareRequest,
  ViewChangeRequest
}
import org.plasmalabs.bridge.shared.Empty
import org.plasmalabs.bridge.consensus.core.PBFTInternalGrpcServiceClient

class BasePBFTInternalGrpcServiceClient extends PBFTInternalGrpcServiceClient[IO] {

  override def newView(request: NewViewRequest): IO[Empty] = ???

  override def prePrepare(request: PrePrepareRequest): IO[Empty] = ???

  override def prepare(request: PrepareRequest): IO[Empty] = ???

  override def commit(request: CommitRequest): IO[Empty] = ???

  override def checkpoint(request: CheckpointRequest): IO[Empty] = ???

  override def viewChange(request: ViewChangeRequest): IO[Empty] = ???

}
