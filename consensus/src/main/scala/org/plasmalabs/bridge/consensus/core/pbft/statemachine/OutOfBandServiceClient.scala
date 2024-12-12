package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Mutex
import cats.implicits._
import com.google.protobuf.ByteString
import fs2.grpc.syntax.all._
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.plasmalabs.bridge.consensus.service.{GetSignatureRequest, OutOfBandServiceFs2Grpc, SignatureMessageReply}
import org.plasmalabs.bridge.shared.{BridgeCryptoUtils, ReplicaNode}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scodec.bits.ByteVector

import java.security.KeyPair

trait OutOfBandServiceClient[F[_]] {

  /**
   * Expected Outcome: Request a signature from another replica and return it.
   * @param replicaId
   * For the current request.
   *
   * @param walletId
   * Current Index of wallet
   *
   * @param bytesToSign
   * Bytes the replica is asked to provide a signature for
   */
  def getSignature(
    replicaId:   Int,
    walletId:    Int,
    bytesToSign: ByteVector
  ): F[Option[SignatureMessageReply]]
}

object OutOfBandServiceClientImpl {

  def make[F[_]: Async: Logger](
    replicaNodes: List[ReplicaNode[F]],
    keyPair:      KeyPair
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
        replicaId:   Int,
        walletId:    Int,
        bytesToSign: ByteVector
      ): F[Option[SignatureMessageReply]] =
        mutex.lock.surround(
          for {
            signedBytes <- BridgeCryptoUtils.signBytes(
              keyPair.getPrivate(),
              BigInt(replicaId).toByteArray ++ BigInt(walletId).toByteArray
            )

            request = GetSignatureRequest(
              replicaId,
              walletId,
              bytesToSign.toHex,
              signature = ByteString.copyFrom(signedBytes)
            )

            response <- replicaMap(replicaId)
              .deliverSignature(request, new Metadata())
              .map[Option[SignatureMessageReply]](Some(_))
              .handleErrorWith { error =>
                error"Error getting signature from replica $replicaId: ${error.getMessage}" >>
                Async[F].pure(Option.empty[SignatureMessageReply])
              }
          } yield response
        )
    }
}
