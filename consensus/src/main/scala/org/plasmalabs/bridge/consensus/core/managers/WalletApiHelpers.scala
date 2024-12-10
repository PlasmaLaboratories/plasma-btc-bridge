package org.plasmalabs.bridge.consensus.core.managers

import cats.Monad
import org.plasmalabs.bridge.consensus.core.{Fellowship, Template}
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.WalletStateAlgebra
import org.plasmalabs.sdk.models.box.Lock
import org.plasmalabs.sdk.models.{Indices, LockAddress}
import org.plasmalabs.bridge.shared.{BridgeError, UnknownError}

object WalletApiHelpers {

  def getCurrentIndices[F[_]](
    fromFellowship:      Fellowship,
    fromTemplate:        Template,
    someFromInteraction: Option[Int]
  )(implicit wsa: WalletStateAlgebra[F]) = wsa.getCurrentIndicesForFunds(
    fromFellowship.underlying,
    fromTemplate.underlying,
    someFromInteraction
  )

  def getCurrentAddress[F[_]: Monad](
    fromFellowship:      Fellowship,
    fromTemplate:        Template,
    someFromInteraction: Option[Int]
  )(implicit
    wsa: WalletStateAlgebra[F],
    tba: TransactionBuilderApi[F]
  ): F[Either[BridgeError, LockAddress]] = {
    import cats.implicits._
    for {
      someCurrentIndices <- getCurrentIndices(
        fromFellowship,
        fromTemplate,
        someFromInteraction
      )
      predicateFundsToUnlock <- getPredicateFundsToUnlock[F](someCurrentIndices)
      fromAddress <- predicateFundsToUnlock match {
        case None =>
          (Left(UnknownError("Predicate funds not found"))).pure[F]
        case Some(lock) =>
          tba.lockAddress(lock).map(Right(_))
      }
    } yield fromAddress
  }

  def getPredicateFundsToUnlock[F[_]: Monad](
    someIndices: Option[Indices]
  )(implicit wsa: WalletStateAlgebra[F]): F[Option[Lock]] = {
    import cats.implicits._
    someIndices
      .map(currentIndices => wsa.getLockByIndex(currentIndices))
      .sequence
      .map(_.flatten.map(Lock().withPredicate(_)))
  }

  def getNextIndices[F[_]](
    fromFellowship: Fellowship,
    fromTemplate:   Template
  )(implicit wsa: WalletStateAlgebra[F]) =
    wsa.getNextIndicesForFunds(
      if (fromFellowship.underlying == "nofellowship") "self"
      else fromFellowship.underlying,
      if (fromFellowship.underlying == "nofellowship") "default"
      else fromTemplate.underlying
    )

  def getChangeLockPredicate[F[_]: Monad](
    someNextIndices: Option[Indices],
    fromFellowship:  Fellowship,
    fromTemplate:    Template
  )(implicit wsa: WalletStateAlgebra[F]) = {
    import cats.implicits._
    someNextIndices
      .map(idx =>
        wsa.getLock(
          if (fromFellowship.underlying == "nofellowship") "self"
          else fromFellowship.underlying,
          if (fromFellowship.underlying == "nofellowship") "default"
          else fromTemplate.underlying,
          idx.z
        )
      )
      .sequence
      .map(_.flatten)
  }

}
