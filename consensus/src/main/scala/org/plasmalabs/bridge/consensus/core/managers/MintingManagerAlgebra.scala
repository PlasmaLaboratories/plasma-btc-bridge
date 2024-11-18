package org.plasmalabs.bridge.consensus.core.managers

import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Queue
import io.grpc.ManagedChannel
import org.plasmalabs.bridge.consensus.core.managers.WalletApiHelpers.{getChangeLockPredicate, getCurrentIndices}
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.WaitingBTCOps.startMintingProcess
import org.plasmalabs.bridge.consensus.core.{Fellowship, PlasmaKeypair, Template}
import org.plasmalabs.bridge.consensus.shared.Lvl
import org.plasmalabs.bridge.shared.{BridgeError, UnknownError}
import org.plasmalabs.quivr.models.Int128
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.{IndexerQueryAlgebra, NodeQueryAlgebra, WalletStateAlgebra}
import org.plasmalabs.sdk.models.LockAddress
import org.plasmalabs.sdk.models.transaction.UnspentTransactionOutput
import org.plasmalabs.sdk.syntax.int128AsBigInt
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

case class StartMintingRequest(
  fellowship:    Fellowship,
  template:      Template,
  redeemAddress: String,
  amount:        Int128
)

case class StartedMintingResponse(
  changeAddress:       LockAddress,
  groupValueToArrive:  BigInt,
  seriesValueToArrive: BigInt
)

trait MintingManagerAlgebra[F[_]] {

  /**
   * Expected Outcome: Append a new request to the minting queue.
   *
   * @param request
   *   Containing fellowship, template, redeemAddress and amount.
   */
  def offer(request: StartMintingRequest): F[Unit]

  /**
   * Expected Outcome:
   * Creates stream from unbounded queue to process minting requests one by one.
   * Processing the minting process fails if not able to get the changeLock. If getting the changeAddress is successful we start the minting Process for
   * the current request.
   * Continues with next request if funds are correctly minted and arrive at the change address in the correct amounts or certain amount of time passes.
   *
   * @param nodeQueryAlgebra
   *   Used for checking the last two blocks if UTXOs arrived after starting the minting process.
   */
  def runMintingStream()(implicit
    plasmaKeypair:    PlasmaKeypair,
    nodeQueryAlgebra: NodeQueryAlgebra[F]
  ): fs2.Stream[F, Unit]
}

object MintingManagerAlgebraImpl {

  import org.typelevel.log4cats.syntax._
  import cats.implicits._

  def make[F[_]: Async: Logger](
  )(implicit
    tba:               TransactionBuilderApi[F],
    walletApi:         WalletApi[F],
    wsa:               WalletStateAlgebra[F],
    utxoAlgebra:       IndexerQueryAlgebra[F],
    channelResource:   Resource[F, ManagedChannel],
    defaultMintingFee: Lvl
  ): F[MintingManagerAlgebra[F]] = {

    for {
      startMintingRequestQueue <- Queue.unbounded[F, StartMintingRequest]
    } yield {
      val verifyingTimeout = 1.minute

      new MintingManagerAlgebra[F] {

        def offer(request: StartMintingRequest): F[Unit] = for {
          _ <- startMintingRequestQueue.offer(request)

        } yield ()

        def runMintingStream()(implicit
          plasmaKeypair:    PlasmaKeypair,
          nodeQueryAlgebra: NodeQueryAlgebra[F]
        ): fs2.Stream[F, Unit] = fs2.Stream
          .fromQueueUnterminated(startMintingRequestQueue)
          .evalMap { request =>
            for {
              mintingResponse <- processMintingRequest(request)
              _ <- mintingResponse match {
                case Right(response) =>
                  Async[F].race(
                    verifyMintingSpent(
                      response.changeAddress,
                      response.groupValueToArrive,
                      response.seriesValueToArrive
                    ),
                    Async[F].sleep(verifyingTimeout) >> error"Started the minting process but failed to verify it."
                  )
                case Left(error) =>
                  error"Minting process failed: ${error.getMessage()}".void
              }
            } yield ()
          }

        private def processMintingRequest(
          request: StartMintingRequest
        )(implicit plasmaKeypair: PlasmaKeypair): F[Either[BridgeError, StartedMintingResponse]] = for {
          response <- startMintingProcess(
            request.fellowship,
            request.template,
            request.redeemAddress,
            request.amount
          )

          changeLock <- for {
            someCurrentIndeces <- getCurrentIndices(request.fellowship, request.template, None)
            changeLock <- getChangeLockPredicate[F](
              someCurrentIndeces,
              request.fellowship,
              request.template
            )
          } yield (changeLock)

          lockForChange = changeLock match {
            case Some(lock) => lock
            case None       => return Async[F].pure(Left(UnknownError("ChangeLock is None, unable to get address.")))
          }

          changeAddress <- tba.lockAddress(
            lockForChange
          )

          unspentPlasmaBeforeMint = response._2.filter((txo) =>
            txo.transactionOutput.value.value.isGroup || txo.transactionOutput.value.value.isSeries || txo.transactionOutput.value.value.isLvl
          )

          groupValueToArrive = unspentPlasmaBeforeMint
            .filter(_.transactionOutput.value.value.isGroup)
            .map(txo => int128AsBigInt(txo.transactionOutput.value.value.group.get.quantity))
            .sum
          seriesValueToArrive = unspentPlasmaBeforeMint
            .filter(_.transactionOutput.value.value.isSeries)
            .map(txo => int128AsBigInt(txo.transactionOutput.value.value.series.get.quantity))
            .sum

        } yield Right(StartedMintingResponse(changeAddress, groupValueToArrive, seriesValueToArrive))

        private def verifyMintingSpent(
          changeAddress:       LockAddress,
          groupValueToArrive:  BigInt,
          seriesValueToArrive: BigInt
        )(implicit nodeQueryAlgebra: NodeQueryAlgebra[F]): F[Boolean] = {
          def checkBlocksForUtxosOnChangeAddress(
            depth: Long
          ): F[(Seq[UnspentTransactionOutput], Seq[UnspentTransactionOutput])] =
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
            blockResults <- (1L to 5L).toList.traverse(checkBlocksForUtxosOnChangeAddress)

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
