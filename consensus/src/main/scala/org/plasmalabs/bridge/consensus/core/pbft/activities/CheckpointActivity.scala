package org.plasmalabs.bridge.consensus.core.pbft.activities

import cats.effect.kernel.Async
import cats.implicits._
import org.typelevel.log4cats.Logger
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.PBFTState
import org.plasmalabs.bridge.consensus.core.pbft.{CheckpointIdentifier, CheckpointManager, StableCheckpoint}
import org.plasmalabs.bridge.consensus.core.{KWatermark, WatermarkRef, stateDigest}
import org.plasmalabs.bridge.consensus.pbft.CheckpointRequest
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.shared.implicits._
import org.plasmalabs.bridge.shared.{Empty, ReplicaCount}
import org.plasmalabs.sdk.utils.Encoding

import java.security.PublicKey

object CheckpointActivity {

  sealed private trait CheckpointProblem extends Throwable
  private case object InvalidSignature extends CheckpointProblem
  private case object MessageTooOld extends CheckpointProblem
  private case object LogAlreadyExists extends CheckpointProblem

  private def checkLowWatermark[F[_]: Async](
    request: CheckpointRequest
  )(implicit checkpointManager: CheckpointManager[F]) =
    for {
      lastStableCheckpoint <- checkpointManager.latestStableCheckpoint
      _ <-
        if (request.sequenceNumber < lastStableCheckpoint.sequenceNumber)
          Async[F].raiseError(MessageTooOld)
        else Async[F].unit
    } yield ()

  private def checkSignature[F[_]: Async](
    replicaKeysMap: Map[Int, PublicKey],
    request:        CheckpointRequest
  ): F[Unit] =
    for {
      reqSignCheck <- checkMessageSignature(
        request.replicaId,
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      )
      _ <-
        Async[F].raiseUnless(reqSignCheck)(
          InvalidSignature
        )
    } yield ()

  private def checkExistingLog[F[_]: Async](
    request: CheckpointRequest
  )(implicit storageApi: StorageApi[F]): F[Unit] =
    for {
      someCheckpointMessage <- storageApi
        .getCheckpointMessage(request.sequenceNumber, request.replicaId)
      _ <- Async[F]
        .raiseWhen(someCheckpointMessage.isDefined)(LogAlreadyExists)
    } yield ()

  private def handleUnstableCheckpoint[F[_]: Async](
    request: CheckpointRequest
  )(implicit
    replicaCount:      ReplicaCount,
    checkpointManager: CheckpointManager[F]
  ): F[(Boolean, Map[Int, CheckpointRequest], Map[String, PBFTState])] =
    for {
      unstableCheckpoint <- checkpointManager.unstableCheckpoint(
        CheckpointIdentifier(
          request.sequenceNumber,
          Encoding.encodeToHex(
            request.digest.toByteArray()
          )
        )
      )
      newVotes <- unstableCheckpoint match {
        case None =>
          checkpointManager.createUnstableCheckpoint(request)
        case Some(_) =>
          checkpointManager.updateUnstableCheckpoint(request)
      }
      someStateSnapshot <- checkpointManager.stateSnapshot(
        request.sequenceNumber
      )
    } yield {
      val haveNewStableState = newVotes.size > replicaCount.maxFailures &&
        someStateSnapshot
          .map(snapshot =>
            snapshot.digest == Encoding.encodeToHex(
              request.digest.toByteArray()
            )
          )
          .getOrElse(false)
      (haveNewStableState, newVotes, someStateSnapshot.get.state)
    }

  private def handleNewStableCheckpoint[F[_]: Async](
    request:      CheckpointRequest,
    certificates: Map[Int, CheckpointRequest],
    state:        Map[String, PBFTState]
  )(implicit
    storageApi:        StorageApi[F],
    checkpointManager: CheckpointManager[F],
    kWatermark:        KWatermark,
    watermarkRef:      WatermarkRef[F]
  ): F[Unit] =
    for {
      _ <- checkpointManager.setLatestStableCheckpoint(
        StableCheckpoint(
          request.sequenceNumber,
          certificates,
          state
        )
      )
      lowAndHigh <- watermarkRef.lowAndHigh.get
      (lowWatermark, highWatermark) = lowAndHigh
      _ <- watermarkRef.lowAndHigh.set(
        (
          request.sequenceNumber,
          request.sequenceNumber + kWatermark.underlying
        )
      )
      _ <- storageApi.cleanLog(request.sequenceNumber)
    } yield ()

  private def checkIfStable[F[_]: Async](
    request: CheckpointRequest
  )(implicit checkpointManager: CheckpointManager[F]) = {
    import cats.implicits._
    for {
      lastStableCheckpoint <- checkpointManager.latestStableCheckpoint
    } yield (
      lastStableCheckpoint,
      lastStableCheckpoint.sequenceNumber == request.sequenceNumber &&
      Encoding.encodeToHex(
        stateDigest(lastStableCheckpoint.state)
      ) == Encoding.encodeToHex(request.digest.toByteArray())
    )
  }

  private def performCheckpoint[F[_]: Async](
    request: CheckpointRequest
  )(implicit
    checkpointManager: CheckpointManager[F],
    watermarkRef:      WatermarkRef[F],
    kWatermark:        KWatermark,
    replicaCount:      ReplicaCount,
    storageApi:        StorageApi[F]
  ): F[Unit] =
    for {
      _                               <- storageApi.insertCheckpointMessage(request)
      lastStableCheckpointAndisStable <- checkIfStable(request)
      (lastStableCheckpoint, isStable) = lastStableCheckpointAndisStable
      _ <-
        if (isStable)
          checkpointManager.updateLatestStableCheckpoint(request)
        else {
          for {
            triplet <- handleUnstableCheckpoint(request)
            (haveNewStableState, certificates, state) = triplet
            _ <-
              if (haveNewStableState) {
                for {
                  _ <- handleNewStableCheckpoint(
                    request,
                    certificates,
                    state
                  )
                } yield ()
              } else Async[F].unit
          } yield ()
        }
    } yield ()

  def performValidation[F[_]: Async](
    replicaKeysMap: Map[Int, PublicKey],
    request:        CheckpointRequest
  )(implicit
    checkpointManager: CheckpointManager[F],
    storageApi:        StorageApi[F]
  ): F[Unit] =
    for {
      _ <- checkSignature(replicaKeysMap, request)
      _ <- checkLowWatermark(request)
      _ <- checkExistingLog(request)
    } yield ()

  def apply[F[_]: Async: Logger](
    replicaKeysMap: Map[Int, PublicKey],
    request:        CheckpointRequest
  )(implicit
    watermarkRef:      WatermarkRef[F],
    kWatermark:        KWatermark,
    replicaCount:      ReplicaCount,
    storageApi:        StorageApi[F],
    checkpointManager: CheckpointManager[F]
  ): F[Empty] = {
    import org.typelevel.log4cats.syntax._
    (for {
      _ <- performValidation(replicaKeysMap, request)
      _ <- performCheckpoint(request)
    } yield Empty()).handleErrorWith(e =>
      (e match {
        case InvalidSignature =>
          error"Error handling checkpoint request: Signature verification failed"
        case MessageTooOld =>
          warn"Error handling checkpoint request: Checkpoint message is older than last stable checkpoint"
        case LogAlreadyExists =>
          warn"Error handling checkpoint request: The log is already present"
        case e =>
          error"Error handling checkpoint request: ${e.getMessage()}"
      }) >>
      Async[F].pure(Empty())
    )

  }

}
