package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.IO
import cats.effect.kernel.Sync
import com.google.protobuf.ByteString
import io.grpc.Metadata
import org.plasmalabs.bridge.consensus.service.{GetSignatureRequest, SignatureMessage, SignatureServiceFs2Grpc}
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

case class InternalSignature(
  machineId:     Int,
  signatureData: ByteVector,
  timestamp:     Long
)

object SignatureServiceServer {

  def signatureGrpcServiceServer(
    allowedPeers: Set[Int]
  )(implicit
    logger: Logger[IO]
  ) =
    SignatureServiceFs2Grpc.bindServiceResource(
      serviceImpl = new SignatureServiceFs2Grpc[IO, Metadata] {

        private def getSignatureAux[F[_]: Sync: Logger](
          request: GetSignatureRequest
        ) = for {
          result <-
            if (allowedPeers.contains(request.replicaId)) {
              // Signature retrieval logic would happen here
              IO.pure(
                SignatureMessage(
                  machineId = 0,
                  signatureData = ByteString.empty,
                  timestamp = 1L
                )
              )
            } else {
              IO.pure(
                SignatureMessage(
                  machineId = 0,
                  signatureData = ByteString.empty,
                  timestamp = 1L
                )
              )
            }
        } yield result

        override def getSignature(
          request: GetSignatureRequest,
          ctx:     Metadata
        ): IO[SignatureMessage] =
          getSignatureAux[IO](request)
      }
    )
}
