package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.Async
import cats.effect.std.Mutex
import cats.implicits._
import fs2.grpc.syntax.all._
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.plasmalabs.bridge.consensus.service.{
  GetSignatureRequest,
  SignatureMessage,
  InternalCommunicationServiceFs2Grpc
}
import org.plasmalabs.bridge.shared.ReplicaNode
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

trait InternalCommunicationServiceClient[F[_]] {

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
  ): F[SignatureMessage]
}

object InternalCommunicationServiceClientImpl {

  def make[F[_]: Async: Logger](
    replicaNodes: List[ReplicaNode[F]],
    mutex:        Mutex[F]
  ) =
    for {
      idClientList <- (for {
        replicaNode <- replicaNodes
      } yield for {
        channel <-
          (if (replicaNode.backendSecure)
             ManagedChannelBuilder
               .forAddress(replicaNode.internalBackendHost, replicaNode.internalBackendPort)
               .useTransportSecurity()
           else
             ManagedChannelBuilder
               .forAddress(replicaNode.internalBackendHost, replicaNode.internalBackendPort)
               .usePlaintext()).resource[F]
        internalCommunicationClient <- InternalCommunicationServiceFs2Grpc.stubResource(channel)
      } yield (replicaNode.id -> internalCommunicationClient)).sequence
      replicaMap = idClientList.toMap
    } yield new InternalCommunicationServiceClient[F] {

      def getSignature(
        replicaId: Int,
        txId:      String
      ): F[SignatureMessage] =
        mutex.lock.surround(
          for {
            _ <- info"Requesting signature from replica ${replicaId} for tx ${txId}"
            request = GetSignatureRequest(replicaId, txId)
            response <- replicaMap(replicaId)
              .getSignature(request, new Metadata())
              .handleErrorWith { error =>
                error"Error getting signature from replica $replicaId: ${error.getMessage}" >>
                SignatureMessage(
                  replicaId = -1, // TODO: maybe return an option/either here
                  signatureData = com.google.protobuf.ByteString.EMPTY,
                  timestamp = 0L
                ).pure[F]
              }
          } yield response
        )
    }
}
