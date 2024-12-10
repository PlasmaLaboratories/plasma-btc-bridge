package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import io.grpc.ManagedChannel
import org.plasmalabs.bridge.consensus.core.managers.{PlasmaWalletAlgebra, TransactionAlgebra, WalletApiHelpers}
import org.plasmalabs.bridge.consensus.core.{Fellowship, PlasmaKeypair, Template}
import org.plasmalabs.bridge.consensus.shared.Lvl
import org.plasmalabs.bridge.shared.{BridgeError, UnknownError}
import org.plasmalabs.indexer.services.{Txo, TxoState}
import org.plasmalabs.quivr.models.{Int128, KeyPair}
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.{IndexerQueryAlgebra, WalletStateAlgebra}
import org.plasmalabs.sdk.models.{AssetMintingStatement, LockAddress}
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger

object WaitingBTCOps {

  import WalletApiHelpers._
  import PlasmaWalletAlgebra._
  import TransactionAlgebra._

  private def getGroupTokeUtxo(txos: Seq[Txo]) =
    txos
      .filter(_.transactionOutput.value.value.isGroup)
      .head
      .outputAddress

  private def getSeriesTokenUtxo(txos: Seq[Txo]) =
    txos
      .filter(_.transactionOutput.value.value.isSeries)
      .head
      .outputAddress

  private def computeAssetMintingStatement[F[_]: Async: Logger](
    amount:         Int128,
    currentAddress: LockAddress,
    utxoAlgebra:    IndexerQueryAlgebra[F]
  ): F[Either[BridgeError, (AssetMintingStatement, Seq[Txo])]] =
    getUtxos(currentAddress, utxoAlgebra, TxoState.UNSPENT).map(_.map { txos =>
      (
        AssetMintingStatement(
          getGroupTokeUtxo(txos),
          getSeriesTokenUtxo(txos),
          amount
        ),
        txos
      )
    })

  def getUtxos[F[_]: Async: Logger](
    currentAddress: LockAddress,
    utxoAlgebra:    IndexerQueryAlgebra[F],
    txoState:       TxoState = TxoState.UNSPENT
  ): F[Either[BridgeError, Seq[Txo]]] = {
    import scala.concurrent.duration._
    import org.typelevel.log4cats.syntax._

    def attemptQuery: F[Either[BridgeError, Seq[Txo]]] =
      utxoAlgebra
        .queryUtxo(currentAddress, txoState)
        .attempt
        .flatMap {
          case Left(e) =>
            Async[F].sleep(5.second) >>
            Async[F].pure(Left(UnknownError(s"Something went wrong: ${e}")))
          case Right(txos) if txos.isEmpty =>
            Async[F].sleep(5.second) >>
            warn"No UTXO found while minting" >>
            Async[F].pure(Left(UnknownError("No UTXOs found")))
          case Right(txos) =>
            Async[F].pure(Right(txos))
        }

    attemptQuery.iterateUntil(_.isRight)
  }

  private def mintTBTC[F[_]: Async](
    redeemAddress:         String,
    fromFellowship:        Fellowship,
    fromTemplate:          Template,
    assetMintingStatement: AssetMintingStatement,
    keyPair:               KeyPair,
    fee:                   Lvl
  )(implicit
    tba:             TransactionBuilderApi[F],
    walletApi:       WalletApi[F],
    wsa:             WalletStateAlgebra[F],
    utxoAlgebra:     IndexerQueryAlgebra[F],
    channelResource: Resource[F, ManagedChannel]
  ): F[Either[BridgeError, String]] = for {
    ioTransaction <- createSimpleAssetMintingTransactionFromParams(
      keyPair,
      fromFellowship,
      fromTemplate,
      None,
      fee,
      None,
      None,
      assetMintingStatement,
      redeemAddress
    )
    provedIoTx <- proveSimpleTransactionFromParams(
      ioTransaction,
      keyPair
    )
      .flatMap(Async[F].fromEither(_))
    txId <- broadcastSimpleTransactionFromParams(provedIoTx)

  } yield txId match {
    case Left(e)     => Left(UnknownError(s"Simple Transaction Error: ${e.getMessage}"))
    case Right(txId) => Right(txId)
  }

  def startMintingProcess[F[_]: Async: Logger](
    fromFellowship: Fellowship,
    fromTemplate:   Template,
    redeemAddress:  String,
    amount:         Int128
  )(implicit
    plasmaKeypair:         PlasmaKeypair,
    walletApi:             WalletApi[F],
    walletStateApi:        WalletStateAlgebra[F],
    transactionBuilderApi: TransactionBuilderApi[F],
    utxoAlgebra:           IndexerQueryAlgebra[F],
    channelResource:       Resource[F, ManagedChannel],
    defaultMintingFee:     Lvl
  ): F[Either[BridgeError, (LockAddress, Seq[Txo])]] = {
    import cats.data.EitherT

    val result: EitherT[F, BridgeError, (LockAddress, Seq[Txo])] = for {
      currentAddress <- EitherT[F, BridgeError, LockAddress](
        getCurrentAddress[F](
          fromFellowship,
          fromTemplate,
          None
        )
      )

      mintingStatementOuter <- EitherT(
        computeAssetMintingStatement(
          amount,
          currentAddress,
          utxoAlgebra
        )
      )

      (mintStatement, txos) = mintingStatementOuter

      _ <- EitherT(
        mintTBTC(
          redeemAddress,
          fromFellowship,
          fromTemplate,
          mintStatement,
          plasmaKeypair.underlying,
          defaultMintingFee
        )
      )
    } yield (currentAddress, txos)

    result.value
  }

}
