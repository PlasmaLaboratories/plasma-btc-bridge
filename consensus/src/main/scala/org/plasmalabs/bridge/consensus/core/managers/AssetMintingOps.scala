package xyz.stratalab.bridge.consensus.core.managers

import cats.effect.kernel.Sync
import com.google.protobuf.ByteString
import com.google.protobuf.struct.Struct
import io.circe.Json
import quivr.models.KeyPair
import xyz.stratalab.bridge.consensus.shared.Lvl
import xyz.stratalab.indexer.services.Txo
import xyz.stratalab.sdk.builders.TransactionBuilderApi
import xyz.stratalab.sdk.dataApi.WalletStateAlgebra
import xyz.stratalab.sdk.models.box.{AssetMintingStatement, Lock}
import xyz.stratalab.sdk.models.transaction.IoTransaction
import xyz.stratalab.sdk.models.{Indices, LockAddress}
import xyz.stratalab.sdk.utils.Encoding
import xyz.stratalab.sdk.wallet.WalletApi

import TransactionBuilderApi.implicits._

object AssetMintingOps {

  import cats.implicits._

  def buildAssetTxAux[G[_]: Sync](
    keyPair:               KeyPair,
    lvlTxos:               Seq[Txo],
    nonLvlTxos:            Seq[Txo],
    groupTxo:              Txo,
    seriesTxo:             Txo,
    lockAddrToUnlock:      LockAddress,
    lockPredicateFrom:     Lock.Predicate,
    fee:                   Lvl,
    someNextIndices:       Option[Indices],
    assetMintingStatement: AssetMintingStatement,
    ephemeralMetadata:     Option[Json],
    commitment:            Option[ByteString],
    changeLock:            Option[Lock],
    recipientLockAddress:  LockAddress
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
               .flatMap { _ =>
                 buildAssetTransaction(
                   keyPair,
                   lvlTxos ++ nonLvlTxos :+ groupTxo :+ seriesTxo,
                   Map(lockAddrToUnlock -> lockPredicateFrom),
                   lockPredicateForChange,
                   recipientLockAddress, // recipient lock address
                   fee,
                   assetMintingStatement,
                   ephemeralMetadata.map(toStruct(_).getStructValue),
                   commitment,
                   someNextIndices
                 )
               }
           case None =>
             Sync[G].raiseError(
               CreateTxError("Unable to generate change lock")
             )
         }
       })

  private def buildAssetTransaction[G[_]: Sync](
    keyPair:               KeyPair,
    txos:                  Seq[Txo],
    lockPredicateFrom:     Map[LockAddress, Lock.Predicate],
    lockForChange:         Lock,
    recipientLockAddress:  LockAddress,
    fee:                   Lvl,
    assetMintingStatement: AssetMintingStatement,
    ephemeralMetadata:     Option[Struct],
    commitment:            Option[ByteString],
    someNextIndices:       Option[Indices]
  )(implicit
    tba: TransactionBuilderApi[G],
    wsa: WalletStateAlgebra[G],
    wa:  WalletApi[G]
  ): G[IoTransaction] = {
    import xyz.stratalab.sdk.syntax._
    for {
      changeAddress <- tba.lockAddress(
        lockForChange
      )
      eitherIoTransaction <- tba.buildAssetMintingTransaction(
        assetMintingStatement,
        txos,
        lockPredicateFrom,
        fee.amount.toLong,
        recipientLockAddress,
        changeAddress,
        ephemeralMetadata,
        commitment
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
}
