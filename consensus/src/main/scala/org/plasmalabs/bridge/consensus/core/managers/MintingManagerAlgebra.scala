package org.plasmalabs.bridge.consensus.core.managers

import cats.data.EitherT
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.Queue
import io.grpc.ManagedChannel
import org.plasmalabs.bridge.consensus.core.managers.WalletApiHelpers.{getChangeLockPredicate, getCurrentIndices}
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.WaitingBTCOps.{getUtxos, startMintingProcess}
import org.plasmalabs.bridge.consensus.core.{Fellowship, PlasmaKeypair, Template}
import org.plasmalabs.bridge.consensus.shared.Lvl
import org.plasmalabs.bridge.shared.{BridgeError, UnknownError, ValidationPolicy}
import org.plasmalabs.quivr.models.Int128
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.{IndexerQueryAlgebra, WalletStateAlgebra}
import org.plasmalabs.sdk.models.LockAddress
import org.plasmalabs.sdk.syntax.int128AsBigInt
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger

case class StartMintingRequest(
  timestamp:     Long,
  clientNumber:  Int,
  fellowship:    Fellowship,
  template:      Template,
  redeemAddress: String,
  amount:        Int128
) {
  override def toString: String = s"Start Minting Request: Client Number: ${clientNumber}, Timestamp: ${timestamp}"
}

case class StartedMintingResponse(
  changeAddress:       LockAddress,
  groupValueToArrive:  BigInt,
  seriesValueToArrive: BigInt
)

trait MintingManagerAlgebra[F[_]] {

  /**
   * Expected Outcome:
   * Creates stream from unbounded queue to process minting requests one by one.
   * Processing the minting process fails if not able to get the changeLock. If getting the changeAddress is successful we start the minting Process for
   * the current request.
   * Continues with next request if funds are correctly minted and arrive at the change address in the correct amounts or certain amount of time passes.
   *
   * @param plasmaKeyPair
   * Used for starting the minting process and building the transaction.
   */
  def mintingStream(mintingManagerPolicy: ValidationPolicy)(implicit
    plasmaKeypair: PlasmaKeypair
  ): fs2.Stream[F, Unit]
}

object MintingManagerAlgebraImpl {

  import org.typelevel.log4cats.syntax._
  import cats.implicits._

  def make[F[_]: Async: Logger](
    queue: Queue[F, StartMintingRequest]
  )(implicit
    tba:               TransactionBuilderApi[F],
    walletApi:         WalletApi[F],
    wsa:               WalletStateAlgebra[F],
    utxoAlgebra:       IndexerQueryAlgebra[F],
    channelResource:   Resource[F, ManagedChannel],
    defaultMintingFee: Lvl
  ): MintingManagerAlgebra[F] =
    new MintingManagerAlgebra[F] {

      def mintingStream(mintingManagerPolicy: ValidationPolicy)(implicit
        plasmaKeypair: PlasmaKeypair
      ): fs2.Stream[F, Unit] = fs2.Stream
        .fromQueueUnterminated(queue)
        .evalMap { request =>
          for {
            mintingResponse <- processMintingRequest(request)
            _ <- mintingResponse match {
              case Right(response) =>
                mintingManagerPolicy.validate match {
                  case true =>
                    verifyMintingSpent(
                      mintingManagerPolicy.maxRetries,
                      response.changeAddress,
                      response.groupValueToArrive,
                      response.seriesValueToArrive
                    ).flatMap(success =>
                      info"${request.toString} - Minting process successfully started. Verification successful: ${success}."
                    )
                  case false => info"${request.toString} - Minting process successfully started."
                }
              case Left(error) =>
                error"${request.toString} - Minting process failed: ${error.getMessage}.".void
            }
          } yield ()
        }

      private def processMintingRequest(
        request: StartMintingRequest
      )(implicit plasmaKeypair: PlasmaKeypair): F[Either[BridgeError, StartedMintingResponse]] = (for {

        changeLock <- EitherT.fromOptionF(
          getCurrentIndices(request.fellowship, request.template, None)
            .flatMap { someCurrentIndeces =>
              getChangeLockPredicate[F](
                someCurrentIndeces,
                request.fellowship,
                request.template
              )
            },
          UnknownError("ChangeLock is None, unable to get address."): BridgeError
        )

        changeAddress <- EitherT(tba.lockAddress(changeLock).attempt)
          .leftMap(th => UnknownError(th.getMessage): BridgeError)

        txos <- EitherT(
          startMintingProcess(
            request.fellowship,
            request.template,
            request.redeemAddress,
            request.amount
          ).map(_._2).attempt
        ).leftMap(th => UnknownError(th.getMessage): BridgeError)

        unspentPlasmaBeforeMint = txos.filter(txo =>
          txo.transactionOutput.value.value.isGroup || txo.transactionOutput.value.value.isSeries
        )

        groupValueToArrive = unspentPlasmaBeforeMint
          .filter(_.transactionOutput.value.value.isGroup)
          .map(txo => int128AsBigInt(txo.transactionOutput.value.value.group.get.quantity))
          .sum
        seriesValueToArrive = unspentPlasmaBeforeMint
          .filter(_.transactionOutput.value.value.isSeries)
          .map(txo => int128AsBigInt(txo.transactionOutput.value.value.series.get.quantity))
          .sum

        response = StartedMintingResponse(changeAddress, groupValueToArrive, seriesValueToArrive)
      } yield response).value

      private def verifyMintingSpent(
        maxRetries:          Int,
        changeAddress:       LockAddress,
        groupValueToArrive:  BigInt,
        seriesValueToArrive: BigInt
      ): F[Boolean] = {
        def verifyUtxosMatch = (for {
          utxos <- getUtxos(changeAddress, utxoAlgebra)

          groupSumArrived = utxos
            .filter(_.transactionOutput.value.value.isGroup)
            .map(txo => int128AsBigInt(txo.transactionOutput.value.value.group.get.quantity))
            .sum

          seriesSumArrived = utxos
            .filter(_.transactionOutput.value.value.isSeries)
            .map(txo => int128AsBigInt(txo.transactionOutput.value.value.series.get.quantity))
            .sum

        } yield (seriesSumArrived >= seriesValueToArrive && groupSumArrived >= groupValueToArrive))
          .handleErrorWith(_ => Async[F].pure(false))

        def loop(retriesLeft: Int): F[Boolean] = (for {
          success <- verifyUtxosMatch
        } yield (success, retriesLeft - 1)).iterateUntil(response => response._1 == true || response._2 < 0).map(_._1)

        loop(maxRetries)
      }
    }
}
