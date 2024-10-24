package org.plasmalabs.bridge.consensus.core

import cats.effect.kernel.{Async, Ref, Sync}
import cats.effect.std.{Mutex, Queue}
import cats.effect.{ExitCode, IO, IOApp}
import com.google.protobuf.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import io.grpc.netty.NettyServerBuilder
import io.grpc.{ManagedChannelBuilder, Metadata}
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.rpc.config.BitcoindAuthCredentials
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.plasmalabs.bridge.consensus.core.managers.{BTCWalletAlgebra, BTCWalletAlgebraImpl}
import org.plasmalabs.bridge.consensus.core.modules.AppModule
import org.plasmalabs.bridge.consensus.core.utils.KeyGenerationUtils
import org.plasmalabs.bridge.consensus.core.{
  ConsensusParamsDescriptor,
  ServerConfig,
  StrataBTCBridgeConsensusParamConfig
}
import org.plasmalabs.bridge.consensus.service.StateMachineServiceFs2Grpc
import org.plasmalabs.bridge.consensus.shared.BTCRetryThreshold
import org.plasmalabs.bridge.consensus.shared.persistence.{StorageApi, StorageApiImpl}
import org.plasmalabs.bridge.consensus.shared.utils.ConfUtils._
import org.plasmalabs.bridge.consensus.subsystems.monitor.{BlockProcessor, SessionEvent}
import org.plasmalabs.bridge.shared.{
  BridgeCryptoUtils,
  BridgeError,
  BridgeResponse,
  ClientCount,
  ClientId,
  ConsensusClientMessageId,
  ReplicaCount,
  ReplicaId,
  ReplicaNode,
  ResponseGrpcServiceServer,
  StateMachineServiceGrpcClient,
  StateMachineServiceGrpcClientImpl, 
  StateMachineServiceGrpcClientRetryConfigImpl,
  PBFTInternalGrpcServiceClientRetryConfigImpl,
  RetryPolicy,
}
import org.plasmalabs.consensus.core.{PBFTInternalGrpcServiceClient, PBFTInternalGrpcServiceClientImpl}
import org.plasmalabs.sdk.dataApi.NodeQueryAlgebra
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import org.plasmalabs.sdk.monitoring.{BitcoinMonitor, NodeMonitor}
import org.plasmalabs.sdk.utils.Encoding
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scopt.OParser

import java.net.InetSocketAddress
import java.security.{KeyPair => JKeyPair, PublicKey, Security}
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

case class SystemGlobalState(
  currentStatus: Option[String],
  currentError:  Option[String],
  isReady:       Boolean = false
)

object Main extends IOApp with ConsensusParamsDescriptor with AppModule with InitUtils {

  override def run(args: List[String]): IO[ExitCode] =
    OParser.parse(
      parser,
      args,
      StrataBTCBridgeConsensusParamConfig(
        toplHost = Option(System.getenv("STRATA_HOST")).getOrElse("localhost"),
        toplWalletDb = System.getenv("STRATA_WALLET_DB"),
        zmqHost = Option(System.getenv("ZMQ_HOST")).getOrElse("localhost"),
        zmqPort = Option(System.getenv("ZMQ_PORT")).map(_.toInt).getOrElse(28332),
        btcUrl = Option(System.getenv("BTC_URL")).getOrElse("http://localhost"),
        btcUser = Option(System.getenv("BTC_USER")).getOrElse("bitcoin"),
        groupId = Option(System.getenv("ABTC_GROUP_ID"))
          .map(Encoding.decodeFromHex(_).toOption)
          .flatten
          .map(x => GroupId(ByteString.copyFrom(x)))
          .getOrElse(GroupId(ByteString.copyFrom(Array.fill(32)(0.toByte)))),
        seriesId = Option(System.getenv("ABTC_SERIES_ID"))
          .map(Encoding.decodeFromHex(_).toOption)
          .flatten
          .map(x => SeriesId(ByteString.copyFrom(x)))
          .getOrElse(SeriesId(ByteString.copyFrom(Array.fill(32)(0.toByte)))),
        btcPassword = Option(System.getenv("BTC_PASSWORD")).getOrElse("password")
      )
    ) match {
      case Some(config) =>
        runWithArgs(config)
      case None =>
        println("Invalid arguments")
        IO.consoleForIO.errorln("Invalid arguments") *>
        IO(ExitCode.Error)
    }

  private def loadKeyPegin(
    params: StrataBTCBridgeConsensusParamConfig
  ): IO[BIP39KeyManager] =
    KeyGenerationUtils.loadKeyManager[IO](
      params.btcNetwork,
      params.btcPegInSeedFile,
      params.btcPegInPassword
    )

  private def loadKeyWallet(
    params: StrataBTCBridgeConsensusParamConfig
  ): IO[BIP39KeyManager] =
    KeyGenerationUtils.loadKeyManager[IO](
      params.btcNetwork,
      params.btcWalletSeedFile,
      params.walletPassword
    )

  private def loadReplicaNodeFromConfig[F[_]: Sync: Logger](
    conf: Config
  )(implicit replicaCount: ReplicaCount): F[List[ReplicaNode[F]]] = {
    import cats.implicits._
    (for (i <- 0 until replicaCount.value) yield for {
      host <- Sync[F].delay(
        conf.getString(s"bridge.replica.consensus.replicas.$i.host")
      )
      port <- Sync[F].delay(
        conf.getInt(s"bridge.replica.consensus.replicas.$i.port")
      )
      secure <- Sync[F].delay(
        conf.getBoolean(s"bridge.replica.consensus.replicas.$i.secure")
      )
      _ <-
        info"bridge.replica.consensus.replicas.$i.host: ${host}"
      _ <-
        info"bridge.replica.consensus.replicas.$i.port: ${port}"
      _ <-
        info"bridge.replica.consensus.replicas.$i.secure: ${secure}"
    } yield ReplicaNode[F](i, host, port, secure)).toList.sequence
  }

  private def createReplicaClienMap[F[_]: Async](
    replicaNodes: List[ReplicaNode[F]]
  ) = {
    import cats.implicits._
    import fs2.grpc.syntax.all._
    for {
      idClientList <- (for {
        replicaNode <- replicaNodes
      } yield for {
        channel <-
          (if (replicaNode.backendSecure)
             ManagedChannelBuilder
               .forAddress(replicaNode.backendHost, replicaNode.backendPort)
               .useTransportSecurity()
           else
             ManagedChannelBuilder
               .forAddress(replicaNode.backendHost, replicaNode.backendPort)
               .usePlaintext()).resource[F]
        consensusClient <- StateMachineServiceFs2Grpc.stubResource(
          channel
        )
      } yield (replicaNode.id -> consensusClient)).sequence.map(x => Map(x: _*))
    } yield idClientList
  }

  def initializeForResources(
    replicaKeysMap:     Map[Int, PublicKey],
    replicaKeyPair:     JKeyPair,
    pbftProtocolClient: PBFTInternalGrpcServiceClient[IO],
    storageApi:         StorageApi[IO],
    consensusClient:    StateMachineServiceGrpcClient[IO],
    idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
    publicApiClientGrpcMap: Map[
      ClientId,
      (PublicApiClientGrpc[IO], PublicKey)
    ],
    params:                      StrataBTCBridgeConsensusParamConfig,
    queue:                       Queue[IO, SessionEvent],
    walletManager:               BTCWalletAlgebra[IO],
    pegInWalletManager:          BTCWalletAlgebra[IO],
    currentBitcoinNetworkHeight: Ref[IO, Int],
    seqNumberManager:            SequenceNumberManager[IO],
    currentStrataHeight:         Ref[IO, Long],
    currentState:                Ref[IO, SystemGlobalState]
  )(implicit
    clientId:           ClientId,
    replicaId:          ReplicaId,
    replicaCount:       ReplicaCount,
    fromFellowship:     Fellowship,
    fromTemplate:       Template,
    bitcoindInstance:   BitcoindRpcClient,
    btcRetryThreshold:  BTCRetryThreshold,
    groupIdIdentifier:  GroupId,
    seriesIdIdentifier: SeriesId,
    logger:             Logger[IO]
  ) = {
    implicit val consensusClientImpl = consensusClient
    implicit val storageApiImpl = storageApi
    implicit val iPbftProtocolClient = pbftProtocolClient
    implicit val pbftProtocolClientImpl =
      new PublicApiClientGrpcMap[IO](publicApiClientGrpcMap)
    for {
      currentStrataHeightVal         <- currentStrataHeight.get
      currentBitcoinNetworkHeightVal <- currentBitcoinNetworkHeight.get
      res <- createApp(
        replicaKeysMap,
        replicaKeyPair,
        idReplicaClientMap,
        params,
        queue,
        walletManager,
        pegInWalletManager,
        logger,
        currentBitcoinNetworkHeight,
        seqNumberManager,
        currentStrataHeight,
        currentState
      )
    } yield (
      currentStrataHeightVal,
      currentBitcoinNetworkHeightVal,
      res._1,
      res._2,
      res._3,
      res._4,
      res._5,
      res._6
    )
  }

  def startResources(
    privateKeyFile:              String,
    params:                      StrataBTCBridgeConsensusParamConfig,
    queue:                       Queue[IO, SessionEvent],
    walletManager:               BTCWalletAlgebra[IO],
    pegInWalletManager:          BTCWalletAlgebra[IO],
    currentBitcoinNetworkHeight: Ref[IO, Int],
    seqNumberManager:            SequenceNumberManager[IO],
    currentStrataHeight:         Ref[IO, Long],
    currentState:                Ref[IO, SystemGlobalState]
  )(implicit
    conf:               Config,
    fromFellowship:     Fellowship,
    fromTemplate:       Template,
    bitcoindInstance:   BitcoindRpcClient,
    btcRetryThreshold:  BTCRetryThreshold,
    groupIdIdentifier:  GroupId,
    seriesIdIdentifier: SeriesId,
    logger:             Logger[IO],
    clientId:           ClientId,
    replicaId:          ReplicaId,
    clientCount:        ClientCount,
    replicaCount:       ReplicaCount,
    pbftInternalConfig: PBFTInternalGrpcServiceClientRetryConfigImpl,
    stateMachineConf:   StateMachineServiceGrpcClientRetryConfigImpl
  ) = {
    import fs2.grpc.syntax.all._
    import scala.jdk.CollectionConverters._
    val messageResponseMap =
      new ConcurrentHashMap[ConsensusClientMessageId, ConcurrentHashMap[Either[
        BridgeError,
        BridgeResponse
      ], LongAdder]]()
    val messageVoterMap =
      new ConcurrentHashMap[
        ConsensusClientMessageId,
        ConcurrentHashMap[Int, Int]
      ]()
    for {
      replicaKeyPair <- BridgeCryptoUtils
        .getKeyPair[IO](privateKeyFile)
      publicApiClientGrpcMap <- createClientMap(
        replicaKeyPair,
        conf
      )(IO.asyncForIO, logger, replicaId, clientCount)
      replicaNodes       <- loadReplicaNodeFromConfig[IO](conf).toResource
      storageApi         <- StorageApiImpl.make[IO](params.dbFile.toPath().toString())
      idReplicaClientMap <- createReplicaClienMap[IO](replicaNodes)
      mutex              <- Mutex[IO].toResource
      pbftProtocolClientGrpc <- PBFTInternalGrpcServiceClientImpl.make[IO](
        replicaKeyPair,
        replicaNodes
      )
      viewReference <- Ref[IO].of(0L).toResource
      replicaClients <- StateMachineServiceGrpcClientImpl
        .makeContainer[IO](
          viewReference,
          replicaKeyPair,
          mutex,
          replicaNodes,
          messageVoterMap,
          messageResponseMap
        )
      replicaKeysMap <- createReplicaPublicKeyMap[IO](conf).toResource
      res <- initializeForResources(
        replicaKeysMap,
        replicaKeyPair,
        pbftProtocolClientGrpc,
        storageApi,
        replicaClients,
        idReplicaClientMap,
        publicApiClientGrpcMap,
        params,
        queue,
        walletManager,
        pegInWalletManager,
        currentBitcoinNetworkHeight,
        seqNumberManager,
        currentStrataHeight,
        currentState
      ).toResource
      (
        currentStrataHeightVal,
        currentBitcoinNetworkHeightVal,
        bridgeStateMachineExecutionManager,
        grpcServiceResource,
        init,
        peginStateMachine,
        pbftServiceResource,
        requestStateManager
      ) = res
      _           <- requestStateManager.startProcessingEvents()
      _           <- IO.asyncForIO.background(bridgeStateMachineExecutionManager.runStream().compile.drain)
      pbftService <- pbftServiceResource
      nodeQueryAlgebra = NodeQueryAlgebra
        .make[IO](
          channelResource(
            params.toplHost,
            params.toplPort,
            params.toplSecureConnection
          )
        )
      btcMonitor <- BitcoinMonitor(
        bitcoindInstance,
        zmqHost = params.zmqHost,
        zmqPort = params.zmqPort
      )
      nodeMonitor <- NodeMonitor(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection,
        nodeQueryAlgebra
      )
      _              <- storageApi.initializeStorage().toResource
      currentViewRef <- Ref[IO].of(0L).toResource
      responsesService <- ResponseGrpcServiceServer
        .responseGrpcServiceServer[IO](
          currentViewRef,
          replicaKeysMap,
          messageVoterMap,
          messageResponseMap
        )
      grpcService <- grpcServiceResource
      _ <- getAndSetCurrentStrataHeight(
        currentStrataHeight,
        nodeQueryAlgebra
      ).toResource
      _ <- getAndSetCurrentBitcoinHeight(
        currentBitcoinNetworkHeight,
        bitcoindInstance
      ).toResource
      _ <- getAndSetCurrentStrataHeight( // we do this again in case the BTC height took too much time to get
        currentStrataHeight,
        nodeQueryAlgebra
      ).toResource
      replicaGrpcListener <- NettyServerBuilder
        .forAddress(new InetSocketAddress(replicaHost, replicaPort))
        .addServices(List(grpcService, pbftService).asJava)
        .resource[IO]
      responsesGrpcListener <- NettyServerBuilder
        .forAddress(new InetSocketAddress(responseHost, responsePort))
        .addService(responsesService)
        .resource[IO]
      _ <- IO.asyncForIO
        .background(
          fs2.Stream
            .fromQueueUnterminated(queue)
            .evalMap(x => peginStateMachine.innerStateConfigurer(x))
            .compile
            .drain
        )
      _ <- IO.asyncForIO
        .background(
          IO(
            replicaGrpcListener.start
          ) >> info"Netty-Server (replica grpc) service bound to address ${replicaHost}:${replicaPort}" (
            logger
          )
        )
      _ <- IO.asyncForIO
        .background(
          IO(
            responsesGrpcListener.start
          ) >> info"Netty-Server (response grpc) service bound to address ${responseHost}:${responsePort}" (
            logger
          )
        )
      outcome <- IO.asyncForIO
        .backgroundOn(
          btcMonitor
            .either(
              nodeMonitor
                .handleErrorWith { e =>
                  e.printStackTrace()
                  fs2.Stream.empty
                }
            )
            .flatMap(
              BlockProcessor
                .process(currentBitcoinNetworkHeightVal, currentStrataHeightVal)
            )
            .observe(_.foreach(evt => storageApi.insertBlockchainEvent(evt)))
            .flatMap(
              // this handles each event in the context of the state machine
              peginStateMachine.handleBlockchainEventInContext
            )
            .evalMap(identity)
            .compile
            .drain,
          ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
        )
      outcomeVal <- outcome.toResource
      _          <- info"Outcome of monitoring: $outcomeVal".toResource
    } yield ()
  }

  def getAndSetCurrentStrataHeight[F[_]: Async: Logger](
    currentStrataHeight: Ref[F, Long],
    bqa:                 NodeQueryAlgebra[F]
  ) = {
    import cats.implicits._
    import scala.concurrent.duration._
    (for {
      someTip <- bqa.blockByDepth(1)
      height <- someTip
        .map({ tip =>
          val (_, header, _, _) = tip
          currentStrataHeight.set(header.height) >>
          info"Obtained and set topl height: ${header.height}" >>
          header.height.pure[F]
        })
        .getOrElse(
          warn"Failed to obtain and set topl height" >> Async[F]
            .sleep(3.second) >> 0L.pure[F]
        )
    } yield height).iterateUntil(_ != 0)
  }

  def getAndSetCurrentBitcoinHeight[F[_]: Async: Logger](
    currentBitcoinNetworkHeight: Ref[F, Int],
    bitcoindInstance:            BitcoindRpcClient
  ) = {
    import cats.implicits._
    import scala.concurrent.duration._
    (for {
      height <- Async[F].fromFuture(
        Async[F].delay(bitcoindInstance.getBlockCount())
      )
      _ <- currentBitcoinNetworkHeight.set(height)
      _ <-
        if (height == 0)
          warn"Failed to obtain and set BTC height" >> Async[F].sleep(3.second)
        else info"Obtained and set BTC height: $height"
    } yield height).iterateUntil(_ != 0)
  }

  def runWithArgs(params: StrataBTCBridgeConsensusParamConfig): IO[ExitCode] = {
    implicit val defaultFromFellowship = new Fellowship("self")
    implicit val defaultFromTemplate = new Template("default")
    val credentials = BitcoindAuthCredentials.PasswordBased(
      params.btcUser,
      params.btcPassword
    )
    implicit val bitcoindInstance = BitcoinMonitor.Bitcoind.remoteConnection(
      params.btcNetwork.btcNetwork,
      params.btcUrl,
      credentials
    )
    implicit val groupId = params.groupId
    implicit val seriesId = params.seriesId
    implicit val btcRetryThreshold: BTCRetryThreshold = new BTCRetryThreshold(
      params.btcRetryThreshold
    )
    implicit val conf = ConfigFactory.parseFile(params.configurationFile)
    implicit val replicaId = new ReplicaId(
      conf.getInt("bridge.replica.replicaId")
    )
    implicit val clientId = new ClientId(
      conf.getInt("bridge.replica.clientId")
    )
    implicit val replicaCount =
      new ReplicaCount(conf.getInt("bridge.replica.consensus.replicaCount"))
    implicit val clientCount =
      new ClientCount(conf.getInt("bridge.replica.clients.clientCount"))
    implicit val logger =
      org.typelevel.log4cats.slf4j.Slf4jLogger
        .getLoggerFromName[IO]("consensus-" + f"${replicaId.id}%02d")

    implicit val pbftInternalConfig = PBFTInternalGrpcServiceClientRetryConfigImpl(
      retryPolicy = RetryPolicy(
        initialDelay =
          FiniteDuration.apply(conf.getInt("bridge.replica.clients.pbftInternal.retryPolicy.initialDelay"), "second"),
        maxRetries = conf.getInt("bridge.replica.clients.pbftInternal.retryPolicy.maxRetries"),
        delayMultiplier = conf.getInt("bridge.replica.clients.pbftInternal.retryPolicy.delayMultiplier")
      )
    )

    implicit val stateMachineConf = StateMachineServiceGrpcClientRetryConfigImpl(
      primaryResponseWait =
        FiniteDuration.apply(conf.getInt("bridge.replica.clients.monitor.client.primaryResponseWait"), "second"),
      otherReplicasResponseWait =
        FiniteDuration.apply(conf.getInt("bridge.replica.clients.monitor.client.otherReplicasResponseWait"), "second"),
      retryPolicy = RetryPolicy(
        initialDelay =
          FiniteDuration.apply(conf.getInt("bridge.replica.clients.monitor.client.retryPolicy.initialDelay"), "second"),
        maxRetries = conf.getInt("bridge.replica.clients.monitor.client.retryPolicy.maxRetries"),
        delayMultiplier = conf.getInt("bridge.replica.clients.monitor.client.retryPolicy.delayMultiplier")
      )
    )

    (for {
      _                  <- IO(Security.addProvider(new BouncyCastleProvider()))
      pegInKm            <- loadKeyPegin(params)
      walletKm           <- loadKeyWallet(params)
      pegInWalletManager <- BTCWalletAlgebraImpl.make[IO](pegInKm)
      walletManager      <- BTCWalletAlgebraImpl.make[IO](walletKm)
      _                  <- printParams[IO](params)
      _                  <- printConfig[IO]
      globalState <- Ref[IO].of(
        SystemGlobalState(Some("Setting up wallet..."), None)
      )
      currentStrataHeight         <- Ref[IO].of(0L)
      queue                       <- Queue.unbounded[IO, SessionEvent]
      currentBitcoinNetworkHeight <- Ref[IO].of(0)
      seqNumberManager            <- SequenceNumberManagerImpl.make[IO]()
      _ <- startResources(
        privateKeyFile,
        params,
        queue,
        walletManager,
        pegInWalletManager,
        currentBitcoinNetworkHeight,
        seqNumberManager,
        currentStrataHeight,
        globalState
      ).useForever
    } yield Right(
      s"Server started on ${ServerConfig.host}:${ServerConfig.port}"
    )).handleErrorWith { e =>
      e.printStackTrace()
      IO(Left(e.getMessage))
    } >> IO.never

  }
}
