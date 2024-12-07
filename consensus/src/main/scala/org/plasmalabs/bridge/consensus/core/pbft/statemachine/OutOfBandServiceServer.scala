package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import com.google.protobuf.ByteString
import io.grpc.{Metadata, ServerServiceDefinition}
import org.plasmalabs.bridge.consensus.service.{GetSignatureRequest, OutOfBandServiceFs2Grpc, SignatureMessage}
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi

case class OutOfBandSignature(
  txId:      String,
  signature: String,
  timestamp: Long
)

trait OutOfBandServiceServer[F[_]] {

  /**
   * Retrieves a signature for a given transaction ID.
   *
   * @param txId The unique identifier of the transaction
   * @param replicaId The ID of the replica requesting the signature
   * @return The signature message if found and the requesting replica is authorized,
   *         otherwise returns a default empty signature
   */
  def getSignature(txId: String, replicaId: Int): F[SignatureMessage]
}

object OutOfBandServiceServer {

  def make[F[_]: Async](
    allowedPeers: Set[Int], // TODO: Secure method to authorize other replicas
    replicaId:    Int
  )(implicit
    storageApi: StorageApi[F]
  ): Resource[F, ServerServiceDefinition] =
    OutOfBandServiceFs2Grpc.bindServiceResource(
      serviceImpl = new OutOfBandServiceFs2Grpc[F, Metadata] {

        val defaultSignature = SignatureMessage(
          replicaId = -1,
          signature = ByteString.empty,
          timestamp = 1L
        )

        private def getSignatureAux(
          request: GetSignatureRequest
        ) =
          for {
            result <-
              if (allowedPeers.contains(request.replicaId)) {
                for {
                  result <- storageApi.getSignature(request.txId)
                } yield result match {
                  case Some(signature) =>
                    SignatureMessage(
                      replicaId,
                      signature = ByteString.fromHex(signature.signature),
                      timestamp = signature.timestamp
                    )
                  case None => defaultSignature
                }
              } else {
                Async[F].pure(defaultSignature)
              }

          } yield result

        override def getSignature(
          request: GetSignatureRequest,
          ctx:     Metadata
        ): F[SignatureMessage] =
          getSignatureAux(request)
      }
    )
}
