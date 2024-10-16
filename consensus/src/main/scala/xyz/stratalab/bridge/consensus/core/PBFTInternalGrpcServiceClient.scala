package xyz.stratalab.consensus.core

import cats.effect.kernel.Async
import com.google.protobuf.ByteString
import fs2.grpc.syntax.all._
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import xyz.stratalab.bridge.consensus.pbft.{
  CheckpointRequest,
  CommitRequest,
  NewViewRequest,
  PBFTInternalServiceFs2Grpc,
  PrePrepareRequest,
  PrepareRequest,
  ViewChangeRequest
}
import xyz.stratalab.bridge.shared.{BridgeCryptoUtils, Empty, ReplicaNode}

import java.security.KeyPair

trait PBFTInternalGrpcServiceClient[F[_]] {

  def prePrepare(
    request: PrePrepareRequest
  ): F[Empty]

  def prepare(
    request: PrepareRequest
  ): F[Empty]

  def commit(
    request: CommitRequest
  ): F[Empty]

  def checkpoint(
    request: CheckpointRequest
  ): F[Empty]

  def viewChange(
    request: ViewChangeRequest
  ): F[Empty]

  def newView(
    request: NewViewRequest
  ): F[Empty]

}

object PBFTInternalGrpcServiceClientImpl {

  import cats.implicits._

  def make[F[_]: Async: Logger](
    keyPair:      KeyPair,
    replicaNodes: List[ReplicaNode[F]]
  ) =
    for {
      idBackupMap <- (for {
        replicaNode <- replicaNodes
      } yield for {
        channel <-
          (if (replicaNode.backendSecure)
             ManagedChannelBuilder
               .forAddress(replicaNode.backendHost, replicaNode.backendPort)
               .useTransportSecurity()
           else
             ManagedChannelBuilder
               .forAddress(replicaNode.backendHost, replicaNode.backendPort)
               .usePlaintext()).resource[F]
        consensusClient <- PBFTInternalServiceFs2Grpc.stubResource(
          channel
        )
      } yield (replicaNode.id -> consensusClient)).sequence
      backupMap = idBackupMap.toMap
    } yield new PBFTInternalGrpcServiceClient[F] {

      import xyz.stratalab.bridge.shared.implicits._

      override def viewChange(request: ViewChangeRequest): F[Empty] =
        for {
          _ <- trace"Sending ViewChange to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.viewChange(
              request.withSignature(
                ByteString.copyFrom(signedBytes)
              ),
              new Metadata()
            ).handleErrorWith { _ =>
           Async[F].pure(Empty())
         }}
        } yield Empty()

      override def commit(request: CommitRequest): F[Empty] =
        for {
          _ <- trace"Sending CommitRequest to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.commit(
              request.withSignature(
                ByteString.copyFrom(signedBytes)
              ),
              new Metadata()
            ).handleErrorWith { _ =>
           Async[F].pure(Empty())
         } // TODO: add retries (5 seconds) delays of 1,2 seconds
          }
        } yield Empty()

      override def prePrepare(request: PrePrepareRequest): F[Empty] =
        for {
          _ <- trace"Sending PrePrepareRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.prePrepare(request, new Metadata()).handleErrorWith { _ =>
           Async[F].pure(Empty())
         }
        } // TODO: add retries (5 seconds) delays of 1,2 seconds

        } yield Empty()

      override def prepare(
        request: PrepareRequest
      ): F[Empty] =
        for {
          _ <- trace"Sending PrepareRequest to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.prepare(
              request.withSignature(
                ByteString.copyFrom(signedBytes)
              ),
              new Metadata()
            ).handleErrorWith { _ =>
           Async[F].pure(Empty())
         }
          }
        } yield Empty()

      override def checkpoint(
        request: CheckpointRequest
      ): F[Empty] = for {
        _ <- trace"Sending Checkpoint to all replicas"
        _ <- backupMap.toList.traverse { case (_, backup) =>
          backup.checkpoint(request, new Metadata()).handleErrorWith { _ =>
           Async[F].pure(Empty())
         }
        }
      } yield Empty()

      override def newView(
        request: NewViewRequest
      ): F[Empty] = for {
        _ <- trace"Sending NewViewRequest to all replicas"
        signedBytes <- BridgeCryptoUtils.signBytes[F](
          keyPair.getPrivate(),
          request.signableBytes
        )
        _ <- backupMap.toList.traverse { case (_, backup) =>
          backup.newView(
            request.withSignature(
              ByteString.copyFrom(signedBytes)
            ),
            new Metadata()
          ).handleErrorWith { _ =>
           Async[F].pure(Empty())
         }
        }
      } yield Empty()

    }
}
