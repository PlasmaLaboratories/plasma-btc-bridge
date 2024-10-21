package org.plasmalabs.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import cats.implicits._
import org.plasmalabs.bridge.consensus.core.PublicApiClientGrpcMap
import org.plasmalabs.bridge.consensus.core.pbft.ViewManager
import org.plasmalabs.bridge.consensus.pbft.{PrePrepareRequest, PrepareRequest, ViewChangeRequest}
import org.plasmalabs.bridge.shared.BridgeCryptoUtils
import org.plasmalabs.bridge.shared.implicits._
import org.typelevel.log4cats.Logger

import java.security.PublicKey

object ViewChangeActivity {

  sealed private trait ViewChangeProblem extends Throwable
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

  private def performViewChangeValidation[F[_]: Async](
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
    request:        ViewChangeRequest,
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    viewManager:            ViewManager[F],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[F]
  ): F[Unit] = {

    import org.typelevel.log4cats.syntax._
    (for {
      _ <- performViewChangeValidation(
        request,
        replicaKeysMap
      )
    } yield ()).handleErrorWith {
      _ match {
        case InvalidCertificates =>
          error"View Change: An invalid certificate was found in the view change request"
        case InvalidViewChangeSignature =>
          error"View Change: An invalid signature was found in the view change request"
        case InvalidPreprepareSignature =>
          error"View Change: An invalid signature was found in the preprepare request"
        case InvalidPrepareSignature =>
          error"View Change: An invalid signature was found in the prepare request"
        case InvalidPreprepareRequestSignature =>
          error"View Change: An invalid signature was found in the preprepare request"
      }
    }
  }
}
