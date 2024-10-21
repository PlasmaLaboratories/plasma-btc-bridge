package org.plasmalabs.bridge.consensus.core.pbft

import cats.effect.kernel.{Async, Ref}
import org.plasmalabs.bridge.consensus.pbft.CheckpointRequest
import org.plasmalabs.sdk.utils.Encoding

private[pbft] case class CheckpointIdentifier(
  sequenceNumber: Long,
  digest:         String
)

trait CheckpointManager[F[_]] {

  def latestStableCheckpoint: F[StableCheckpoint]

  def setLatestStableCheckpoint(
    stableCheckpoint: StableCheckpoint
  ): F[Unit]

  def updateLatestStableCheckpoint(
    request: CheckpointRequest
  ): F[Map[Int, CheckpointRequest]]

  def stateSnapshot(sequenceNumber: Long): F[Option[StateSnapshot]]

  def unstableCheckpoint(
    identifier: CheckpointIdentifier
  ): F[Option[UnstableCheckpoint]]

  def createUnstableCheckpoint(
    request: CheckpointRequest
  ): F[Map[Int, CheckpointRequest]]

  def updateUnstableCheckpoint(
    request: CheckpointRequest
  ): F[Map[Int, CheckpointRequest]]

}

object CheckpointManagerImpl {
  import cats.implicits._

  def make[F[_]: Async](): F[CheckpointManager[F]] =
    for {
      stableCheckpoint <- Ref.of(StableCheckpoint(0, Map(), Map()))
      unstableCheckpoints <- Ref.of[
        F,
        Map[CheckpointIdentifier, UnstableCheckpoint]
      ](Map())
      stateSnapshotMap <- Ref.of[F, Map[Long, StateSnapshot]](
        Map(0L -> StateSnapshot(0, Encoding.encodeToHex(Array.emptyByteArray), Map()))
      )
    } yield new CheckpointManager[F] {

      override def latestStableCheckpoint: F[StableCheckpoint] =
        stableCheckpoint.get

      override def unstableCheckpoint(
        identifier: CheckpointIdentifier
      ): F[Option[UnstableCheckpoint]] =
        unstableCheckpoints.get.map(_.get(identifier))

      override def createUnstableCheckpoint(
        request: CheckpointRequest
      ): F[Map[Int, CheckpointRequest]] = {
        val certificates = Map(request.replicaId -> request)
        unstableCheckpoints.update(
          _.updated(
            CheckpointIdentifier(
              request.sequenceNumber,
              Encoding.encodeToHex(request.digest.toByteArray())
            ),
            UnstableCheckpoint(certificates)
          )
        ) >> certificates.pure[F]
      }

      def updateLatestStableCheckpoint(
        request: CheckpointRequest
      ): F[Map[Int, CheckpointRequest]] =
        stableCheckpoint.update { stableCheckpoint =>
          val newCertificates =
            stableCheckpoint.certificates + (request.replicaId -> request)
          stableCheckpoint.copy(certificates = newCertificates)
        } >> stableCheckpoint.get.map(_.certificates)

      override def setLatestStableCheckpoint(
        newStableCheckpoint: StableCheckpoint
      ): F[Unit] =
        for {
          _ <- stableCheckpoint.set(newStableCheckpoint)
          _ <- unstableCheckpoints.update(x => x.filter(_._1.sequenceNumber < newStableCheckpoint.sequenceNumber))
        } yield ()

      override def stateSnapshot(
        sequenceNumber: Long
      ): F[Option[StateSnapshot]] =
        stateSnapshotMap.get.map(_.get(sequenceNumber))

      override def updateUnstableCheckpoint(
        request: CheckpointRequest
      ): F[Map[Int, CheckpointRequest]] =
        for {
          unstableCheckpoint <- unstableCheckpoints.get.map(
            _.get(
              CheckpointIdentifier(
                request.sequenceNumber,
                Encoding.encodeToHex(request.digest.toByteArray())
              )
            )
          )
          someCertificates =
            unstableCheckpoint.map(
              _.certificates + (request.replicaId -> request)
            )
          certificates <- someCertificates
            .map(certificates =>
              unstableCheckpoints.update(
                _.updated(
                  CheckpointIdentifier(
                    request.sequenceNumber,
                    Encoding.encodeToHex(request.digest.toByteArray())
                  ),
                  UnstableCheckpoint(certificates)
                )
              ) >> certificates.pure[F]
            )
            .sequence
            .map(_.getOrElse(Map.empty))
        } yield certificates

    }

}
