package xyz.stratalab.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource}
import cats.implicits._
import io.grpc.ManagedChannel
import org.typelevel.log4cats.Logger
import quivr.models.{Int128, KeyPair}
import xyz.stratalab.bridge.consensus.core.managers.{StrataWalletAlgebra, TransactionAlgebra, WalletApiHelpers}
import xyz.stratalab.bridge.consensus.core.{Fellowship, StrataKeypair, Template}
import xyz.stratalab.bridge.consensus.shared.Lvl
import xyz.stratalab.indexer.services.Txo
import xyz.stratalab.sdk.builders.TransactionBuilderApi
import xyz.stratalab.sdk.dataApi.{IndexerQueryAlgebra, WalletStateAlgebra}
import xyz.stratalab.sdk.models.LockAddress
import xyz.stratalab.sdk.models.box.AssetMintingStatement
import xyz.stratalab.sdk.wallet.WalletApi

object WaitingBTCOps {

  import WalletApiHelpers._
  import StrataWalletAlgebra._
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
  ) = for {
    txos <- (utxoAlgebra
      .queryUtxo(
        currentAddress
      )
      .attempt >>= {
      case Left(e) =>
        import scala.concurrent.duration._
        Async[F].sleep(5.second) >> Async[F].pure(e.asLeft[Seq[Txo]])
      case Right(txos) =>
        if (txos.isEmpty) {
          import scala.concurrent.duration._
          import org.typelevel.log4cats.syntax._
          Async[F].sleep(
            5.second
          ) >> warn"No UTXO found while minting" >> Async[F].pure(
            new Throwable("No UTXOs found").asLeft[Seq[Txo]]
          )
        } else
          Async[F].pure(txos.asRight[Throwable])
    })
      .iterateUntil(_.isRight)
      .map(_.toOption.get)
  } yield AssetMintingStatement(
    getGroupTokeUtxo(txos),
    getSeriesTokenUtxo(txos),
    amount
  )

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
  ) = for {
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
      .flatMap(Async[F].fromEither(_))
  } yield txId

  def startMintingProcess[F[_]: Async: Logger](
    fromFellowship: Fellowship,
    fromTemplate:   Template,
    redeemAddress:  String,
    amount:         Int128
  )(implicit
    toplKeypair:           StrataKeypair,
    walletApi:             WalletApi[F],
    walletStateApi:        WalletStateAlgebra[F],
    transactionBuilderApi: TransactionBuilderApi[F],
    utxoAlgebra:           IndexerQueryAlgebra[F],
    channelResource:       Resource[F, ManagedChannel],
    defaultMintingFee:     Lvl
  ): F[Unit] = {
    import cats.implicits._
    for {
      currentAddress <- getCurrentAddress[F](
        fromFellowship,
        fromTemplate,
        None
      )
      assetMintingStatement <- computeAssetMintingStatement(
        amount,
        currentAddress,
        utxoAlgebra
      )
      _ <- mintTBTC(
        redeemAddress,
        fromFellowship,
        fromTemplate,
        assetMintingStatement,
        toplKeypair.underlying,
        defaultMintingFee
      )
    } yield ()
  }

}
