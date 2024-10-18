package org.plasmalabs.bridge.consensus.core.modules

import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.box.Lock
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.brambl.models.{Event, Indices, LockAddress}
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.genus.services.Txo
import quivr.models.KeyPair
import org.plasmalabs.bridge.consensus.core.managers.CreateTxError

import TransactionBuilderApi.implicits._

object SeriesMintingOps {

  import cats.implicits._

  private def buildSeriesTransaction[G[_]: Sync](
    txos:                   Seq[Txo],
    predicateFundsToUnlock: Lock.Predicate,
    lockForChange:          Lock,
    recipientLockAddress:   LockAddress,
    amount:                 Long,
    fee:                    Long,
    someNextIndices:        Option[Indices],
    keyPair:                KeyPair,
    seriesPolicy:           Event.SeriesPolicy
  )(implicit
    tba: TransactionBuilderApi[G],
    wsa: WalletStateAlgebra[G],
    wa:  WalletApi[G]
  ): G[IoTransaction] =
    for {
      changeAddress <- tba.lockAddress(
        lockForChange
      )
      eitherIoTransaction <- tba.buildSeriesMintingTransaction(
        txos,
        predicateFundsToUnlock,
        seriesPolicy,
        amount,
        recipientLockAddress,
        changeAddress,
        fee
      )
      ioTransaction <- Sync[G].fromEither(eitherIoTransaction)
      // Only save to wallet interaction if there is a change output in the transaction
      _ <-
        if (ioTransaction.outputs.length >= 2) for {
          vk <- someNextIndices
            .map(nextIndices =>
              wa
                .deriveChildKeys(keyPair, nextIndices)
                .map(_.vk)
            )
            .sequence
          _ <- wsa.updateWalletState(
            Encoding.encodeToBase58Check(
              lockForChange.getPredicate.toByteArray
            ),
            changeAddress.toBase58(),
            vk.map(_ => "ExtendedEd25519"),
            vk.map(x => Encoding.encodeToBase58(x.toByteArray)),
            someNextIndices.get
          )
        } yield ioTransaction
        else {
          Sync[G].delay(ioTransaction)
        }
    } yield ioTransaction

  def buildSeriesTx[G[_]: Sync](
    lvlTxos:                Seq[Txo],
    nonLvlTxos:             Seq[Txo],
    predicateFundsToUnlock: Lock.Predicate,
    amount:                 Long,
    fee:                    Long,
    someNextIndices:        Option[Indices],
    keyPair:                KeyPair,
    seriesPolicy:           Event.SeriesPolicy,
    changeLock:             Option[Lock]
  )(implicit
    tba: TransactionBuilderApi[G],
    wsa: WalletStateAlgebra[G],
    wa:  WalletApi[G]
  ): G[IoTransaction] = (if (lvlTxos.isEmpty) {
                           Sync[G].raiseError(
                             CreateTxError("No LVL txos found")
                           )
                         } else {
                           changeLock match {
                             case Some(lockPredicateForChange) =>
                               tba
                                 .lockAddress(lockPredicateForChange)
                                 .flatMap { changeAddress =>
                                   buildSeriesTransaction(
                                     lvlTxos ++ nonLvlTxos,
                                     predicateFundsToUnlock,
                                     lockPredicateForChange,
                                     changeAddress,
                                     amount,
                                     fee,
                                     someNextIndices,
                                     keyPair,
                                     seriesPolicy
                                   )
                                 }
                             case None =>
                               Sync[G].raiseError(
                                 CreateTxError("Unable to generate change lock")
                               )
                           }
                         })
}
