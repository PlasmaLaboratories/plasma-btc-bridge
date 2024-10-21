package xyz.stratalab.bridge.stubs

import cats.effect.IO
import xyz.stratalab.bridge.consensus.pbft.{
  CheckpointRequest,
  CommitRequest,
  NewViewRequest,
  PrePrepareRequest,
  PrepareRequest,
  ViewChangeRequest
}
import xyz.stratalab.bridge.shared.Empty
import xyz.stratalab.consensus.core.PBFTInternalGrpcServiceClient

class BasePBFTInternalGrpcServiceClient extends PBFTInternalGrpcServiceClient[IO] {

  override def newView(request: NewViewRequest): IO[Empty] = ???

  override def prePrepare(request: PrePrepareRequest): IO[Empty] = ???

  override def prepare(request: PrepareRequest): IO[Empty] = ???

  override def commit(request: CommitRequest): IO[Empty] = ???

  override def checkpoint(request: CheckpointRequest): IO[Empty] = ???

  override def viewChange(request: ViewChangeRequest): IO[Empty] = ???

}
