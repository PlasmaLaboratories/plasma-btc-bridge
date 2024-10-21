package org.plasmalabs.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import cats.implicits._
import org.typelevel.log4cats.Logger
import org.plasmalabs.bridge.consensus.core.PublicApiClientGrpcMap
import org.plasmalabs.bridge.consensus.core.pbft.{
  PBFTInternalEvent,
  PrePreparedInserted,
  RequestIdentifier,
  RequestStateManager,
  ViewManager
}
import org.plasmalabs.bridge.consensus.pbft.PrePrepareRequest
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.shared.ClientId
import org.plasmalabs.bridge.shared.implicits._
import org.plasmalabs.sdk.utils.Encoding

import java.security.PublicKey

object PrePrepareActivity {

  sealed private trait PreprepareProblem extends Throwable
  private case object InvalidPrepreareSignature extends PreprepareProblem
  private case object InvalidRequestSignature extends PreprepareProblem
  private case object InvalidRequestDigest extends PreprepareProblem
  private case object InvalidView extends PreprepareProblem
  private case object LogAlreadyExists extends PreprepareProblem

  private def checkViewNumber[F[_]: Async](
    requestViewNumber: Long
  )(implicit viewManager: ViewManager[F]): F[Unit] =
    for {
      currentView <- viewManager.currentView
      isValidViewNumber = requestViewNumber == currentView
      _ <- Async[F].raiseUnless(isValidViewNumber)(
        InvalidView
      )
    } yield ()

  private def checkWaterMark[F[_]: Async](): F[Unit] = // FIXME: add check when watermarks are implemented
    ().pure[F]

  private def checkMessageSignaturePrimaryAux[F[_]: Async](
    replicaKeysMap:       Map[Int, PublicKey],
    requestSignableBytes: Array[Byte],
    requestSignature:     Array[Byte]
  )(implicit
    viewManager: ViewManager[F]
  ): F[Boolean] = {
    import cats.implicits._
    for {
      isValidSignature <- checkMessageSignaturePrimary(
        replicaKeysMap,
        requestSignableBytes,
        requestSignature
      )
      _ <-
        Async[F].raiseUnless(isValidSignature)(
          InvalidPrepreareSignature
        )
    } yield isValidSignature
  }

  def apply[F[_]: Async: Logger](
    request: PrePrepareRequest
  )(
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    requestStateManager:    RequestStateManager[F],
    viewManager:            ViewManager[F],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
    storageApi:             StorageApi[F]
  ): F[Option[PBFTInternalEvent]] = {
    import org.typelevel.log4cats.syntax._
    (for {
      _ <- trace"Received pre-prepare request"
      _ <- checkRequestSignatures(request) >>= (
        Async[F].raiseUnless(_)(
          InvalidRequestSignature
        )
      )
      _ <- checkMessageSignaturePrimaryAux(
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      ) >>= (Async[F].raiseUnless(_)(
        InvalidPrepreareSignature
      ))
      _ <-
        Async[F].raiseUnless(
          checkDigest(
            request.digest.toByteArray(),
            request.payload.get.signableBytes
          )
        )(
          InvalidRequestSignature
        )
      _ <- checkViewNumber(request.viewNumber)
      _ <- checkWaterMark()
      canInsert <- storageApi
        .getPrePrepareMessage(request.viewNumber, request.sequenceNumber)
        .map(x =>
          x.map(y =>
            Encoding.encodeToHex(y.digest.toByteArray()) == Encoding
              .encodeToHex(request.digest.toByteArray())
          ).getOrElse(true)
        )
      _ <- Async[F].raiseUnless(canInsert)(
        LogAlreadyExists
      )
      _ <- storageApi.insertPrePrepareMessage(request)
      _ <- requestStateManager.createStateMachine(
        RequestIdentifier(
          ClientId(request.payload.get.clientNumber),
          request.payload.get.timestamp
        )
      )
    } yield Option(PrePreparedInserted(request): PBFTInternalEvent))
      .handleErrorWith(_ match {
        case InvalidPrepreareSignature =>
          error"Invalid pre-prepare signature" >> none[PBFTInternalEvent]
            .pure[F]
        case InvalidRequestSignature =>
          error"Invalid request signature" >> none[PBFTInternalEvent].pure[F]
        case InvalidView =>
          warn"Invalid view number" >> none[PBFTInternalEvent].pure[F]
        case LogAlreadyExists =>
          warn"Log already exists" >> none[PBFTInternalEvent].pure[F]
        case e =>
          error"Error handling pre-prepare request: $e" >> none[
            PBFTInternalEvent
          ].pure[F]
      })
  }
}
