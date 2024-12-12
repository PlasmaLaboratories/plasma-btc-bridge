package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import com.google.protobuf.ByteString
import io.grpc.{Metadata, ServerServiceDefinition}
import org.plasmalabs.bridge.consensus.core.PeginWalletManager
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.WaitingForRedemptionOps
import org.plasmalabs.bridge.consensus.service.{
  GetSignatureRequest,
  InvalidInputRes,
  OutOfBandServiceFs2Grpc,
  SignatureMessage,
  SignatureMessageReply
}
import org.plasmalabs.bridge.shared.BridgeCryptoUtils
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.security.PublicKey

case class OutOfBandSignature(
  txId:      String,
  signature: String,
  timestamp: Long
)

trait OutOfBandServiceServer[F[_]] {

  /**
   * Retrieves a signature for a given transaction ID.
   *
   * @param request request input for the signature
   */
  def deliverSignature(request: GetSignatureRequest, ctx: Metadata): F[SignatureMessageReply]
}

object OutOfBandServiceServer {

  /**
   * Retrieves a signature for a given transaction ID.
   *
   * @param allowerPeers The unique identifiers of the replicas, has to be set to 0, 1, 2 ... n
   * @param replicaId The ID of the replica creating this service
   * if the set of the allowed peers differs, requests from replicas not in the set will not execute correctly.
   */
  def make[F[_]: Async: Logger](
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    peginWalletManager: PeginWalletManager[F]
  ): Resource[F, ServerServiceDefinition] =
    OutOfBandServiceFs2Grpc.bindServiceResource(
      serviceImpl = new OutOfBandServiceFs2Grpc[F, Metadata] {

        val defaultSignature = SignatureMessageReply(
          walletId = -1,
          bytesToSign = "",
          result = SignatureMessageReply.Result.InvalidInput(
            InvalidInputRes("Something went wrong")
          )
        )

        import org.typelevel.log4cats.syntax._

        override def deliverSignature(
          request: GetSignatureRequest,
          ctx:     Metadata
        ): F[SignatureMessageReply] =
          for {
            isValidSignature <- BridgeCryptoUtils.verifyBytes(
              publicKey = replicaKeysMap(request.replicaId),
              BigInt(request.replicaId).toByteArray ++ BigInt(request.walletId).toByteArray,
              signature = request.signature.toByteArray
            )

            _ <- info"I validated with with public key ${replicaKeysMap(request.replicaId).getFormat}"

            result <-
              if (isValidSignature) {
                (for {
                  signatureEC <- WaitingForRedemptionOps
                    .signTx(request.walletId, ByteVector.fromHex(request.bytesToSign).getOrElse(ByteVector.empty))
                } yield SignatureMessageReply(
                  walletId = request.walletId,
                  bytesToSign = request.bytesToSign,
                  result = SignatureMessageReply.Result.SignatureMessage(
                    SignatureMessage(replicaId = request.replicaId, ByteString.fromHex(signatureEC.hex))
                  )
                )).handleErrorWith { _ =>
                  Async[F].pure(defaultSignature)
                }
              } else {
                Async[F].pure(defaultSignature)
              }
          } yield result
      }
    )
}
