package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Fiber, Resource}
import cats.effect.std.Queue
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.plasmalabs.bridge.consensus.core.{Fellowship, Template}
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.WaitingBTCOps.startMintingProcess
import org.plasmalabs.bridge.consensus.core.StrataKeypair
import org.plasmalabs.sdk.wallet.WalletApi
import org.plasmalabs.sdk.dataApi.WalletStateAlgebra
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.IndexerQueryAlgebra
import io.grpc.ManagedChannel
import org.plasmalabs.bridge.consensus.shared.Lvl
import quivr.models.Int128

case class StartMintingRequest(
  fellowship: Fellowship,
  template: Template,
  redeemAddress: String,
  amount: Int128,
  toplKeyPair: StrataKeypair
)

trait MintingProcessManager[F[_]] {
  def offer(request: StartMintingRequest): F[Unit]
  def runStream(): Stream[F, Unit]
  def start: F[Fiber[F, Throwable, Unit]] // New method for starting background process
}

object MintingProcessManager {
  import cats.implicits._

  def make[F[_]](implicit 
    F: Async[F],
    logger: Logger[F],
    walletApi: WalletApi[F],
    wsa: WalletStateAlgebra[F],    
    tba: TransactionBuilderApi[F],
    utxoAlgebra: IndexerQueryAlgebra[F],
    channelResource: Resource[F, ManagedChannel],
    defaultMintingFee: Lvl,
  ): F[MintingProcessManager[F]] = {
    Queue.unbounded[F, StartMintingRequest].map { queue =>
      new MintingProcessManager[F] {
        def offer(request: StartMintingRequest): F[Unit] =
          queue.offer(request)

        def runStream(): Stream[F, Unit] =
          Stream
            .fromQueueUnterminated(queue)
            .evalMap { request =>
              (for {
                _ <- logger.trace(s"Processing minting request: request=$request")
                _ <- processMintingRequest(request)
              } yield ()).handleErrorWith { error =>
                logger.error(s"Failed to process minting request: $error") >>
                F.unit
              }
            }

        def start: F[Fiber[F, Throwable, Unit]] =
          F.start(runStream().compile.drain)

        private def processMintingRequest(request: StartMintingRequest): F[Unit] = {
          implicit val toplKeypair = request.toplKeyPair
          for {
            _ <- logger.info(s"Starting minting process for address ${request.redeemAddress} with amount ${request.amount}")
            response <- startMintingProcess[F](
              request.fellowship, 
              request.template, 
              request.redeemAddress, 
              request.amount
            )
            _ <- logger.debug(s"Completed minting process for ${request.redeemAddress}")
          } yield response
        }
      }
    }
  }
}