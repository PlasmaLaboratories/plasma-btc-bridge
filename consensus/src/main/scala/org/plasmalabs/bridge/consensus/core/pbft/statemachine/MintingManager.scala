package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Queue
import io.grpc.ManagedChannel
import org.plasmalabs.bridge.consensus.core.PlasmaKeypair
import org.plasmalabs.bridge.consensus.core.managers.WalletApiHelpers.{getChangeLockPredicate, getCurrentIndices}
import org.plasmalabs.bridge.consensus.core.managers.WalletManagementUtils
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.WaitingBTCOps.startMintingProcess
import org.plasmalabs.bridge.consensus.shared.Lvl
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.{IndexerQueryAlgebra, NodeQueryAlgebra, WalletStateAlgebra}
import org.plasmalabs.sdk.models.LockAddress
import org.plasmalabs.sdk.models.transaction.UnspentTransactionOutput
import org.plasmalabs.sdk.syntax.{int128AsBigInt, valueToQuantitySyntaxOps}
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

trait MintingManager[F[_]] {
  def offer(request:                     StartMintingRequest): F[Unit]
  def runMintingStream(nodeQueryAlgebra: NodeQueryAlgebra[F]): fs2.Stream[F, Unit]
}

object MintingManagerImpl {

  import org.typelevel.log4cats.syntax._
  import cats.implicits._

  def make[F[_]: Parallel: Async: Logger](
    walletManagementUtils: WalletManagementUtils[F],
    plasmaWalletSeedFile:  String,
    plasmaWalletPassword:  String
  )(implicit
    tba:               TransactionBuilderApi[F],
    walletApi:         WalletApi[F],
    wsa:               WalletStateAlgebra[F],
    utxoAlgebra:       IndexerQueryAlgebra[F],
    channelResource:   Resource[F, ManagedChannel],
    defaultMintingFee: Lvl
  ) = {
    for {
      tKeyPair <- walletManagementUtils.loadKeys(
        plasmaWalletSeedFile,
        plasmaWalletPassword
      )
      startMintingRequestQueue <- Queue.unbounded[F, StartMintingRequest]
    } yield {
      implicit val plasmaKeypair = new PlasmaKeypair(tKeyPair)
      new MintingManager[F] {

        def offer(request: StartMintingRequest) = for {
          _ <- startMintingRequestQueue.offer(request)
        } yield ()

        def runMintingStream(nodeQueryAlgebra: NodeQueryAlgebra[F]): fs2.Stream[F, Unit] = fs2.Stream
          .fromQueueUnterminated(startMintingRequestQueue)
          .evalMap { request =>
            for {
              _ <- processMintingRequest(nodeQueryAlgebra, request)
            } yield ()
          }

        private def processMintingRequest(
          nodeQueryAlgebra: NodeQueryAlgebra[F],
          request:          StartMintingRequest
        ) = for {
          _ <- info"Starting new minting process"

          response <- startMintingProcess(
            request.fellowship,
            request.template,
            request.redeemAddress,
            request.amount
          )
          unspentPlasmaBeforeMint = response._2.filter((txo) =>
            txo.transactionOutput.value.value.isGroup || txo.transactionOutput.value.value.isSeries || txo.transactionOutput.value.value.isLvl
          )
          changeLock <- for {
            someNextIndices <- getCurrentIndices(request.fellowship, request.template, None)
            changeLock <- getChangeLockPredicate[F](
              someNextIndices,
              request.fellowship,
              request.template
            )
          } yield (changeLock)

          lockForChange = changeLock match {
            case Some(lock) => lock
            case None       => throw new RuntimeException("changeLock is None, unable to get address")
          }

          changeAddress <- tba.lockAddress(
            lockForChange
          )

          groupValueToArrive = unspentPlasmaBeforeMint
            .filter(_.transactionOutput.value.value.isGroup)
            .map(txo => int128AsBigInt(valueToQuantitySyntaxOps(txo.transactionOutput.value.value).quantity))
            .sum
          seriesValueToArrive = unspentPlasmaBeforeMint
            .filter(_.transactionOutput.value.value.isSeries)
            .map(txo => int128AsBigInt(valueToQuantitySyntaxOps(txo.transactionOutput.value.value).quantity))
            .sum

          _ <- for {
            mintingSuccessful <- verifyMintingSpent(
              nodeQueryAlgebra,
              changeAddress,
              groupValueToArrive,
              seriesValueToArrive
            )
          } yield mintingSuccessful
          _ <- info"Processing minting successful, continue with next Request"
        } yield ()

        private def verifyMintingSpent(
          nodeQueryAlgebra:    NodeQueryAlgebra[F],
          changeAddress:       LockAddress,
          groupValueToArrive:  BigInt,
          seriesValueToArrive: BigInt
        ): F[Boolean] = {
          def checkBlocks(depth: Long): F[(Seq[UnspentTransactionOutput], Seq[UnspentTransactionOutput])] =
            nodeQueryAlgebra.blockByDepth(depth).map {
              case Some(block) =>
                val utxosForChangeAddress = block._4
                  .flatMap(_.outputs)
                  .filter(_.address == changeAddress)

                val seriesTxs = utxosForChangeAddress.filter(_.value.value.isSeries)
                val groupTxs = utxosForChangeAddress.filter(_.value.value.isGroup)
                (seriesTxs, groupTxs)
              case None => (Seq(), Seq())
            }

          (for {
            blockResults <- (1L to 3L).toList.parTraverse(checkBlocks)

            (allSeriesTxs, allGroupTxs) = blockResults.foldLeft(
              (Seq.empty[UnspentTransactionOutput], Seq.empty[UnspentTransactionOutput])
            ) { case ((accSeries, accGroup), (series, group)) =>
              (accSeries ++ series, accGroup ++ group)
            }

            seriesSumArrived = allSeriesTxs.map(txo => int128AsBigInt(txo.value.value.series.get.quantity)).sum
            groupSumArrived = allGroupTxs.map(txo => int128AsBigInt(txo.value.value.group.get.quantity)).sum

            _ <- Async[F].sleep(1.second)

          } yield seriesSumArrived == seriesValueToArrive && groupSumArrived == groupValueToArrive)
            .handleErrorWith(_ => Async[F].pure(false))
            .iterateUntil(_ == true)
        }

      }
    }
  }

}
