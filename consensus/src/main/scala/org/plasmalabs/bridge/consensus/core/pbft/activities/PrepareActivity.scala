package org.plasmalabs.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import org.typelevel.log4cats.Logger
import org.plasmalabs.bridge.consensus.core.pbft.{PBFTInternalEvent, Prepared, RequestIdentifier, ViewManager}
import org.plasmalabs.bridge.consensus.pbft.PrepareRequest
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.shared.implicits._
import org.plasmalabs.bridge.shared.{ClientId, ReplicaCount}
import org.plasmalabs.sdk.utils.Encoding

import java.security.PublicKey

object PrepareActivity {

  sealed private trait PrepareProblem extends Throwable
  private case object InvalidPrepareSignature extends PrepareProblem
  private case object InvalidView extends PrepareProblem
  private case object InvalidWatermark extends PrepareProblem
  private case class LogAlreadyExists(sequenceNumber: Long) extends PrepareProblem
  import cats.implicits._

  def apply[F[_]: Async: Logger](
    request: PrepareRequest
  )(
    replicaKeysMap: Map[Int, PublicKey]
  )(implicit
    viewManager:  ViewManager[F],
    storageApi:   StorageApi[F],
    replicaCount: ReplicaCount
  ): F[Option[PBFTInternalEvent]] = {
    import org.typelevel.log4cats.syntax._
    (for {
      reqSignCheck <- checkMessageSignature(
        request.replicaId,
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      )
      _ <- Async[F].raiseUnless(reqSignCheck)(
        InvalidPrepareSignature
      )
      viewNumberCheck <- checkViewNumber(request.viewNumber)
      _ <- Async[F].raiseUnless(viewNumberCheck)(
        InvalidView
      )
      waterMarkCheck <- checkWaterMark()
      _ <- Async[F].raiseUnless(waterMarkCheck)(
        InvalidWatermark
      )
      canInsert <- storageApi
        .getPrepareMessage(request.viewNumber, request.sequenceNumber, request.replicaId)
        .map(x =>
          x.find(y =>
            Encoding.encodeToHex(y.digest.toByteArray()) == Encoding
              .encodeToHex(request.digest.toByteArray())
          ).isEmpty
        )
      _ <- Async[F].raiseUnless(canInsert)(
        LogAlreadyExists(request.sequenceNumber)
      )
      _ <- storageApi.insertPrepareMessage(request)
      isPrepared <- isPrepared[F](
        request.viewNumber,
        request.sequenceNumber
      )
      somePrePrepareMessage <-
        if (isPrepared)
          storageApi
            .getPrePrepareMessage(request.viewNumber, request.sequenceNumber)
        else
          Async[F].pure(None)
    } yield Option.when(isPrepared)(
      Prepared(
        RequestIdentifier(
          ClientId(
            somePrePrepareMessage.flatMap(_.payload).get.clientNumber
          ),
          somePrePrepareMessage.flatMap(_.payload).get.timestamp
        ),
        request
      ): PBFTInternalEvent
    )).handleErrorWith {
      _ match {
        case InvalidPrepareSignature =>
          error"Invalid Prepare signature" >> none[PBFTInternalEvent]
            .pure[F]
        case InvalidView =>
          error"Invalid view number" >> none[PBFTInternalEvent]
            .pure[F]
        case InvalidWatermark =>
          error"Invalid watermark" >> none[PBFTInternalEvent]
            .pure[F]
        case LogAlreadyExists(sequenceNumber) =>
          warn"Prepare message already exists for this sequence number: $sequenceNumber" >> none[
            PBFTInternalEvent
          ]
            .pure[F]
      }
    }
  }

}
