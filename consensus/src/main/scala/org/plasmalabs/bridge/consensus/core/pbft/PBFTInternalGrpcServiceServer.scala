package org.plasmalabs.bridge.consensus.core.pbft

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import io.grpc.{Metadata, ServerServiceDefinition}
import org.plasmalabs.bridge.consensus.core.pbft.activities.CheckpointActivity
import org.plasmalabs.bridge.consensus.core.{KWatermark, WatermarkRef}
import org.plasmalabs.bridge.consensus.pbft.{
  CheckpointRequest,
  CommitRequest,
  NewViewRequest,
  PBFTInternalServiceFs2Grpc,
  PrePrepareRequest,
  PrepareRequest,
  ViewChangeRequest
}
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.shared.{Empty, ReplicaCount}
import org.typelevel.log4cats.Logger

import java.security.PublicKey

object PBFTInternalGrpcServiceServer {

  def pbftInternalGrpcServiceServerAux[F[_]: Async: Logger](
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    checkpointManager: CheckpointManager[F],
    pbftReqProcessor:  PBFTRequestPreProcessor[F],
    watermarkRef:      WatermarkRef[F],
    kWatermark:        KWatermark,
    storageApi:        StorageApi[F],
    replicaCount:      ReplicaCount
  ) = new PBFTInternalServiceFs2Grpc[F, Metadata] {

    override def newView(request: NewViewRequest, ctx: Metadata): F[Empty] = ???

    override def viewChange(
      request: ViewChangeRequest,
      ctx:     Metadata
    ): F[Empty] = pbftReqProcessor.preProcessRequest(request) >> Empty().pure[F]

    override def prePrepare(
      request: PrePrepareRequest,
      ctx:     Metadata
    ): F[Empty] =
      pbftReqProcessor.preProcessRequest(request) >> Empty().pure[F]

    override def prepare(
      request: PrepareRequest,
      ctx:     Metadata
    ): F[Empty] = pbftReqProcessor.preProcessRequest(request) >> Empty().pure[F]

    override def checkpoint(
      request: CheckpointRequest,
      ctx:     Metadata
    ): F[Empty] =
      CheckpointActivity(
        replicaKeysMap,
        request
      )

    override def commit(request: CommitRequest, ctx: Metadata): F[Empty] =
      pbftReqProcessor.preProcessRequest(request) >> Empty().pure[F]

  }

  def pbftInternalGrpcServiceServer[F[_]: Async: Logger](
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    checkpointManager: CheckpointManager[F],
    pbftReqProcessor:  PBFTRequestPreProcessor[F],
    watermarkRef:      WatermarkRef[F],
    kWatermark:        KWatermark,
    storageApi:        StorageApi[F],
    replicaCount:      ReplicaCount
  ): Resource[F, ServerServiceDefinition] =
    PBFTInternalServiceFs2Grpc.bindServiceResource(
      pbftInternalGrpcServiceServerAux(
        replicaKeysMap
      )
    )
}
