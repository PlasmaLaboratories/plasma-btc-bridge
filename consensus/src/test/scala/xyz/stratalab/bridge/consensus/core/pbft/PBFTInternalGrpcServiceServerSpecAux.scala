package xyz.stratalab.bridge.consensus.core.pbft

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import xyz.stratalab.bridge.consensus.core.PublicApiClientGrpcMap
import xyz.stratalab.bridge.consensus.core.WatermarkRef
import xyz.stratalab.bridge.consensus.shared.persistence.StorageApi
import xyz.stratalab.bridge.consensus.shared.utils.ConfUtils._
import xyz.stratalab.bridge.stubs.BaseRequestStateManager
import xyz.stratalab.bridge.stubs.BaseRequestTimerManager
import org.typelevel.log4cats.Logger
import java.security.KeyPair
import xyz.stratalab.bridge.stubs.BasePBFTInternalGrpcServiceClient
import xyz.stratalab.bridge.consensus.core.pbft.{PBFTInternalGrpcServiceServer, PBFTRequestPreProcessorImpl, PBFTInternalEvent, CheckpointManagerImpl, ViewManagerImpl}

trait PBFTInternalGrpcServiceServerSpecAux extends SampleData {

  def createSimpleInternalServer(
      replicaKeyPair: KeyPair
  )(implicit
      storageApi: StorageApi[IO],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      logger: Logger[IO]
  ) = {
    implicit val pbftInternalGrpcServiceClient =
      new BasePBFTInternalGrpcServiceClient()
    implicit val rquestStateManager = new BaseRequestStateManager()
    implicit val requestTimerManager = new BaseRequestTimerManager()
    for {
      checkpointManager <- CheckpointManagerImpl.make[IO]().toResource
      replicaKeysMap <- createReplicaPublicKeyMap[IO](conf).toResource
      lowAndHigh <- Ref.of[IO, (Long, Long)]((0L, 0L)).toResource
      viewManager <- ViewManagerImpl
        .make[IO](
          replicaKeyPair,
          5,
          storageApi,
          checkpointManager,
          requestTimerManager
        )
        .toResource
      queue <- Queue.unbounded[IO, PBFTInternalEvent].toResource
    } yield {
      implicit val iCheckpointManager = checkpointManager
      implicit val pbftReqProcessor = PBFTRequestPreProcessorImpl
        .make[IO](
          queue,
          viewManager,
          replicaKeysMap
        )
      implicit val watermarkRef = new WatermarkRef(lowAndHigh)
      PBFTInternalGrpcServiceServer.pbftInternalGrpcServiceServerAux[IO](
        replicaKeysMap
      )
    }
  }
}
