package xyz.stratalab.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import org.typelevel.log4cats.Logger
import xyz.stratalab.bridge.consensus.core.PublicApiClientGrpcMap
import xyz.stratalab.bridge.consensus.core.pbft.ViewManager
import xyz.stratalab.bridge.consensus.pbft.{NewViewRequest, PrePrepareRequest, PrepareRequest, ViewChangeRequest}
import xyz.stratalab.bridge.shared.implicits._
import xyz.stratalab.bridge.shared.{BridgeCryptoUtils, ReplicaCount}

import java.security.PublicKey

object NewViewActivity {

  import cats.implicits._

  sealed private trait ViewChangeProblem extends Throwable
  private case object NewViewSignature extends ViewChangeProblem
  private case object InvalidViewChangeSignature extends ViewChangeProblem
  private case object InvalidCertificates extends ViewChangeProblem
  private case object InvalidPreprepareSignature extends ViewChangeProblem
  private case object InvalidPrepareSignature extends ViewChangeProblem
  private case object InvalidPreprepareRequestSignature extends ViewChangeProblem

  private def validatePrePrepares[F[_]: Async](
    prePrepare:     PrePrepareRequest,
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    viewManager:            ViewManager[F],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[F]
  ): F[Unit] =
    for {
      validPrePrepares <- checkRequestSignatures(prePrepare)
      _ <- Async[F].raiseUnless(validPrePrepares)(
        InvalidPreprepareSignature
      )
      _ <- checkMessageSignaturePrimary(
        replicaKeysMap,
        prePrepare.signableBytes,
        prePrepare.signature.toByteArray()
      ) >>= (Async[F].raiseUnless(_)(
        InvalidPreprepareRequestSignature
      ))
    } yield ()

  private def validatePrepares[F[_]: Async](
    prepare:        PrepareRequest,
    replicaKeysMap: Map[Int, PublicKey]
  ): F[Unit] =
    for {
      validPrepares <- checkMessageSignature(
        prepare.replicaId,
        replicaKeysMap,
        prepare.signableBytes,
        prepare.signature.toByteArray()
      )
      _ <- Async[F].raiseUnless(validPrepares)(
        InvalidPrepareSignature
      )
    } yield ()

  private def performNewViewValidation[F[_]: Async](
    request:        ViewChangeRequest,
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    viewManager:            ViewManager[F],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[F]
  ): F[Unit] =
    for {
      reqSignCheck <- checkMessageSignature(
        request.replicaId,
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      )
      _ <- Async[F].raiseUnless(reqSignCheck)(
        InvalidViewChangeSignature
      )
      // validate checkpoint certificates
      validCheckpoints <- request.checkpoints.toList
        .map { checkpoint =>
          BridgeCryptoUtils.verifyBytes(
            replicaKeysMap(checkpoint.replicaId),
            checkpoint.signableBytes,
            checkpoint.signature.toByteArray()
          )
        }
        .sequence
        .map(_.forall(identity))
      _ <- Async[F].raiseUnless(validCheckpoints)(
        InvalidCertificates
      )
      // Validate pre prepare requests
      _ <- request.pms.toList.map { pm =>
        validatePrePrepares(pm.prePrepare.get, replicaKeysMap)
      }.sequence
      // Validate prepare requests
      _ <- request.pms.toList.flatMap { pm =>
        pm.prepares.map(validatePrepares(_, replicaKeysMap))
      }.sequence
    } yield ()

  def apply[F[_]: Async: Logger](
    request: NewViewRequest
  )(
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    viewManager:            ViewManager[F],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
    replicaCount:           ReplicaCount
  ): F[Unit] =
    (for {
      reqSignCheck <- checkMessageSignature(
        (request.newViewNumber % replicaCount.value).toInt,
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      )
      _ <- Async[F].raiseUnless(reqSignCheck)(
        NewViewSignature
      )
      _ <- request.viewChanges.toList
        .map(performNewViewValidation(_, replicaKeysMap))
        .sequence
    } yield ()).handleErrorWith { e =>
      e match {
        case NewViewSignature =>
          Logger[F].error(
            "NewViewActivity: NewView signature validation failed"
          )
        case InvalidViewChangeSignature =>
          Logger[F].error(
            "NewViewActivity: Invalid view change signature"
          )
        case InvalidCertificates =>
          Logger[F].error(
            "NewViewActivity: Invalid checkpoint certificates"
          )
        case InvalidPreprepareSignature =>
          Logger[F].error(
            "NewViewActivity: Invalid preprepare signature"
          )
        case InvalidPrepareSignature =>
          Logger[F].error(
            "NewViewActivity: Invalid prepare signature"
          )
        case InvalidPreprepareRequestSignature =>
          Logger[F].error(
            "NewViewActivity: Invalid preprepare request signature"
          )
        case e =>
          Logger[F].error(
            s"NewViewActivity: Unknown error occurred: ${e.getMessage}"
          )
      }
    }
}
