package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.std.Queue
import io.grpc.ManagedChannel
import org.plasmalabs.bridge.consensus.core.managers.WalletApiHelpers.{getChangeLockPredicate, getCurrentIndices}
import org.plasmalabs.bridge.consensus.core.managers.WalletManagementUtils
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.WaitingBTCOps.startMintingProcess
import org.plasmalabs.bridge.consensus.core.PlasmaKeypair
import org.plasmalabs.bridge.consensus.shared.Lvl
import org.plasmalabs.sdk.dataApi.NodeQueryAlgebra
import org.plasmalabs.sdk.models.transaction.UnspentTransactionOutput
import cats.Parallel
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.{IndexerQueryAlgebra, WalletStateAlgebra}
import org.plasmalabs.sdk.models.LockAddress
import org.plasmalabs.sdk.syntax.{int128AsBigInt, valueToQuantitySyntaxOps}
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._
import org.plasmalabs.bridge.consensus.core.managers.WalletApiHelpers.getCurrentAddress
import java.util.concurrent.ConcurrentHashMap

trait MintingManager[F[_]] {
  def runMintingStream: fs2.Stream[F, Unit]
  def offer(request: StartMintingRequest): F[Unit]
}

object MintingManagerImpl {

  import org.typelevel.log4cats.syntax._
  import cats.implicits._

  def make[F[_]: Parallel: Async: Logger](
    walletManagementUtils: WalletManagementUtils[F],
    plasmaWalletSeedFile:  String,
    plasmaWalletPassword:  String,
    nodeQueryAlgebra:      NodeQueryAlgebra[F]
  )(implicit
    tba:               TransactionBuilderApi[F],
    walletApi:         WalletApi[F],
    wsa:               WalletStateAlgebra[F],
    utxoAlgebra:       IndexerQueryAlgebra[F],
    channelResource:   Resource[F, ManagedChannel],
    defaultMintingFee: Lvl
  ) = {
    val currentMintingProcessesMap =
      new ConcurrentHashMap[
        LockAddress,
        String
      ]()

    for {
      tKeyPair <- walletManagementUtils.loadKeys(
        plasmaWalletSeedFile,
        plasmaWalletPassword
      )
      startMintingRequestQueue <- Queue.unbounded[F, StartMintingRequest]
    } yield {
      implicit val plasmaKeypair = new PlasmaKeypair(tKeyPair)
      new MintingManager[F] {

        def runMintingStream: fs2.Stream[F, Unit] = fs2.Stream
          .fromQueueUnterminated(startMintingRequestQueue)
          .evalMap { request =>
            for {
              _ <- info"Processing new minting request"

              currentAddress <- getCurrentAddress[F](
                request.fellowship,
                request.template,
                None
              )

              currentProcess <- Sync[F].delay(currentMintingProcessesMap.get(currentAddress))
              x = Option(currentProcess) match {
                case Some(_) =>
                  for {
                    _ <- Async[F].sleep(3.second)
                    _ <- offer(request)
                  } yield ()
                case None => for {
                  _ <- Async[F].start(processMintingRequest(request, currentAddress))
                } yield ()
              }

            } yield ()
          }

        def offer(request: StartMintingRequest) = for {
          _ <- startMintingRequestQueue.offer(request)
        } yield ()

        private def processMintingRequest(request: StartMintingRequest, currentAddress: LockAddress) = for {
                  _ <- Sync[F].delay(currentMintingProcessesMap.put(currentAddress, "running"))

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
          _ <- Sync[F].delay(currentMintingProcessesMap.remove(currentAddress))
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
