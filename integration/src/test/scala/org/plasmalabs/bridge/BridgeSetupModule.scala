package org.plasmalabs.bridge

import cats.effect.kernel.{Async, Fiber}
import cats.effect.{ExitCode, IO}
import fs2.io.{file, process}
import io.circe.parser._
import munit.{AnyFixture, CatsEffectSuite, FutureFixture}
import org.plasmalabs.bridge.{userFundRedeemTx, userFundRedeemTxProved, userRedeemTx, userVkFile}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.nio.file.{Files, Paths}
import scala.concurrent.duration._
import scala.util.Try

trait BridgeSetupModule extends CatsEffectSuite with ReplicaConfModule with PublicApiConfModule {

  override val munitIOTimeout = Duration(400, "s")

  implicit val logger: Logger[IO] =
    org.typelevel.log4cats.slf4j.Slf4jLogger
      .getLoggerFromName[IO]("it-test")

  def plasmaWalletDb(replicaId: Int) =
    Option(System.getenv(s"PLASMA_WALLET_DB_$replicaId")).getOrElse(s"plasma-wallet$replicaId.db")

  def plasmaWalletJson(replicaId: Int) =
    Option(System.getenv(s"PLASMA_WALLET_JSON_$replicaId")).getOrElse(s"plasma-wallet$replicaId.json")

  import cats.implicits._

  lazy val replicaCount = 7

  def createReplicaConfigurationFiles[F[_]: file.Files: Async]() = (for {
    replicaId <- 0 until replicaCount
  } yield fs2
    .Stream(consensusConfString(replicaId, replicaCount))
    .through(fs2.text.utf8.encode)
    .through(file.Files[F].writeAll(fs2.io.file.Path(s"replicaConfig${replicaId}.conf")))
    .compile
    .drain).toList.sequence

  def createPublicApiConfigurationFiles[F[_]: file.Files: Async]() = (for {
    replicaId <- 0 until replicaCount
  } yield fs2
    .Stream(publicApiConfString(replicaId * 2, replicaCount))
    .through(fs2.text.utf8.encode)
    .through(file.Files[F].writeAll(fs2.io.file.Path(s"clientConfig${replicaId * 2}.conf")))
    .compile
    .drain).toList.sequence

  def launchConsensus(replicaId: Int) = IO.asyncForIO
    .start(
      consensus.core.Main.run(
        List(
          "--config-file",
          s"replicaConfig${replicaId}.conf",
          "--db-file",
          s"replica${replicaId}.db",
          "--btc-wallet-seed-file",
          "src/test/resources/wallet.json",
          "--btc-peg-in-seed-file",
          "src/test/resources/pegin-wallet.json",
          "--plasma-wallet-seed-file",
          plasmaWalletJson(replicaId),
          "--plasma-wallet-db",
          plasmaWalletDb(replicaId),
          "--btc-url",
          "http://localhost",
          "--btc-blocks-to-recover",
          "50",
          "--plasma-confirmation-threshold",
          "5",
          "--plasma-blocks-to-recover",
          "100",
          "--abtc-group-id",
          "0631c11b499425e93611d85d52e4c71c2ad1cf4d58fb379d6164f486ac6b50d2",
          "--abtc-series-id",
          "a8ef2a52d574520de658a43ceda12465ee7f17e9db68dbf07f1e6614a23efa64"
        )
      )
    )

  def launchPublicApi(replicaId: Int) = IO.asyncForIO
    .start(
      publicapi.Main.run(
        List(
          "--config-file",
          s"clientConfig${replicaId * 2}.conf"
        )
      )
    )

  var fiber01: List[(Fiber[IO, Throwable, ExitCode], Int)] = _
  var fiber02: List[(Fiber[IO, Throwable, ExitCode], Int)] = _

  val startServer: AnyFixture[Unit] =
    new FutureFixture[Unit]("server setup") {
      def apply() = ()

      override def beforeAll() =
        (for {
          _ <- pwd
          _ <- (0 until replicaCount).toList.traverse { replicaId =>
            IO(Try(Files.delete(Paths.get(s"replicaConfig${replicaId}.conf"))))
          }
          _ <- (0 until replicaCount).toList.traverse { replicaId =>
            IO(Try(Files.delete(Paths.get(s"clientConfig${replicaId * 2}.conf"))))
          }
          _ <- (0 until replicaCount).toList.traverse { replicaId =>
            IO(Try(Files.delete(Paths.get(s"replica${replicaId}.db"))))
          }
          _ <- createReplicaConfigurationFiles[IO]()
          _ <- createPublicApiConfigurationFiles[IO]()

          _ <- IO(Try(Files.delete(Paths.get("bridge.db"))))
          _ <- IO.asyncForIO.both(
            (0 until replicaCount).map(launchConsensus(_)).toList.sequence.map { f2 =>
              fiber02 = f2.zipWithIndex
            },
            IO.sleep(10.seconds)
          )
          _ <- IO.asyncForIO.both(
            (0 until replicaCount).map(launchPublicApi(_)).toList.sequence.map { f1 =>
              fiber01 = f1.zipWithIndex
            },
            IO.sleep(10.seconds)
          )
          _             <- IO.sleep(10.seconds)
          bridgeNetwork <- computeBridgeNetworkName
          _             <- IO.println("bridgeNetwork: " + bridgeNetwork)
          // parse
          ipBitcoin02 <- IO.fromEither(
            parse(bridgeNetwork._1)
              .map(x =>
                (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
                  x.filter(x => (x._2 \\ "Name").head.asString.get == "bitcoin02").values.head
                }).get \\ "IPv4Address").head.asString.get
                  .split("/")
                  .head
              )
          )
          // print IP BTC 02
          _ <- IO.println("ipBitcoin02: " + ipBitcoin02)
          // parse
          ipBitcoin01 <- IO.fromEither(
            parse(bridgeNetwork._1)
              .map(x =>
                (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
                  x.filter(x => (x._2 \\ "Name").head.asString.get == "bitcoin01").values.head
                }).get \\ "IPv4Address").head.asString.get
                  .split("/")
                  .head
              )
          )
          // print IP BTC 01
          _ <- IO.println("ipBitcoin01: " + ipBitcoin01)
          // add node
          _ <- process
            .ProcessBuilder(DOCKER_CMD, addNode(1, ipBitcoin02, 18444): _*)
            .spawn[IO]
            .use(getText)
          _ <- process
            .ProcessBuilder(DOCKER_CMD, addNode(2, ipBitcoin01, 18444): _*)
            .spawn[IO]
            .use(getText)
          _          <- initUserBitcoinWallet
          newAddress <- getNewAddress
          _          <- generateToAddress(1, 101, newAddress)
          _          <- mintPlasmaBlock(1, 1)
        } yield ()).unsafeToFuture()

      override def afterAll() =
        (for {
          _ <- IO.race(
            fiber01.parTraverse(_._1.cancel),
            fiber02.traverse(_._1.cancel)
          )
          _ <- IO.sleep(5.seconds)
        } yield ()).void.unsafeToFuture()
    }

  def killFiber(replicaId: Int): IO[Unit] = {
    val consensusFiberOpt = fiber02.find(_._2 == replicaId)
    val publicApiFiberOpt = fiber01.find(_._2 == replicaId)

    (consensusFiberOpt, publicApiFiberOpt)
      .mapN((consensusFiber, publicApiFiber) =>
        for {
          _ <- consensusFiber._1.cancel
          _ <- publicApiFiber._1.cancel
          _ <- IO.delay {
            fiber02 = fiber02.filter(_._2 != replicaId)
            fiber01 = fiber01.filter(_._2 != replicaId)
          }
          _ <- logger.info(s"Killed both consensus and public API fibers for replica $replicaId")
        } yield ()
      )
      .sequence[IO, Unit]
      .void
  }

  def restoreMissingFibers: IO[Unit] = for {
    missingReplicas <- IO.delay {
      (0 until replicaCount).filter(id => !fiber02.exists(_._2 == id) || !fiber01.exists(_._2 == id)).toList
    }

    _ <-
      if (missingReplicas.nonEmpty) {
        for {
          _ <- IO.asyncForIO.both(
            missingReplicas.map(launchConsensus).sequence.map { newFibers =>
              fiber02 = fiber02 ++ newFibers.zip(missingReplicas)
            },
            IO.sleep(10.seconds)
          )

          _ <- IO.asyncForIO.both(
            missingReplicas.map(launchPublicApi).sequence.map { newFibers =>
              fiber01 = fiber01 ++ newFibers.zip(missingReplicas)
            },
            IO.sleep(10.seconds)
          )

          _ <- logger.info(s"Restored consensus and public api for ${missingReplicas}")
        } yield ()
      } else {
        logger.info("All replicas are still running.")
      }
  } yield ()

  def deleteOutputFiles(numberOfSessions: Int) = {
    def deleteFilesForSession(id: Int) =
      for {
        _ <- IO {
          List(
            userWalletDb(id),
            userWalletMnemonic(id),
            userWalletJson(id),
            userVkFile(id),
            userFundRedeemTx(id),
            userFundRedeemTxProved(id),
            userRedeemTx(id),
            userRedeemTxProved(id)
          ).foreach { case (path) =>
            try
              Files.delete(Paths.get(path))
            catch {
              case _: Throwable => ()
            }
          }
        }
      } yield ()

    for {
      _ <- info"Deleting files for ${numberOfSessions} sessions"
      _ <- (1 to numberOfSessions).toList.parTraverse { id =>
        for {
          _ <- deleteFilesForSession(id)
        } yield ()
      }
    } yield ()
  }

  val cleanupDir = FunFixture[Unit](
    setup = { _ =>
      (for {
        _ <- restoreMissingFibers
        _ <- IO {
          List(
            userWalletDb(1),
            userWalletMnemonic(1),
            userWalletJson(1),
            userWalletDb(2),
            userWalletMnemonic(2),
            userWalletJson(2),
            userVkFile(1),
            userVkFile(2),
            userFundRedeemTx(1),
            userFundRedeemTx(2),
            userFundRedeemTxProved(1),
            userFundRedeemTxProved(2),
            userRedeemTx(1),
            userRedeemTx(2),
            userRedeemTxProved(1),
            userRedeemTxProved(2)
          ).foreach { case (path) =>
            try
              Files.delete(Paths.get(path))
            catch {
              case _: Throwable => ()
            }
          }
        }
      } yield ()).unsafeRunSync()
    },
    teardown = { _ => () }
  )

  val computeBridgeNetworkName = for {
    // network ls
    networkLs <- process
      .ProcessBuilder(DOCKER_CMD, networkLs: _*)
      .spawn[IO]
      .use(getText)
    // extract the string that starts with github_network_
    // the format is
    // NETWORK ID     NAME      DRIVER    SCOPE
    // 7b1e3b1b1b1b   github_network_bitcoin01   bridge   local
    pattern = ".*?(github_network_\\S+)\\s+.*".r
    networkName = pattern.findFirstMatchIn(networkLs) match {
      case Some(m) =>
        m.group(1) // Extract the first group matching the pattern
      case None => "bridge"
    }
    // inspect bridge
    bridgeNetwork <- process
      .ProcessBuilder(DOCKER_CMD, inspectBridge(networkName): _*)
      .spawn[IO]
      .use(getText)
    // print bridgeNetwork
    // _ <- info"bridgeNetwork: $bridgeNetwork"
  } yield (bridgeNetwork, networkName)
}
