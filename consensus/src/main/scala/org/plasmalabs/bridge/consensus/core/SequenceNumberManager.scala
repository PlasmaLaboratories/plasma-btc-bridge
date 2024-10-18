package org.plasmalabs.bridge.consensus.core

import cats.effect.kernel.{Ref, Sync}

trait SequenceNumberManager[F[_]] {

  def currentSequenceNumber: F[Long]

  def getAndIncrease: F[Long]

}

object SequenceNumberManagerImpl {

  import cats.implicits._

  def make[F[_]: Sync](): F[SequenceNumberManager[F]] =
    for {
      sequenceNumber <- Ref.of[F, Long](0L)
    } yield new SequenceNumberManager[F] {
      override def currentSequenceNumber: F[Long] = sequenceNumber.get

      override def getAndIncrease: F[Long] =
        sequenceNumber.updateAndGet(_ + 1)
    }

}
