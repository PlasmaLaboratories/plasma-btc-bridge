package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Mutex
import cats.implicits._
import fs2.grpc.syntax.all._
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.plasmalabs.bridge.consensus.service.{GetSignatureRequest, OutOfBandServiceFs2Grpc, SignatureMessage}
import org.plasmalabs.bridge.shared.ReplicaNode
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

trait OutOfBandServiceClient[F[_]] {

  /**
   * Expected Outcome: Request a signature from another replica and return it.
   * @param replicaId
   * For the current request.
   *
   * @param txId
   * Currently this is the txId used for creating the redeem Tx.
   */
  def getSignature(
    replicaId: Int,
    txId:      String
  ): F[Option[SignatureMessage]]
}

object OutOfBandServiceClientImpl {

  def make[F[_]: Async: Logger](
    replicaNodes: List[ReplicaNode[F]]
  ): Resource[F, OutOfBandServiceClient[F]] =
    for {
      mutex <- Resource.eval(Mutex[F])
      idClientList <- (for {
        replicaNode <- replicaNodes
      } yield for {
        channel <-
          (if (replicaNode.backendSecure)
             ManagedChannelBuilder
               .forAddress(replicaNode.outOfBandBackendHost, replicaNode.outOfBandBackendPort)
               .useTransportSecurity()
           else
             ManagedChannelBuilder
               .forAddress(replicaNode.outOfBandBackendHost, replicaNode.outOfBandBackendPort)
               .usePlaintext()).resource[F]
        outOfBandServiceClient <- OutOfBandServiceFs2Grpc.stubResource(channel)
      } yield (replicaNode.id -> outOfBandServiceClient)).sequence
      replicaMap = idClientList.toMap
    } yield new OutOfBandServiceClient[F] {

      def getSignature(
        replicaId: Int,
        txId:      String
      ): F[Option[SignatureMessage]] =
        mutex.lock.surround(
          (for {
            _ <- info"Requesting signature from replica ${replicaId} for tx ${txId}"
            request = GetSignatureRequest(replicaId, txId)
            response <- replicaMap(replicaId)
              .getSignature(request, new Metadata())
              .map[Option[SignatureMessage]](Some(_))
              .handleErrorWith { error =>
                error"Error getting signature from replica $replicaId: ${error.getMessage}" >>
                Async[F].pure(Option.empty[SignatureMessage])
              }
          } yield response)
        )
    }
}
