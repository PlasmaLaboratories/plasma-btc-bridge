package org.plasmalabs.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.{Async, Ref, Resource, Sync}
import cats.effect.std.Queue
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.WaitingBTCOps.{startMintingProcess, getUtxos}
import scala.concurrent.duration._
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.plasmalabs.bridge.consensus.core.controllers.StartSessionController
import org.plasmalabs.bridge.consensus.core.managers.WalletManagementUtils
import org.plasmalabs.bridge.consensus.core.pbft.ViewManager
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.PBFTEvent
import org.plasmalabs.bridge.consensus.core.{
  BitcoinNetworkIdentifiers,
  BridgeWalletManager,
  CheckpointInterval,
  CurrentBTCHeightRef,
  CurrentStrataHeightRef,
  Fellowship,
  LastReplyMap,
  PeginWalletManager,
  PublicApiClientGrpcMap,
  StrataKeypair,
  Template,
  stateDigest
}
import org.plasmalabs.bridge.consensus.pbft.CheckpointRequest
import org.plasmalabs.bridge.consensus.service.StateMachineReply.Result
import org.plasmalabs.bridge.consensus.service.{InvalidInputRes, StartSessionRes, StateMachineReply}
import org.plasmalabs.bridge.consensus.shared.PeginSessionState.{
  PeginSessionStateMintingTBTC,
  PeginSessionStateSuccessfulPegin,
  PeginSessionStateTimeout,
  PeginSessionStateWaitingForBTC,
  PeginSessionWaitingForClaim
}
import org.plasmalabs.bridge.consensus.shared.{
  AssetToken,
  BTCWaitExpirationTime,
  Lvl,
  MiscUtils,
  PeginSessionState,
  StrataWaitExpirationTime
}
import org.plasmalabs.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import org.plasmalabs.bridge.shared.StateMachineRequest.Operation.{
  PostClaimTx,
  PostDepositBTC,
  PostRedemptionTx,
  StartSession,
  TimeoutDepositBTC,
  TimeoutTBTCMint
}
import org.plasmalabs.bridge.shared.{
  BridgeCryptoUtils,
  BridgeError,
  ClientId,
  ReplicaCount,
  ReplicaId,
  StartSessionOperation,
  StateMachineRequest
}
import org.plasmalabs.consensus.core.PBFTInternalGrpcServiceClient
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.{
  FellowshipStorageAlgebra,
  IndexerQueryAlgebra,
  TemplateStorageAlgebra,
  WalletStateAlgebra
}
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import org.plasmalabs.sdk.utils.Encoding
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.security.{KeyPair => JKeyPair}
import java.util.UUID
import quivr.models.Int128

case class StartMintingRequest(
 fellowship: Fellowship,
 template: Template,
 redeemAddress: String,
 amount: Int128,
 toplKeyPair: StrataKeypair
)

import java.util.concurrent.TimeUnit

trait BridgeStateMachineExecutionManager[F[_]] {

  def executeRequest(
    sequenceNumber: Long,
    request:        org.plasmalabs.bridge.shared.StateMachineRequest
  ): F[Unit]

  def runStream(): fs2.Stream[F, Unit]

  def runMintingStream(): fs2.Stream[F, Unit]

}

object BridgeStateMachineExecutionManagerImpl {

  import org.typelevel.log4cats.syntax._
  import cats.implicits._
  import WaitingForRedemptionOps._

  def make[F[_]: Async: Logger](
    keyPair:               JKeyPair,
    viewManager:           ViewManager[F],
    walletManagementUtils: WalletManagementUtils[F],
    toplWalletSeedFile:    String,
    toplWalletPassword:    String
  )(implicit
    pbftProtocolClientGrpc:   PBFTInternalGrpcServiceClient[F],
    replica:                  ReplicaId,
    publicApiClientGrpcMap:   PublicApiClientGrpcMap[F],
    checkpointInterval:       CheckpointInterval,
    sessionManager:           SessionManagerAlgebra[F],
    currentBTCHeightRef:      CurrentBTCHeightRef[F],
    btcNetwork:               BitcoinNetworkIdentifiers,
    pegInWalletManager:       PeginWalletManager[F],
    bridgeWalletManager:      BridgeWalletManager[F],
    fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
    templateStorageAlgebra:   TemplateStorageAlgebra[F],
    toplWaitExpirationTime:   StrataWaitExpirationTime,
    btcWaitExpirationTime:    BTCWaitExpirationTime,
    tba:                      TransactionBuilderApi[F],
    currentStrataHeight:      CurrentStrataHeightRef[F],
    walletApi:                WalletApi[F],
    wsa:                      WalletStateAlgebra[F],
    groupIdIdentifier:        GroupId,
    seriesIdIdentifier:       SeriesId,
    utxoAlgebra:              IndexerQueryAlgebra[F],
    channelResource:          Resource[F, ManagedChannel],
    defaultMintingFee:        Lvl,
    lastReplyMap:             LastReplyMap,
    defaultFromFellowship:    Fellowship,
    defaultFromTemplate:      Template,
    bitcoindInstance:         BitcoindRpcClient,
    replicaCount:             ReplicaCount,
    defaultFeePerByte:        CurrencyUnit
  ) = {
    for {
      tKeyPair <- walletManagementUtils.loadKeys(
        toplWalletSeedFile,
        toplWalletPassword
      )
      state              <- Ref.of[F, Map[String, PBFTState]](Map.empty)
      queue              <- Queue.unbounded[F, (Long, StateMachineRequest)]
      startMintingRequestQueue <- Queue.unbounded[F, StartMintingRequest]
      elegibilityManager <- ExecutionElegibilityManagerImpl.make[F]()
    } yield {
      implicit val toplKeypair = new StrataKeypair(tKeyPair)
      new BridgeStateMachineExecutionManager[F] {

        def runStream(): fs2.Stream[F, Unit] =
          fs2.Stream
            .fromQueueUnterminated[F, (Long, StateMachineRequest)](queue)
            .evalMap(x =>
              trace"Appending request: ${x._1}, ${x._2}" >>
              elegibilityManager.appendOrUpdateRequest(
                x._1,
                x._2
              )
            )
            .flatMap(_ =>
              fs2.Stream
                .unfoldEval[F, Unit, (Long, StateMachineRequest)](())(_ =>
                  elegibilityManager.getNextExecutableRequest().map(_.map(x => ((x._1, x._2), ())))
                )
            )
            .evalMap(x => trace"Executing the request: ${x._2}" >> executeRequestF(x._1, x._2))

        def runMintingStream(): fs2.Stream[F, Unit] = fs2.Stream
           .fromQueueUnterminated(startMintingRequestQueue)
           .evalMap ( request => {
              import org.plasmalabs.indexer.services.TxoState
              implicit val toplKeypair = request.toplKeyPair

              for {
               _ <- info"Processing minting request"
               response <- startMintingProcess(
                  request.fellowship,
                  request.template,
                  request.redeemAddress,
                  request.amount
                )
              _ <- (for {
                afterMintUtxos <- getUtxos(
                  response._1,
                  utxoAlgebra, 
                  txoState = TxoState.SPENT
                )
                result <- verifyMintingSpent(response._2, afterMintUtxos)
                _ <- info"Matching spent txos: ${result._1}"
                _ <- Async[F].sleep(FiniteDuration(1, TimeUnit.SECONDS))
              } yield result._1).iterateUntil(_ == true)
             } yield ()
           }
             .handleErrorWith { error =>
               error"Failed to process minting request: $error" >>
               Async[F].unit
             }
           )
          import org.plasmalabs.indexer.services.Txo
          import org.plasmalabs.indexer.services.TxoState
          
          // TODO: UTXOs before mint can include more utxos then necessary, currently checked if all utxos match spent utxos
          def verifyMintingSpent(beforeMintTxos: Seq[Txo], afterMintTxos: Seq[Txo]) = {

            def safeSum(matchingSpent: Seq[Txo], predicate: Txo => Boolean): BigInt = {
              val values = matchingSpent
                .filter(predicate)
                .map(txo => Option(txo.transactionOutput.value.getAsset.quantity))
                .collect { case Some(quantity) if quantity.toString.nonEmpty => BigInt(quantity.toString) }
              
              if (values.isEmpty) BigInt(0)
              else values.sum
            }

            for {
              _ <- info"Verifying the Minting spent" 
              // Get all spent and unspent UTXOs from before mint
              unspentBefore = beforeMintTxos.filter(_.state == TxoState.UNSPENT)
              spentBefore = beforeMintTxos.filter(_.state == TxoState.SPENT)
            // filtering out the newly spent txos 
             newlySpent = afterMintTxos.filter(spentTxo =>
              !spentBefore.exists(previouslySpentTxo =>
                spentTxo.outputAddress == previouslySpentTxo.outputAddress
              ) &&
              spentTxo.state == TxoState.SPENT
            )

            // we match newly spent to unspent before mint 
             matchingSpent = newlySpent.filter(spentTxo => 
              unspentBefore.exists(unspentTxo => 
                spentTxo.outputAddress == unspentTxo.outputAddress &&
                spentTxo.state == TxoState.SPENT
              )
            )

            _ <- for {
              _ <- info"Check matching Spent cumulative values"
              valueGroup = safeSum(matchingSpent, ((txo) => txo.transactionOutput.value.value.isGroup)).handleErrorWith{e => error"Error happened checking conversion ${e.getMessage()}" >> Async[F].pure( 0)}
              valueSeries = safeSum(matchingSpent, ((txo) => txo.transactionOutput.value.value.isSeries)).handleErrorWith{e => error"Error happened checking conversion ${e.getMessage()}" >> Async[F].pure( 0)}
              valuedLvl = safeSum(matchingSpent, ((txo) => txo.transactionOutput.value.value.isLvl)).handleErrorWith{e => error"Error happened checking conversion ${e.getMessage()}" >> Async[F].pure(0)}

              _ <- info"Cumulative values - Group: $valueGroup, Series: $valueSeries, Level: $valuedLvl"
            } yield ()
            

            // these need the cumulation of values, currently only check if all necessary are there but not check if the values matched
             spentHasGroup = matchingSpent.exists(_.transactionOutput.value.value.isGroup) 
             spentHasSeries = matchingSpent.exists(_.transactionOutput.value.value.isSeries)
             spentHasLvl = matchingSpent.exists(_.transactionOutput.value.value.isLvl)
            } yield (spentHasGroup && spentHasSeries && spentHasLvl, matchingSpent)
          }


        private def startSession(
          clientNumber: Int,
          sc:           StartSessionOperation
        ): F[Result] = {
          import StartSessionController._
          for {
            _ <-
              info"Received start session request from client ${clientNumber}"
            sessionId <- Sync[F].delay(
              sc.sessionId.getOrElse(UUID.randomUUID().toString)
            )
            _ <- debug"Session ID: $sessionId"
            res <- startPeginSession[F](
              sessionId,
              sc
            )
            currentBTCHeight <- currentBTCHeightRef.underlying.get
            resp <- res match {
              case Left(e: BridgeError) =>
                Sync[F].delay(
                  Result.InvalidInput(
                    InvalidInputRes(
                      e.error
                    )
                  )
                )
              case Right((sessionInfo, response)) =>
                state.update(
                  _.+(
                    sessionId ->
                    PSWaitingForBTCDeposit(
                      height = currentBTCHeight,
                      currentWalletIdx = sessionInfo.btcPeginCurrentWalletIdx,
                      scriptAsm = sessionInfo.scriptAsm,
                      escrowAddress = sessionInfo.escrowAddress,
                      redeemAddress = sessionInfo.redeemAddress,
                      claimAddress = sessionInfo.claimAddress
                    )
                  )
                ) >> sessionManager
                  .createNewSession(sessionId, sessionInfo) >> Sync[F].delay(
                  Result.StartSession(
                    StartSessionRes(
                      sessionId = response.sessionID,
                      script = response.script,
                      escrowAddress = response.escrowAddress,
                      descriptor = response.descriptor,
                      minHeight = response.minHeight,
                      maxHeight = response.maxHeight
                    )
                  )
                )
            }
          } yield resp
        }

        private def toEvt(op: StateMachineRequest.Operation)(implicit
          groupIdIdentifier:  GroupId,
          seriesIdIdentifier: SeriesId
        ): PBFTEvent =
          op match {
            case StateMachineRequest.Operation.Empty =>
              throw new Exception("Invalid operation")
            case StartSession(_) =>
              throw new Exception("Invalid operation")
            case TimeoutTBTCMint(value) =>
              TimeoutMinting(value.sessionId)
            case TimeoutDepositBTC(value) =>
              TimeoutDeposit(value.sessionId)
            case PostDepositBTC(value) =>
              PostDepositBTCEvt(
                sessionId = value.sessionId,
                height = value.height,
                txId = value.txId,
                vout = value.vout,
                amount = Satoshis.fromBytes(ByteVector(value.amount.toByteArray))
              )
            case PostRedemptionTx(value) =>
              import org.plasmalabs.sdk.syntax._
              PostRedemptionTxEvt(
                sessionId = value.sessionId,
                secret = value.secret,
                height = value.height,
                utxoTxId = value.utxoTxId,
                utxoIdx = value.utxoIndex,
                amount = AssetToken(
                  Encoding.encodeToBase58(groupIdIdentifier.value.toByteArray),
                  Encoding.encodeToBase58(seriesIdIdentifier.value.toByteArray),
                  BigInt(value.amount.toByteArray())
                )
              )
            case PostClaimTx(value) =>
              PostClaimTxEvt(
                sessionId = value.sessionId,
                height = value.height,
                txId = value.txId,
                vout = value.vout
              )
          }

        private def executeStateMachine(
          sessionId: String,
          pbftEvent: PBFTEvent
        ): F[Option[PBFTState]] =
          for {
            currentState <- state.get.map(_.apply(sessionId))
            newState = PBFTTransitionRelation
              .handlePBFTEvent(
                currentState,
                pbftEvent
              )
            _ <- state.update(x =>
              newState
                .map(y =>
                  x.updated(
                    sessionId,
                    y
                  )
                )
                .getOrElse(x)
            )
          } yield newState

        private def pbftStateToPeginSessionState(
          pbftState: PBFTState
        ): PeginSessionState =
          pbftState match {
            case _: PSWaitingForBTCDeposit => PeginSessionStateWaitingForBTC
            case _: PSMintingTBTC          => PeginSessionStateMintingTBTC
            case _: PSClaimingBTC          => PeginSessionWaitingForClaim
          }

        private def standardResponse(
          sessionId: String,
          value:     StateMachineRequest.Operation
        ) =
          for {
            newState <- executeStateMachine(
              sessionId,
              toEvt(value)
            )
            someSessionInfo <- sessionManager.updateSession(
              sessionId,
              x =>
                newState
                  .map(y =>
                    x.copy(
                      mintingBTCState = pbftStateToPeginSessionState(y)
                    )
                  )
                  .getOrElse(x)
            )
          } yield someSessionInfo

        private def executeRequestAux(
          request: org.plasmalabs.bridge.shared.StateMachineRequest
        ): F[StateMachineReply.Result] =
          (request.operation match {
            case StateMachineRequest.Operation.Empty =>
              // This is just a no-op for the when an operation
              // is used to vote on the result but requires no
              // action
              trace"No op" >> Sync[F].delay(Result.Empty)
            case StartSession(sc) =>
              trace"handling StartSession" >> startSession(
                request.clientNumber,
                sc
              )
            case PostDepositBTC(
                  value
                ) =>
              import org.plasmalabs.sdk.syntax._
              for {
                _ <- debug"handling PostDepositBTC ${value.sessionId}"
                someSessionInfo <- standardResponse(
                  value.sessionId,
                  request.operation
                )
                currentPrimary <- viewManager.currentPrimary
                _ <- someSessionInfo
                 .flatMap(sessionInfo =>
                   if (currentPrimary == replica.id)
                     MiscUtils.sessionInfoPeginPrism
                       .getOption(sessionInfo)
                       .map(
                         peginSessionInfo =>
                           startMintingRequestQueue.offer(
                             StartMintingRequest(
                               defaultFromFellowship,
                                defaultFromTemplate,
                               peginSessionInfo.redeemAddress,
                               BigInt(value.amount.toByteArray()),
                               toplKeypair)
                             )
                         )
                   else None
                 )
                 .getOrElse(Sync[F].unit)
             } yield Result.Empty
            case TimeoutDepositBTC(value) =>
              trace"handling TimeoutDepositBTC ${value.sessionId}" >> state.update(_ - (value.sessionId)) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> Result.Empty.pure[F]
            case TimeoutTBTCMint(
                  value
                ) =>
              trace"handling TimeoutTBTCMint ${value.sessionId}" >> state.update(_ - value.sessionId) >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateTimeout
              ) >> Result.Empty.pure[F]
            case PostRedemptionTx(
                  value
                ) =>
              for {
                _ <- trace"handling PostRedemptionTx ${value.sessionId}"
                someSessionInfo <- standardResponse(
                  value.sessionId,
                  request.operation
                )
                _ <- (for {
                  sessionInfo <- someSessionInfo
                  peginSessionInfo <- MiscUtils.sessionInfoPeginPrism
                    .getOption(sessionInfo)
                } yield startClaimingProcess[F](
                  value.secret,
                  peginSessionInfo.claimAddress,
                  peginSessionInfo.btcBridgeCurrentWalletIdx,
                  value.txId,
                  value.vout,
                  peginSessionInfo.scriptAsm, // scriptAsm,
                  Satoshis
                    .fromLong(
                      BigInt(value.amount.toByteArray()).toLong
                    )
                )).getOrElse(Sync[F].unit)
              } yield Result.Empty
            case PostClaimTx(value) =>
              debug"handling PostClaimTx ${value.sessionId}" >>
              sessionManager.removeSession(
                value.sessionId,
                PeginSessionStateSuccessfulPegin
              ) >> Result.Empty.pure[F]
          }).flatMap(x =>
            Sync[F].delay(
              lastReplyMap.underlying.put(
                (ClientId(request.clientNumber), request.timestamp),
                x
              )
            ) >> Sync[F].delay(x)
          )

        private def executeRequestF(
          sequenceNumber: Long,
          request:        org.plasmalabs.bridge.shared.StateMachineRequest
        ) = {
          import org.plasmalabs.bridge.shared.implicits._
          import cats.implicits._
          for {
            resp        <- executeRequestAux(request)
            currentView <- viewManager.currentView
            // here we start the checkpoint
            _ <-
              if (!request.operation.isEmpty)
                info"Replying to start pegin request: $resp" >>
                publicApiClientGrpcMap
                  .underlying(ClientId(request.clientNumber))
                  ._1
                  .replyStartPegin(request.timestamp, currentView, resp)
              else Async[F].unit
            _ <-
              if (sequenceNumber % checkpointInterval.underlying == 0)
                for {
                  digest <- state.get.map(stateDigest)
                  checkpointRequest <- CheckpointRequest(
                    sequenceNumber = sequenceNumber,
                    digest = ByteString.copyFrom(digest),
                    replicaId = replica.id
                  ).pure[F]
                  signedBytes <- BridgeCryptoUtils.signBytes[F](
                    keyPair.getPrivate(),
                    checkpointRequest.signableBytes
                  )
                  _ <- pbftProtocolClientGrpc.checkpoint(
                    checkpointRequest.withSignature(
                      ByteString.copyFrom(signedBytes)
                    )
                  )
                } yield ()
              else Async[F].unit
          } yield ()
        }

        def executeRequest(
          sequenceNumber: Long,
          request:        org.plasmalabs.bridge.shared.StateMachineRequest
        ): F[Unit] =
          for {
            _ <- queue.offer((sequenceNumber, request))
          } yield ()
      }
    }
  }

}
