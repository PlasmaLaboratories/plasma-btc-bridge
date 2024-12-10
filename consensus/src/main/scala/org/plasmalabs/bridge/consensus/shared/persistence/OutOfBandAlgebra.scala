package org.plasmalabs.bridge.consensus.shared.persistence

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Mutex
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.OutOfBandSignature

import java.util.concurrent.ConcurrentHashMap

trait OutOfBandAlgebra[F[_]] {
  /** Stores a claim signature for a given transaction.
    *
    * This method attempts to insert a new signature entry into the storage. If an entry
    * for the given transaction ID already exists, it will be overwritten.
    *
    * @param txId The unique identifier of the transaction
    * @param signature The cryptographic signature to be stored
    * @param timestamp The timestamp when the signature was created
    * @return F[Boolean] Returns true if the insertion was successful, false otherwise
    */
  def insertClaimSignature(
    txId:      String,
    signature: String,
    timestamp: Long
  ): F[Boolean]

  /** Retrieves a previously stored claim signature for a given transaction.
    *
    * This method looks up the signature entry associated with the provided
    * transaction ID. If no entry exists, None is returned.
    *
    * @param txId The unique identifier of the transaction
    * @return F[Option[OutOfBandSignature]] Returns the signature entry if found, None otherwise
    */
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
