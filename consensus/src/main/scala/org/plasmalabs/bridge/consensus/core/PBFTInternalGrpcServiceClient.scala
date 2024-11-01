package org.plasmalabs.consensus.core

import cats.Parallel
import cats.effect.kernel.Async
import com.google.protobuf.ByteString
import fs2.grpc.syntax.all._
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.plasmalabs.bridge.consensus.pbft.{
  CheckpointRequest,
  CommitRequest,
  NewViewRequest,
  PBFTInternalServiceFs2Grpc,
  PrePrepareRequest,
  PrepareRequest,
  ViewChangeRequest
}
import org.plasmalabs.bridge.shared.{BridgeCryptoUtils, Empty, ReplicaNode, RetryPolicy}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.security.KeyPair
import scala.concurrent.duration.FiniteDuration

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

  def make[F[_]: Parallel: Async: Logger](
    keyPair:      KeyPair,
    replicaNodes: List[ReplicaNode[F]]
  )(implicit pbftInternalConfig: RetryPolicy) =
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

      import org.plasmalabs.bridge.shared.implicits._

      def retryWithBackoff[A](
        operation:     => F[A],
        delay:         FiniteDuration,
        maxRetries:    Int,
        operationName: String,
        defaultValue:  => A
      ): F[A] = for {
        result <- operation.handleErrorWith { _ =>
          maxRetries match {
            case 0 => error"Max retries reached for $operationName" >> Async[F].pure(defaultValue)
            case _ => Async[F].sleep(delay) >> retryWithBackoff(
                  operation,
                  delay * pbftInternalConfig.delayMultiplier,
                  maxRetries - 1,
                  operationName,
                  defaultValue
                )
          }
        }
      } yield result

      override def viewChange(request: ViewChangeRequest): F[Empty] =
        for {
          _ <- trace"Sending ViewChange to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.viewChange(
                request.withSignature(
                  ByteString.copyFrom(signedBytes)
                ),
                new Metadata()
              ),
              pbftInternalConfig.initialDelay,
              pbftInternalConfig.maxRetries,
              "View Change",
              Empty()
            )
          }
        } yield Empty()

      override def commit(request: CommitRequest): F[Empty] =
        for {
          _ <- trace"Sending CommitRequest to all replicas"
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            request.signableBytes
          )
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.commit(
                request.withSignature(
                  ByteString.copyFrom(signedBytes)
                ),
                new Metadata()
              ),
              pbftInternalConfig.initialDelay,
              pbftInternalConfig.maxRetries,
              "Commit",
              Empty()
            ).handleErrorWith { _ =>
              Async[F].pure(Empty())
            }
          }
        } yield Empty()

      override def prePrepare(request: PrePrepareRequest): F[Empty] =
        for {
          _ <- trace"Sending PrePrepareRequest to all replicas"
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.prePrepare(
                request,
                new Metadata()
              ),
              pbftInternalConfig.initialDelay,
              pbftInternalConfig.maxRetries,
              "Pre Prepare",
              Empty()
            )
          }
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
          _ <- backupMap.toList.parTraverse { case (_, backup) =>
            retryWithBackoff(
              backup.prepare(
                request.withSignature(
                  ByteString.copyFrom(signedBytes)
                ),
                new Metadata()
              ),
              pbftInternalConfig.initialDelay,
              pbftInternalConfig.maxRetries,
              "Prepare",
              Empty()
            )
          }
        } yield Empty()

      override def checkpoint(
        request: CheckpointRequest
      ): F[Empty] = for {
        _ <- trace"Sending Checkpoint to all replicas"
        _ <- backupMap.toList.parTraverse { case (_, backup) =>
          retryWithBackoff(
            backup.checkpoint(
              request,
              new Metadata()
            ),
            pbftInternalConfig.initialDelay,
            pbftInternalConfig.maxRetries,
            "Checkpoint",
            Empty()
          )
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
        _ <- backupMap.toList.parTraverse { case (_, backup) =>
          retryWithBackoff(
            backup.newView(
              request.withSignature(
                ByteString.copyFrom(signedBytes)
              ),
              new Metadata()
            ),
            pbftInternalConfig.initialDelay,
            pbftInternalConfig.maxRetries,
            "New View",
            Empty()
          )
        }
      } yield Empty()

    }
}
