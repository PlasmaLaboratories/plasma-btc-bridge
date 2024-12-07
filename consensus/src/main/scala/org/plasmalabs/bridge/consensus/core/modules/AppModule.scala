package org.plasmalabs.bridge.consensus.core.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import io.grpc.Metadata
import org.bitcoins.core.crypto.ExtPublicKey
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, _}
import org.plasmalabs.bridge.consensus.core.managers.{BTCWalletAlgebra, WalletManagementUtils}
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.{
  BridgeStateMachineExecutionManagerImpl,
  InternalCommunicationServiceServer
}
import org.plasmalabs.bridge.consensus.core.pbft.{
  CheckpointManagerImpl,
  PBFTInternalEvent,
  PBFTInternalGrpcServiceServer,
  PBFTRequestPreProcessorImpl,
  RequestStateManagerImpl,
  RequestTimerManagerImpl,
  ViewManagerImpl
}
import org.plasmalabs.bridge.consensus.core.{
  BridgeWalletManager,
  CheckpointInterval,
  CurrentBTCHeightRef,
  CurrentPlasmaHeightRef,
  Fellowship,
  KWatermark,
  LastReplyMap,
  PBFTInternalGrpcServiceClient,
  PeginWalletManager,
  PlasmaBTCBridgeConsensusParamConfig,
  PublicApiClientGrpcMap,
  SequenceNumberManager,
  StateMachineGrpcServiceServer,
  SystemGlobalState,
  Template,
  WatermarkRef,
  channelResource
}
import org.plasmalabs.bridge.consensus.service.StateMachineReply.Result
import org.plasmalabs.bridge.consensus.service.StateMachineServiceFs2Grpc
import org.plasmalabs.bridge.consensus.shared.persistence.StorageApi
import org.plasmalabs.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  BTCRetryThreshold,
  BTCWaitExpirationTime,
  Lvl,
  PlasmaConfirmationThreshold,
  PlasmaWaitExpirationTime
}
import org.plasmalabs.bridge.consensus.subsystems.monitor.{MonitorStateMachine, SessionEvent, SessionManagerImpl}
import org.plasmalabs.bridge.shared.{ClientId, ReplicaCount, ReplicaId, StateMachineServiceGrpcClient}
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.dataApi.IndexerQueryAlgebra
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import org.plasmalabs.sdk.servicekit.{
  FellowshipStorageApi,
  TemplateStorageApi,
  WalletKeyApi,
  WalletStateApi,
  WalletStateResource
}
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger

import java.security.{KeyPair => JKeyPair, PublicKey}
import java.util.concurrent.ConcurrentHashMap

trait AppModule extends WalletStateResource {

  def webUI() = HttpRoutes.of[IO] { case request @ GET -> Root =>
    StaticFile
      .fromResource("/static/index.html", Some(request))
      .getOrElseF(InternalServerError())
  }

  def createApp(
    replicaKeysMap:              Map[Int, PublicKey],
    replicaKeyPair:              JKeyPair,
    idReplicaClientMap:          Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
    params:                      PlasmaBTCBridgeConsensusParamConfig,
    queue:                       Queue[IO, SessionEvent],
    walletManager:               BTCWalletAlgebra[IO],
    pegInWalletManager:          BTCWalletAlgebra[IO],
    logger:                      Logger[IO],
    currentBitcoinNetworkHeight: Ref[IO, Int],
    seqNumberManager:            SequenceNumberManager[IO],
    currentPlasmaHeight:         Ref[IO, Long],
    currentState:                Ref[IO, SystemGlobalState],
    allReplicasPublicKeys:       List[(Int, ExtPublicKey)]
  )(implicit
    pbftProtocolClient:     PBFTInternalGrpcServiceClient[IO],
    publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
    clientId:               ClientId,
    storageApi:             StorageApi[IO],
    consensusClient:        StateMachineServiceGrpcClient[IO],
    replicaId:              ReplicaId,
    replicaCount:           ReplicaCount,
    fromFellowship:         Fellowship,
    fromTemplate:           Template,
    bitcoindInstance:       BitcoindRpcClient,
    btcRetryThreshold:      BTCRetryThreshold,
    groupIdIdentifier:      GroupId,
    seriesIdIdentifier:     SeriesId
  ) = {
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletRes = WalletStateResource.walletResource[IO](params.plasmaWalletDb)
    implicit val walletStateAlgebra = WalletStateApi
      .make[IO](walletRes, walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      params.plasmaNetwork.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )
    implicit val genusQueryAlgebra = IndexerQueryAlgebra.make[IO](
      channelResource(
        params.plasmaHost,
        params.plasmaPort,
        params.plasmaSecureConnection
      )
    )
    implicit val fellowshipStorageApi = FellowshipStorageApi.make(walletRes)
    implicit val templateStorageApi = TemplateStorageApi.make(walletRes)
    implicit val sessionManagerPermanent =
      SessionManagerImpl.makePermanent[IO](storageApi, queue)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    import org.plasmalabs.sdk.syntax._
    implicit val defaultMintingFee = Lvl(params.mintingFee)
    implicit val asyncForIO = IO.asyncForIO
    implicit val l = logger
    implicit val btcWaitExpirationTime = new BTCWaitExpirationTime(
      params.btcWaitExpirationTime
    )
    implicit val plasmaWaitExpirationTime = new PlasmaWaitExpirationTime(
      params.plasmaWaitExpirationTime
    )
    implicit val btcConfirmationThreshold = new BTCConfirmationThreshold(
      params.btcConfirmationThreshold
    )
    implicit val plasmaConfirmationThreshold = new PlasmaConfirmationThreshold(
      params.plasmaConfirmationThreshold
    )
    implicit val checkpointInterval = new CheckpointInterval(
      params.checkpointInterval
    )
    implicit val lastReplyMap = new LastReplyMap(
      new ConcurrentHashMap[(ClientId, Long), Result]()
    )
    implicit val defaultFeePerByte = params.feePerByte
    implicit val iPeginWalletManager = new PeginWalletManager(
      pegInWalletManager
    )
    implicit val iAllReplicasPublicKeys = allReplicasPublicKeys
    implicit val iBridgeWalletManager = new BridgeWalletManager(walletManager)
    implicit val btcNetwork = params.btcNetwork
    implicit val plasmaChannelResource = channelResource(
      params.plasmaHost,
      params.plasmaPort,
      params.plasmaSecureConnection
    )
    implicit val currentBTCHeightRef =
      new CurrentBTCHeightRef[IO](currentBitcoinNetworkHeight)
    implicit val currentPlasmaHeightRef = new CurrentPlasmaHeightRef[IO](
      currentPlasmaHeight
    )
    implicit val watermarkRef = new WatermarkRef[IO](
      Ref.unsafe[IO, (Long, Long)]((0, 0))
    )
    implicit val kWatermark = new KWatermark(params.kWatermark)
    import scala.concurrent.duration._
    for {
      queue             <- Queue.unbounded[IO, PBFTInternalEvent]
      checkpointManager <- CheckpointManagerImpl.make[IO]()
      requestTimerManager <- RequestTimerManagerImpl.make[IO](
        params.requestTimeout.seconds,
        queue
      )
      viewManager <- ViewManagerImpl.make[IO](
        params.viewChangeTimeout,
        storageApi,
        checkpointManager,
        requestTimerManager
      )
      bridgeStateMachineExecutionManager <-
        BridgeStateMachineExecutionManagerImpl
          .make[IO](
            replicaKeyPair,
            viewManager,
            walletManagementUtils,
            params.plasmaWalletSeedFile,
            params.plasmaWalletPassword
          )
      requestStateManager <- RequestStateManagerImpl
        .make[IO](
          viewManager,
          queue,
          requestTimerManager,
          bridgeStateMachineExecutionManager
        )
      peginStateMachine <- MonitorStateMachine
        .make[IO](
          currentBitcoinNetworkHeight,
          currentPlasmaHeight
        )
    } yield {
      implicit val iRequestStateManager = requestStateManager
      implicit val iRequestTimerManager = requestTimerManager
      implicit val iViewManager = viewManager
      implicit val iCheckpointManager = checkpointManager
      implicit val pbftReqProcessor = PBFTRequestPreProcessorImpl.make[IO](
        queue,
        viewManager,
        replicaKeysMap
      )
      (
        bridgeStateMachineExecutionManager,
        StateMachineGrpcServiceServer
          .stateMachineGrpcServiceServer(
            replicaKeyPair,
            pbftProtocolClient,
            idReplicaClientMap,
            seqNumberManager
          ),
        InitializationModule
          .make[IO](currentBitcoinNetworkHeight, currentState),
        peginStateMachine,
        PBFTInternalGrpcServiceServer
          .pbftInternalGrpcServiceServer(
            replicaKeysMap
          ),
        requestStateManager,
        InternalCommunicationServiceServer.internalCommunicationServiceServer[IO](
          Set(0, 1, 2, 3, 4, 5, 6), // TODO: Secure method to verify allowed hosts
          replicaId.id
        )
      )
    }
  }
}
