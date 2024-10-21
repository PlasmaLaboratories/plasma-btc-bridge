package org.plasmalabs.bridge.consensus.core.modules

import cats.effect.kernel.Sync
import org.plasmalabs.bridge.consensus.core.managers.CreateTxError
import org.plasmalabs.indexer.services.Txo
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.WalletStateAlgebra
import org.plasmalabs.sdk.models.box.Lock
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.models.{Event, Indices, LockAddress}
import org.plasmalabs.sdk.utils.Encoding
import org.plasmalabs.sdk.wallet.WalletApi
import quivr.models.KeyPair

import TransactionBuilderApi.implicits._

object GroupMintingOps {

  import cats.implicits._

  def buildGroupTx[G[_]: Sync](
    lvlTxos:                Seq[Txo],
    nonlvlTxos:             Seq[Txo],
    predicateFundsToUnlock: Lock.Predicate,
    amount:                 Long,
    fee:                    Long,
    someNextIndices:        Option[Indices],
    keyPair:                KeyPair,
    groupPolicy:            Event.GroupPolicy,
    changeLock:             Option[Lock]
  )(implicit
    tba: TransactionBuilderApi[G],
    wsa: WalletStateAlgebra[G],
    wa:  WalletApi[G]
  ) = (if (lvlTxos.isEmpty) {
         Sync[G].raiseError(CreateTxError("No LVL txos found"))
       } else {
         changeLock match {
           case Some(lockPredicateForChange) =>
             tba
               .lockAddress(lockPredicateForChange)
               .flatMap { changeAddress =>
                 buildGroupTransaction(
                   lvlTxos ++ nonlvlTxos,
                   predicateFundsToUnlock,
                   lockPredicateForChange,
                   changeAddress,
                   amount,
                   fee,
                   someNextIndices,
                   keyPair,
                   groupPolicy
                 )
               }
           case None =>
             Sync[G].raiseError(
               CreateTxError("Unable to generate change lock")
             )
         }
       })

  private def buildGroupTransaction[G[_]: Sync](
    txos:                   Seq[Txo],
    predicateFundsToUnlock: Lock.Predicate,
    lockForChange:          Lock,
    recipientLockAddress:   LockAddress,
    amount:                 Long,
    fee:                    Long,
    someNextIndices:        Option[Indices],
    keyPair:                KeyPair,
    groupPolicy:            Event.GroupPolicy
  )(implicit
    tba: TransactionBuilderApi[G],
    wsa: WalletStateAlgebra[G],
    wa:  WalletApi[G]
  ): G[IoTransaction] =
    for {
      changeAddress <- tba.lockAddress(
        lockForChange
      )
      eitherIoTransaction <- tba.buildGroupMintingTransaction(
        txos,
        predicateFundsToUnlock,
        groupPolicy,
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
        } yield ()
        else {
          Sync[G].delay(())
        }
    } yield ioTransaction

}
