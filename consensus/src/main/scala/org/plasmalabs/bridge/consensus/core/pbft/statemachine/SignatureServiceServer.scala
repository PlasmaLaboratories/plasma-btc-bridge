package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import com.google.protobuf.ByteString
import io.grpc.{Metadata, ServerServiceDefinition}
import org.plasmalabs.bridge.consensus.service.{GetSignatureRequest, SignatureMessage, SignatureServiceFs2Grpc}
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi

case class InternalSignature(
  txId:      String,
  signature: String,
  timestamp: Long
)

object SignatureServiceServer {

  def signatureGrpcServiceServer[F[_]: Async](
    allowedPeers: Set[Int],
    replicaId:    Int
  )(implicit
    storageApi: StorageApi[F]
  ): Resource[F, ServerServiceDefinition] =
    SignatureServiceFs2Grpc.bindServiceResource(
      serviceImpl = new SignatureServiceFs2Grpc[F, Metadata] {

        val defaultSignature = SignatureMessage(
          replicaId = -1,
          signatureData = ByteString.empty,
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
                  case Some(internalSignature) =>
                    SignatureMessage(
                      replicaId, // TODO: rename to replicaId
                      signatureData = ByteString.fromHex(internalSignature.signature),
                      timestamp = internalSignature.timestamp
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
