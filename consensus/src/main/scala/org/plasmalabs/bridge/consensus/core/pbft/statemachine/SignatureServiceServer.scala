package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.IO
import com.google.protobuf.ByteString
import io.grpc.Metadata
import org.plasmalabs.bridge.consensus.service.{GetSignatureRequest, SignatureMessage, SignatureServiceFs2Grpc}
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector
import org.typelevel.log4cats.syntax._
import io.grpc.{Metadata, ServerServiceDefinition}
import cats.effect.kernel.{Async, Resource, Sync}
import cats.implicits._

case class InternalSignature(
  machineId:     Int,
  signatureData: ByteVector,
  timestamp:     Long
)

object SignatureServiceServer {

  def signatureGrpcServiceServer[F[_]: Async: Logger](
    allowedPeers: Set[Int]
  ): Resource[F, ServerServiceDefinition] =
    SignatureServiceFs2Grpc.bindServiceResource(
      serviceImpl = new SignatureServiceFs2Grpc[F, Metadata] {

        private def getSignatureAux[F[_]: Async: Logger](
          request: GetSignatureRequest
        ) = {
          println(s"Someone is trying to get our signatures with request: ${request}")
          for {
            result <-
              if (allowedPeers.contains(request.replicaId)) {
                // TODO: DB connection calls
                Async[F].pure(
                  SignatureMessage(
                    machineId = 0,
                    signatureData = ByteString.empty,
                    timestamp = 1L
                  )
                )
              } else {
                Async[F].pure(
                  SignatureMessage(
                    machineId = 0,
                    signatureData = ByteString.empty,
                    timestamp = 1L
                  )
                )
              }
          } yield result
        }

        override def getSignature(
          request: GetSignatureRequest,
          ctx:     Metadata
        ): F[SignatureMessage] =
          getSignatureAux[F](request)
      }
    )
}
