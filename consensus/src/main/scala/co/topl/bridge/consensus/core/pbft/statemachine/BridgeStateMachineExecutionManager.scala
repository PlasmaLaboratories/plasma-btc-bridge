package co.topl.bridge.consensus.core.pbft.statemachine

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.core.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.CheckpointInterval
import co.topl.bridge.consensus.core.CurrentBTCHeightRef
import co.topl.bridge.consensus.core.CurrentToplHeightRef
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.Fellowship
import co.topl.bridge.consensus.core.LastReplyMap
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.Template
import co.topl.bridge.consensus.core.ToplKeypair
import co.topl.bridge.consensus.core.controllers.StartSessionController
import co.topl.bridge.consensus.core.pbft.statemachine.ConfirmDepositBTCEvt
import co.topl.bridge.consensus.core.pbft.statemachine.ConfirmTBTCMintEvt
import co.topl.bridge.consensus.core.pbft.statemachine.PBFTEvent
import co.topl.bridge.consensus.core.pbft.statemachine.PBFTTransitionRelation
import co.topl.bridge.consensus.core.pbft.statemachine.PostClaimTxEvt
import co.topl.bridge.consensus.core.pbft.statemachine.PostDepositBTCEvt
import co.topl.bridge.consensus.core.pbft.statemachine.PostRedemptionTxEvt
import co.topl.bridge.consensus.core.pbft.statemachine.PostTBTCMintEvt
import co.topl.bridge.consensus.core.pbft.statemachine.UndoClaimTxEvt
import co.topl.bridge.consensus.core.pbft.statemachine.UndoDepositBTCEvt
import co.topl.bridge.consensus.core.pbft.statemachine.UndoTBTCMintEvt
import co.topl.bridge.consensus.core.stateDigest
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.service.InvalidInputRes
import co.topl.bridge.consensus.service.StartSessionRes
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.shared.AssetToken
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.consensus.shared.MiscUtils
import co.topl.bridge.consensus.shared.PeginSessionState
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionMintingTBTCConfirmation
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateSuccessfulPegin
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateTimeout
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForClaim
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForClaimBTCConfirmation
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForEscrowBTCConfirmation
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForRedemption
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.BridgeError
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.StartSessionOperation
import co.topl.bridge.shared.StateMachineRequest
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmClaimTx
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmTBTCMint
import co.topl.bridge.shared.StateMachineRequest.Operation.PostClaimTx
import co.topl.bridge.shared.StateMachineRequest.Operation.PostDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.PostRedemptionTx
import co.topl.bridge.shared.StateMachineRequest.Operation.PostTBTCMint
import co.topl.bridge.shared.StateMachineRequest.Operation.StartSession
import co.topl.bridge.shared.StateMachineRequest.Operation.TimeoutDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.TimeoutTBTCMint
import co.topl.bridge.shared.StateMachineRequest.Operation.UndoClaimTx
import co.topl.bridge.shared.StateMachineRequest.Operation.UndoDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.UndoTBTCMint
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.security.{KeyPair => JKeyPair}
import java.util.UUID
import co.topl.bridge.consensus.core.managers.WalletManagementUtils
import co.topl.bridge.consensus.core.ToplKeypair

trait BridgeStateMachineExecutionManager[F[_]] {

  def executeRequest(
      request: co.topl.bridge.shared.StateMachineRequest
  ): F[Unit]

}

object BridgeStateMachineExecutionManagerImpl {

  import org.typelevel.log4cats.syntax._
  import cats.implicits._
  import WaitingBTCOps._
  import WaitingForRedemptionOps._

  def make[F[_]: Async: Logger](
      keyPair: JKeyPair,
      walletManagementUtils: WalletManagementUtils[F],
      toplWalletSeedFile: String,
      toplWalletPassword: String
  )(implicit
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      replica: ReplicaId,
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      currentViewRef: CurrentViewRef[F],
      checkpointInterval: CheckpointInterval,
      sessionManager: SessionManagerAlgebra[F],
      currentBTCHeightRef: CurrentBTCHeightRef[F],
      btcNetwork: BitcoinNetworkIdentifiers,
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      currentToplHeight: CurrentToplHeightRef[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F],
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      utxoAlgebra: GenusQueryAlgebra[F],
      channelResource: Resource[F, ManagedChannel],
      defaultMintingFee: Lvl,
      lastReplyMap: LastReplyMap,
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      defaultFeePerByte: CurrencyUnit
  ) = {
    for {
      tKeyPair <- walletManagementUtils.loadKeys(
        toplWalletSeedFile,
        toplWalletPassword
      )
      state <- Ref.of[F, Map[String, PBFTState]](Map.empty)
    } yield {
      implicit val toplKeypair = new ToplKeypair(tKeyPair)
      new BridgeStateMachineExecutionManager[F] {

        private def startSession(
            clientNumber: Int,
            timestamp: Long,
            sc: StartSessionOperation
        ): F[Result] = {
          import StartSessionController._
          for {
            _ <-
              info"Received start session request from client ${clientNumber}"
            sessionId <- Sync[F].delay(
              sc.sessionId.getOrElse(UUID.randomUUID().toString)
            )
            res <- startPeginSession[F](
              sessionId,
              sc
            )
            viewNumber <- currentViewRef.underlying.get
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
            _ <- publicApiClientGrpcMap
              .underlying(ClientId(clientNumber))
              ._1
              .replyStartPegin(timestamp, viewNumber, resp)
          } yield resp
        }

        private def toEvt(op: StateMachineRequest.Operation)(implicit
            groupIdIdentifier: GroupId,
            seriesIdIdentifier: SeriesId
        ): PBFTEvent = {
          op match {
            case StateMachineRequest.Operation.Empty =>
              throw new Exception("Invalid operation")
            case StartSession(_) =>
              throw new Exception("Invalid operation")
            case TimeoutDepositBTC(_) =>
              throw new Exception("Invalid operation")
            case PostDepositBTC(value) =>
              PostDepositBTCEvt(
                sessionId = value.sessionId,
                height = value.height,
                txId = value.txId,
                vout = value.vout,
                amount =
                  Satoshis.fromBytes(ByteVector(value.amount.toByteArray))
              )
            case UndoDepositBTC(value) =>
              UndoDepositBTCEvt(
                sessionId = value.sessionId
              )
            case ConfirmDepositBTC(value) =>
              ConfirmDepositBTCEvt(
                sessionId = value.sessionId,
                height = value.height
              )
            case PostTBTCMint(value) =>
              import co.topl.brambl.syntax._
              PostTBTCMintEvt(
                sessionId = value.sessionId,
                height = value.height,
                utxoTxId = value.utxoTxId,
                utxoIdx = value.utxoIndex,
                amount = AssetToken(
                  Encoding.encodeToBase58(groupIdIdentifier.value.toByteArray),
                  Encoding.encodeToBase58(seriesIdIdentifier.value.toByteArray),
                  BigInt(value.amount.toByteArray())
                )
              )
            case TimeoutTBTCMint(_) =>
              throw new Exception("Invalid operation")
            case UndoTBTCMint(value) =>
              UndoTBTCMintEvt(
                sessionId = value.sessionId
              )
            case ConfirmTBTCMint(value) =>
              ConfirmTBTCMintEvt(
                sessionId = value.sessionId,
                height = value.height
              )
            case PostRedemptionTx(value) =>
              import co.topl.brambl.syntax._
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
            case UndoClaimTx(value) =>
              UndoClaimTxEvt(
                sessionId = value.sessionId
              )
            case ConfirmClaimTx(_) =>
              throw new Exception("Invalid operation")
          }

        }

        private def executeStateMachine(
            sessionId: String,
            pbftEvent: PBFTEvent
        ): F[Option[PBFTState]] = {
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
        }

        private def pbftStateToPeginSessionState(
            pbftState: PBFTState
        ): PeginSessionState =
          pbftState match {
            case _: PSWaitingForBTCDeposit => PeginSessionStateWaitingForBTC
            case _: PSConfirmingBTCDeposit =>
              PeginSessionWaitingForEscrowBTCConfirmation
            case _: PSMintingTBTC          => PeginSessionStateMintingTBTC
            case _: PSWaitingForRedemption => PeginSessionWaitingForRedemption
            case _: PSConfirmingTBTCMint => PeginSessionMintingTBTCConfirmation
            case _: PSClaimingBTC        => PeginSessionWaitingForClaim
            case _: PSConfirmingBTCClaim =>
              PeginSessionWaitingForClaimBTCConfirmation
          }

        private def standardResponse(
            clientNumber: Int,
            timestamp: Long,
            sessionId: String,
            value: StateMachineRequest.Operation
        ) = {
          for {
            viewNumber <- currentViewRef.underlying.get
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
            _ <- publicApiClientGrpcMap
              .underlying(ClientId(clientNumber))
              ._1
              .replyStartPegin(timestamp, viewNumber, Result.Empty)
          } yield someSessionInfo
        }

        private def sendResponse[F[_]: Sync](
            clientNumber: Int,
            timestamp: Long
        )(implicit
            currentView: CurrentViewRef[F],
            publicApiClientGrpcMap: PublicApiClientGrpcMap[F]
        ) = {
          for {
            viewNumber <- currentView.underlying.get
            _ <- publicApiClientGrpcMap
              .underlying(ClientId(clientNumber))
              ._1
              .replyStartPegin(timestamp, viewNumber, Result.Empty)
          } yield Result.Empty
        }

        private def executeRequestAux(
            request: co.topl.bridge.shared.StateMachineRequest
        ) =
          (request.operation match {
            case StateMachineRequest.Operation.Empty =>
              warn"Received empty message" >> Sync[F].delay(Result.Empty)
            case StartSession(sc) =>
              startSession(
                request.clientNumber,
                request.timestamp,
                sc
              )
            case PostDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case TimeoutDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              state.update(_ - (value.sessionId)) >>
                sessionManager.removeSession(
                  value.sessionId,
                  PeginSessionStateTimeout
                ) >> sendResponse(
                  request.clientNumber,
                  request.timestamp
                ) // FIXME: this is just a change of state at db level
            case UndoDepositBTC(
                  value
                ) => // FIXME: add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case ConfirmDepositBTC(
                  value
                ) =>
              import co.topl.brambl.syntax._
              for {
                _ <- trace"Deposit has been confirmed"
                someSessionInfo <- standardResponse(
                  request.clientNumber,
                  request.timestamp,
                  value.sessionId,
                  request.operation
                )
                _ <- trace"Minting: ${BigInt(value.amount.toByteArray())}"
                _ <- someSessionInfo
                  .flatMap(sessionInfo =>
                    MiscUtils.sessionInfoPeginPrism
                      .getOption(sessionInfo)
                      .map(peginSessionInfo =>
                        startMintingProcess[F](
                          defaultFromFellowship,
                          defaultFromTemplate,
                          peginSessionInfo.redeemAddress,
                          BigInt(value.amount.toByteArray())
                        )
                      )
                  )
                  .getOrElse(Sync[F].unit)
              } yield Result.Empty
            case PostTBTCMint(
                  value
                ) => // FIXME: add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case TimeoutTBTCMint(
                  value
                ) => // FIXME: Add checks before executing
              state.update(_ - value.sessionId) >>
                sessionManager.removeSession(
                  value.sessionId,
                  PeginSessionStateTimeout
                ) >> sendResponse(
                  request.clientNumber,
                  request.timestamp
                ) // FIXME: this is just a change of state at db level
            case UndoTBTCMint(
                  value
                ) => // FIXME: Add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case ConfirmTBTCMint(
                  value
                ) => // FIXME: Add checks before executing
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case PostRedemptionTx(
                  value
                ) => // FIXME: Add checks before executing
              for {
                someSessionInfo <- standardResponse(
                  request.clientNumber,
                  request.timestamp,
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
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case UndoClaimTx(value) =>
              standardResponse(
                request.clientNumber,
                request.timestamp,
                value.sessionId,
                request.operation
              ) >> Sync[F].delay(Result.Empty)
            case ConfirmClaimTx(value) =>
              state.update(_ - value.sessionId) >>
                sessionManager.removeSession(
                  value.sessionId,
                  PeginSessionStateSuccessfulPegin
                ) >> sendResponse(
                  request.clientNumber,
                  request.timestamp
                ) // FIXME: this is just a change of state at db level
          }).flatMap(x =>
            Sync[F].delay(
              lastReplyMap.underlying.put(
                (ClientId(request.clientNumber), request.timestamp),
                x
              )
            )
          )

        private def executeRequestF(
            request: co.topl.bridge.shared.StateMachineRequest,
            keyPair: JKeyPair,
            pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F]
        ) = {
          import co.topl.bridge.shared.implicits._
          import cats.implicits._
          for {
            currentSequence <- currentViewRef.underlying.get
            _ <- executeRequestAux(request)
            // here we start the checkpoint
            _ <-
              if (currentSequence % checkpointInterval.underlying == 0)
                for {
                  digest <- state.get.map(stateDigest)
                  checkpointRequest <- CheckpointRequest(
                    sequenceNumber = currentSequence,
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
            request: co.topl.bridge.shared.StateMachineRequest
        ): F[Unit] = {
          executeRequestF(
            request,
            keyPair,
            pbftProtocolClientGrpc
          )
        }
      }
    }
  }

}