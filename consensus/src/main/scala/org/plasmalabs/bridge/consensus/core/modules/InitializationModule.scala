package xyz.stratalab.bridge.consensus.core.modules

import cats.effect.kernel.{Async, Ref}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import quivr.models.Int128
import xyz.stratalab.bridge.consensus.core.managers.WalletApiHelpers
import xyz.stratalab.bridge.consensus.core.{Fellowship, SystemGlobalState, Template}
import xyz.stratalab.indexer.services.Txo
import xyz.stratalab.sdk.builders.TransactionBuilderApi
import xyz.stratalab.sdk.dataApi.{IndexerQueryAlgebra, WalletStateAlgebra}
import xyz.stratalab.sdk.models.{GroupId, SeriesId}
import xyz.stratalab.sdk.syntax._
import xyz.stratalab.sdk.utils.Encoding

import scala.concurrent.duration._

trait InitializationModuleAlgebra[F[_]] {

  def setupWallet(
    fromFellowship: Fellowship,
    fromTemplate:   Template,
    groupId:        GroupId,
    seriesId:       SeriesId
  ): F[Unit]

}

object InitializationModule {

  def make[F[_]: Async: Logger](
    currentBitcoinNetworkHeight: Ref[F, Int],
    currentState:                Ref[F, SystemGlobalState]
  )(implicit
    bitcoind:          BitcoindRpcClient,
    tba:               TransactionBuilderApi[F],
    wsa:               WalletStateAlgebra[F],
    genusQueryAlgebra: IndexerQueryAlgebra[F]
  ) = new InitializationModuleAlgebra[F] {

    import WalletApiHelpers._

    import org.typelevel.log4cats.syntax._

    import cats.implicits._

    private def getTxos(
      fromFellowship: Fellowship,
      fromTemplate:   Template
    ): F[Seq[Txo]] = for {
      currentAddress <- getCurrentAddress[F](
        fromFellowship,
        fromTemplate,
        None
      )
      txos <- genusQueryAlgebra.queryUtxo(
        currentAddress
      )
    } yield txos

    private def sumLvls(txos: Seq[Txo]): Int128 =
      txos
        .map(
          _.transactionOutput.value.value.lvl
            .map(_.quantity)
            .getOrElse(longAsInt128(0))
        )
        .fold(longAsInt128(0))(_ + _)

    private def checkForLvls(
      fromFellowship: Fellowship,
      fromTemplate:   Template
    ): F[Unit] = (for {
      _              <- info"Checking for LVLs"
      currentAddress <- wsa.getCurrentAddress
      txos           <- getTxos(fromFellowship, fromTemplate)
      hasLvls <-
        if (txos.filter(_.transactionOutput.value.value.isLvl).nonEmpty) {
          (info"Found LVLs: ${int128AsBigInt(sumLvls(txos))}" >> currentState
            .update(
              _.copy(
                currentStatus = Some("LVLs found"),
                currentError = None,
                isReady = false
              )
            ) >>
          Async[F].pure(true))
        } else {
          warn"No LVLs found. Please fund the bridge wallet." >> currentState
            .update(
              _.copy(
                currentStatus = Some("Checking wallet..."),
                currentError = Some(
                  s"No LVLs found. Please fund the bridge wallet: $currentAddress"
                ),
                isReady = false
              )
            ) >>
          Async[F].pure(false)
        }
      _ <-
        if (!hasLvls)
          Async[F].sleep(5.second) >> checkForLvls(fromFellowship, fromTemplate)
        else Async[F].unit
    } yield ()).handleErrorWith { e =>
      e.printStackTrace()
      error"Error checking LVLs: $e" >>
      error"Retrying in 5 seconds" >>
      Async[F].sleep(
        5.second
      ) >> checkForLvls(fromFellowship, fromTemplate)
    }

    private def checkIfGroupTokenMinted(
      fromFellowship: Fellowship,
      fromTemplate:   Template
    ): F[Unit] = for {
      newTxos <- getTxos(fromFellowship, fromTemplate)
      _ <-
        if (newTxos.filter(_.transactionOutput.value.value.isGroup).nonEmpty) {
          info"Group Token minted successfully" >> currentState
            .update(
              _.copy(
                currentStatus = Some("Group Token minted"),
                currentError = None,
                isReady = false
              )
            )
        } else {
          info"Group Token not minted, checking txos in 5 seconds" >> currentState
            .update(
              _.copy(
                currentStatus = Some("Waiting for group tokens..."),
                currentError = None,
                isReady = false
              )
            ) >> Async[F].sleep(5.second) >> checkIfGroupTokenMinted(
            fromFellowship,
            fromTemplate
          )
        }
    } yield ()

    private def checkIfSeriesTokenMinted(
      fromFellowship: Fellowship,
      fromTemplate:   Template
    ): F[Unit] = for {
      newTxos <- getTxos(fromFellowship, fromTemplate)
      _ <-
        if (newTxos.filter(_.transactionOutput.value.value.isSeries).nonEmpty) {
          info"Series Token minted successfully" >> currentState
            .update(
              _.copy(
                currentStatus = Some("Series Token minted"),
                currentError = None,
                isReady = true
              )
            )
        } else {
          info"Series Token not minted, checking txos in 5 seconds" >> currentState
            .update(
              _.copy(
                currentStatus = Some("Waiting for series tokens..."),
                currentError = None,
                isReady = false
              )
            ) >> Async[F].sleep(5.second) >> checkIfSeriesTokenMinted(
            fromFellowship,
            fromTemplate
          )
        }
    } yield ()

    private def checkForGroupToken(
      fromFellowship: Fellowship,
      fromTemplate:   Template,
      groupId:        GroupId
    ): F[Boolean] = (
      for {
        _    <- info"Checking for Group Tokens"
        txos <- getTxos(fromFellowship, fromTemplate)
        groupTxos = txos.filter(_.transactionOutput.value.value.isGroup)
        hasGroupToken <-
          if (
            groupTxos
              .filter(
                _.transactionOutput.value.value.group.get.groupId == groupId
              )
              .nonEmpty
          ) {
            (info"Found Group Tokens" >> currentState
              .update(
                _.copy(
                  currentStatus = Some(
                    s"Group token found: ${Encoding.encodeToHex(groupTxos.head.transactionOutput.value.value.group.get.groupId.value.toByteArray())}"
                  ),
                  currentError = None,
                  isReady = false
                )
              ) >>
            Async[F].pure(true))
          } else {
            info"No Group Token found. Preparing to mint tokens." >> currentState
              .update(
                _.copy(
                  currentStatus = Some("Preparing to mint group tokens..."),
                  currentError = None,
                  isReady = false
                )
              ) >>
            Async[F].pure(false)
          }
      } yield hasGroupToken
    )

    private def setBlochainHeight() = for {
      tipHeight <- Async[F].fromFuture(Async[F].delay(bitcoind.getBlockCount()))
      _         <- info"Setting blockchain height to $tipHeight"
      _         <- currentBitcoinNetworkHeight.set(tipHeight)
    } yield ()

    private def checkForSeriesToken(
      fromFellowship: Fellowship,
      fromTemplate:   Template,
      seriesId:       SeriesId
    ): F[Boolean] = (
      for {
        _    <- info"Checking for Series Tokens"
        txos <- getTxos(fromFellowship, fromTemplate)
        seriesTxos = txos.filter(_.transactionOutput.value.value.isSeries)
        hasSeriesToken <-
          if (
            seriesTxos
              .filter(
                _.transactionOutput.value.value.series.get.seriesId == seriesId
              )
              .nonEmpty
          ) {
            (info"Found Series Tokens: ${int128AsBigInt(sumLvls(txos))}" >> currentState
              .update(
                _.copy(
                  currentStatus = Some("Series token found"),
                  currentError = None,
                  isReady = false
                )
              ) >>
            Async[F].pure(true))
          } else {
            info"No Series Token found. Preparing to mint tokens." >> currentState
              .update(
                _.copy(
                  currentStatus = Some("Preparing to mint series tokens..."),
                  currentError = None,
                  isReady = false
                )
              ) >>
            Async[F].pure(false)
          }
      } yield hasSeriesToken
    )

    def setupWallet(
      fromFellowship: Fellowship,
      fromTemplate:   Template,
      groupId:        GroupId,
      seriesId:       SeriesId
    ): F[Unit] =
      (for {
        _ <- checkForLvls(fromFellowship, fromTemplate)
        hasGroupToken <- checkForGroupToken(
          fromFellowship,
          fromTemplate,
          groupId
        )
        _ <-
          if (!hasGroupToken)
            error"There is no group token"
          else Async[F].unit
        hasSeriesToken <- checkForSeriesToken(
          fromFellowship,
          fromTemplate,
          seriesId
        )
        _ <-
          if (!hasSeriesToken) error"There is no series token"
          else Async[F].unit
        _ <- setBlochainHeight()
      } yield ()).handleErrorWith { e =>
        e.printStackTrace
        error"Error setting up wallet: $e" >>
        error"Retrying in 5 seconds" >>
        Async[F].sleep(
          5.second
        ) >> setupWallet(fromFellowship, fromTemplate, groupId, seriesId)
      }

  }
}
