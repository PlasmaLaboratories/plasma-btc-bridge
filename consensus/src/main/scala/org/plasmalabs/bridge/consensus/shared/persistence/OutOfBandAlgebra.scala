package org.plasmalabs.bridge.consensus.shared.persistence

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Mutex
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.OutOfBandSignature

import java.util.concurrent.ConcurrentHashMap

trait OutOfBandAlgebra[F[_]] {

  def insertClaimSignature(
    txId:      String,
    signature: String,
    timestamp: Long
  ): F[Boolean]

  def getClaimSignature(
    txId: String
  ): F[Option[OutOfBandSignature]]
}

object OutOfBandAlgebraImpl {

  def make[F[_]: Async]: Resource[F, OutOfBandAlgebra[F]] =
    for {
      mutex <- Resource.eval(Mutex[F])
    } yield {
      val claimSignatures = new ConcurrentHashMap[String, OutOfBandSignature]()

      new OutOfBandAlgebra[F] {
        override def insertClaimSignature(
          txId:      String,
          signature: String,
          timestamp: Long
        ): F[Boolean] = mutex.lock.surround(
          Async[F].delay {
            try {
              claimSignatures.put(
                txId,
                OutOfBandSignature(txId, signature, timestamp)
              )
              true
            } catch {
              case _: Exception => false
            }
          }
        )

        override def getClaimSignature(
          txId: String
        ): F[Option[OutOfBandSignature]] = mutex.lock.surround(
          Async[F].delay {
            Option(claimSignatures.get(txId))
          }
        )
      }
    }
}
