package co.topl.bridge.consensus.core.pbft

import cats.effect.kernel.Async
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.pbft.activities.CommitActivity
import co.topl.bridge.consensus.core.pbft.activities.PrePrepareActivity
import co.topl.bridge.consensus.core.pbft.activities.PrepareActivity
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import org.typelevel.log4cats.Logger

import java.security.PublicKey

trait PBFTRequestPreProcessor[F[_]] {

  def preProcessRequest(request: PrePrepareRequest): F[Unit]
  def preProcessRequest(request: PrepareRequest): F[Unit]
  def preProcessRequest(request: CommitRequest): F[Unit]

}

object PBFTRequestPreProcessorImpl {

  def make[F[_]: Async: Logger](
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      requestTimerManager: RequestTimerManager[F],
      requestStateManager: RequestStateManager[F],
      currentViewRef: CurrentViewRef[F],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      storageApi: StorageApi[F],
      replica: ReplicaId,
      replicaCount: ReplicaCount
  ): PBFTRequestPreProcessor[F] = new PBFTRequestPreProcessor[F] {

    import org.typelevel.log4cats.syntax._
    import cats.implicits._

    override def preProcessRequest(request: PrePrepareRequest): F[Unit] = {
      for {
        timerExpired <- requestTimerManager.hasExpiredTimer()
        _ <-
          if (timerExpired)
            error"Cannot process pre-prepare request, timer expired"
          else
            PrePrepareActivity(
              request
            )(replicaKeysMap)
      } yield ()
    }

    override def preProcessRequest(request: PrepareRequest): F[Unit] =
      for {
        timerExpired <- requestTimerManager.hasExpiredTimer()
        _ <-
          if (timerExpired)
            error"Cannot process prepare request, timer expired"
          else
            PrepareActivity(
              request
            )(replicaKeysMap)
      } yield ()
    override def preProcessRequest(request: CommitRequest): F[Unit] =
      for {
        timerExpired <- requestTimerManager.hasExpiredTimer()
        _ <-
          if (timerExpired)
            error"Cannot process commit request, timer expired"
          else
            CommitActivity(
              request
            )(replicaKeysMap)
      } yield ()
  }
}
