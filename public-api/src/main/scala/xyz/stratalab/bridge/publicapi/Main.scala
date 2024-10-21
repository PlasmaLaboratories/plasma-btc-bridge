package xyz.stratalab.bridge.publicapi

import cats.data.Kleisli
import cats.effect.kernel.{Ref, Sync}
import cats.effect.std.Mutex
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.typesafe.config.{Config, ConfigFactory}
import fs2.grpc.syntax.all._
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scopt.OParser
import xyz.stratalab.bridge.shared.{BridgeCryptoUtils, BridgeError, BridgeResponse, ClientId, ConsensusClientMessageId, ReplicaCount, ReplicaNode, ResponseGrpcServiceServer, RetryPolicy, StateMachineServiceGrpcClient, StateMachineServiceGrpcClientImpl, StateMachineServiceGrpcClientRetryConfigImpl}

import java.net.InetSocketAddress
import java.security.{PublicKey, Security}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.concurrent.duration.FiniteDuration

sealed trait PeginSessionState

case object PeginSessionState {
  case object PeginSessionStateWaitingForBTC extends PeginSessionState
  case object PeginSessionStateMintingTBTC extends PeginSessionState
  case object PeginSessionWaitingForRedemption extends PeginSessionState
  case object PeginSessionWaitingForClaim extends PeginSessionState
  case object PeginSessionMintingTBTCConfirmation extends PeginSessionState
  case object PeginSessionWaitingForEscrowBTCConfirmation extends PeginSessionState
  case object PeginSessionWaitingForClaimBTCConfirmation extends PeginSessionState
}

object Main extends IOApp with PublicApiParamsDescriptor {

  def createApp(
    consensusGrpcClients: StateMachineServiceGrpcClient[IO]
  )(implicit
    l:            Logger[IO],
    clientNumber: ClientId
  ) = {
    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    val router = Router.define(
      "/api" -> PublicApiHttpServiceServer.publicApiHttpServiceServer(
        consensusGrpcClients
      )
    )(default = staticAssetsService)
    Kleisli[IO, Request[IO], Response[IO]] { request =>
      router
        .run(request)
        .getOrElse(
          Response[IO](
            status = Status.NotFound,
            body = fs2.Stream.emits(
              """<!DOCTYPE html>
                |<html>
                |<body>
                |<h1>Not found</h1>
                |<p>The page you are looking for is not found.</p>
                |<p>This message was generated on the server.</p>
                |</body>
                |</html>""".stripMargin('|').getBytes()
            ),
            headers = Headers(headers.`Content-Type`(MediaType.text.html))
          )
        )
    }
  }

  def setupServices(
    apiHost:        String,
    apiPort:        Int,
    clientHost:     String,
    clientPort:     Int,
    privateKeyFile: String,
    replicaNodes:   List[ReplicaNode[IO]],
    replicaKeysMap: Map[Int, PublicKey],
    currentViewRef: Ref[IO, Long]
  )(implicit
    replicaCount:     ReplicaCount,
    clientNumber:     ClientId,
    logger:           Logger[IO],
    stateMachineConf: StateMachineServiceGrpcClientRetryConfigImpl
  ) = {
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
      keyPair <- BridgeCryptoUtils.getKeyPair[IO](privateKeyFile)
      mutex   <- Mutex[IO].toResource
      replicaClients <- StateMachineServiceGrpcClientImpl
        .makeContainer(
          currentViewRef,
          keyPair,
          mutex,
          replicaNodes,
          messageVoterMap,
          messageResponseMap
        )
      app = createApp(replicaClients)
      _ <- EmberServerBuilder
        .default[IO]
        .withIdleTimeout(ServerConfig.idleTimeOut)
        .withHost(ServerConfig.host(apiHost))
        .withPort(ServerConfig.port(apiPort.toString()))
        .withHttpApp(
          CORS.policy.withAllowOriginAll.withAllowMethodsAll
            .withAllowHeadersAll(app)
        )
        .withLogger(logger)
        .build
      rService <- ResponseGrpcServiceServer.responseGrpcServiceServer[IO](
        currentViewRef,
        replicaKeysMap,
        messageVoterMap,
        messageResponseMap
      )
      grpcListener <- NettyServerBuilder
        .forAddress(new InetSocketAddress(clientHost, clientPort))
        .addService(rService)
        .resource[IO]
      grpcShutdown <- IO.asyncForIO.background(
        IO(
          grpcListener.start
        ) >> info"Netty-Server (grpc) service bound to address ${clientHost}:${clientPort}"
      )
    } yield grpcShutdown
  }

  private def createReplicaPublicKeyMap[F[_]: Sync](
    conf: Config
  )(implicit replicaCount: ReplicaCount): F[Map[Int, PublicKey]] =
    (for (i <- 0 until replicaCount.value) yield {
      val publicKeyFile = conf.getString(
        s"bridge.client.consensus.replicas.$i.publicKeyFile"
      )
      for {
        keyPair <- BridgeCryptoUtils.getPublicKey(publicKeyFile).allocated
      } yield (i, keyPair._1)
    }).toList.sequence.map(x => Map(x: _*))

  private def loadReplicaNodeFromConfig[F[_]: Sync: Logger](
    conf: Config
  ): F[List[ReplicaNode[F]]] = {
    val replicaCount = conf.getInt("bridge.client.consensus.replicaCount")
    (for (i <- 0 until replicaCount) yield for {
      host <- Sync[F].delay(
        conf.getString(s"bridge.client.consensus.replicas.$i.host")
      )
      port <- Sync[F].delay(
        conf.getInt(s"bridge.client.consensus.replicas.$i.port")
      )
      secure <- Sync[F].delay(
        conf.getBoolean(s"bridge.client.consensus.replicas.$i.secure")
      )
      _ <-
        info"bridge.client.consensus.replicas.$i.host: ${host}"
      _ <-
        info"bridge.client.consensus.replicas.$i.port: ${port}"
      _ <-
        info"bridge.client.consensus.replicas.$i.secure: ${secure}"
    } yield ReplicaNode[F](i, host, port, secure)).toList.sequence
  }

  override def run(args: List[String]): IO[ExitCode] =
    // log syntax
    OParser.parse(
      parser,
      args,
      StrataBTCBridgePublicApiParamConfig()
    ) match {
      case Some(configuration) =>
        val conf = ConfigFactory.parseFile(configuration.configurationFile)
        implicit val client = new ClientId(
          conf.getInt("bridge.client.clientId")
        )
        implicit val replicaCount = new ReplicaCount(
          conf.getInt("bridge.client.consensus.replicaCount")
        )
        implicit val logger =
          org.typelevel.log4cats.slf4j.Slf4jLogger
            .getLoggerFromName[IO]("public-api-" + f"${client.id}%02d")

        implicit val stateMachineConf = StateMachineServiceGrpcClientRetryConfigImpl(
          primaryResponseWait = FiniteDuration.apply(conf.getInt("bridge.client.primaryResponseWait"), "second"),
          otherReplicasResponseWait =
            FiniteDuration.apply(conf.getInt("bridge.client.otherReplicasResponseWait"), "second"),
          retryPolicy = RetryPolicy(
            initialDelay = FiniteDuration.apply(conf.getInt("bridge.client.retryPolicy.initialDelay"), "second"),
            maxRetries = conf.getInt("bridge.client.retryPolicy.maxRetries"),
            delayMultiplier = conf.getInt("bridge.client.retryPolicy.delayMultiplier")
          )
        )

        for {
          _ <- info"Configuration parameters"
          _ <- IO(Security.addProvider(new BouncyCastleProvider()))
          clientHost <- IO(
            conf.getString("bridge.client.responses.host")
          )
          clientPort <- IO(
            conf.getInt("bridge.client.responses.port")
          )
          apiHost <- IO(
            conf.getString("bridge.client.api.host")
          )
          apiPort <- IO(
            conf.getInt("bridge.client.api.port")
          )
          privateKeyFile <- IO(
            conf.getString("bridge.client.security.privateKeyFile")
          )
          _              <- info"bridge.client.security.privateKeyFile: ${privateKeyFile}"
          _              <- info"bridge.client.clientId: ${client.id}"
          _              <- info"bridge.client.responses.host: ${clientHost}"
          _              <- info"bridge.client.responses.port: ${clientPort}"
          replicaKeysMap <- createReplicaPublicKeyMap[IO](conf)
          replicaNodes   <- loadReplicaNodeFromConfig[IO](conf)
          currentView    <- Ref.of[IO, Long](0)
          _ <- setupServices(
            apiHost,
            apiPort,
            clientHost,
            clientPort,
            privateKeyFile,
            replicaNodes,
            replicaKeysMap,
            currentView
          ).useForever
        } yield ExitCode.Success
      case None =>
        IO.consoleForIO.errorln("Invalid arguments") *>
        IO(ExitCode.Error)
    }
}
