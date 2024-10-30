package org.plasmalabs.bridge

import cats.effect.kernel.{Async, Fiber}
import cats.effect.{ExitCode, IO}
import fs2.io.{file, process}
import io.circe.parser._
import munit.{AnyFixture, CatsEffectSuite, FutureFixture}
import org.typelevel.log4cats.Logger

import java.nio.file.{Files, Paths}
import scala.concurrent.duration._
import scala.util.Try

trait BridgeSetupModule extends CatsEffectSuite with ReplicaConfModule with PublicApiConfModule {

  override val munitIOTimeout = Duration(500, "s")

  implicit val logger: Logger[IO] =
    org.typelevel.log4cats.slf4j.Slf4jLogger
      .getLoggerFromName[IO]("it-test")

  def toplWalletDb(replicaId: Int) =
    Option(System.getenv(s"STRATA_WALLET_DB_$replicaId")).getOrElse(s"strata-wallet$replicaId.db")

  def toplWalletJson(replicaId: Int) =
    Option(System.getenv(s"STRATA_WALLET_JSON_$replicaId")).getOrElse(s"strata-wallet$replicaId.json")

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

  def launchConsensus(replicaId: Int, groupId: String, seriesId: String) = IO.asyncForIO
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
          "--strata-wallet-seed-file",
          toplWalletJson(replicaId),
          "--strata-wallet-db",
          toplWalletDb(replicaId),
          "--btc-url",
          "http://localhost",
          "--btc-blocks-to-recover",
          "50",
          "--strata-confirmation-threshold",
          "5",
          "--strata-blocks-to-recover",
          "15",
          "--abtc-group-id",
          groupId,
          "--abtc-series-id",
          seriesId
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

  val startServer: AnyFixture[Unit] =
    new FutureFixture[Unit]("server setup") {

      var fiber01: List[(Fiber[IO, Throwable, ExitCode], Int)] = _
      var fiber02: List[(Fiber[IO, Throwable, ExitCode], Int)] = _
      def apply() = (fiber01, fiber02): Unit

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
          _ <- deleteFiles(2)
          _              <- createReplicaConfigurationFiles[IO]()
          _              <- createPublicApiConfigurationFiles[IO]()
          currentAddress <- currentAddress(toplWalletDb(0))
          utxo           <- getCurrentUtxosFromAddress(toplWalletDb(0), currentAddress)
          (groupId, seriesId) = extractIds(utxo)
          _ <- IO(Try(Files.delete(Paths.get("bridge.db"))))
          _ <- IO.asyncForIO.both(
            (0 until replicaCount).map(launchConsensus(_, groupId, seriesId)).toList.sequence.map { f2 =>
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
          _          <- mintStrataBlock(1, 1)
        } yield ()).unsafeToFuture()

      override def afterAll() = {
        fiber01.map(_._1.cancel).sequence.unsafeToFuture()
        fiber02.map(_._1.cancel).sequence.void.unsafeToFuture()
      }
    }

  val cleanupDir = FunFixture[Unit](
    setup = { _ =>
      try
        Files.delete(Paths.get(userWalletDb(1)))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get(userWalletMnemonic(1)))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get(userWalletJson(1)))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get(userWalletDb(2)))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get(userWalletMnemonic(2)))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get(userWalletJson(2)))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get(vkFile))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get("fundRedeemTx.pbuf"))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get("fundRedeemTx.pbuf"))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get("fundRedeemTxProved.pbuf"))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get("redeemTx.pbuf"))
      catch {
        case _: Throwable => ()
      }
      try
        Files.delete(Paths.get("redeemTxProved.pbuf"))
      catch {
        case _: Throwable => ()
      }

    },
    teardown = { _ =>
      ()
    }
  )

   

  def deleteFiles(numberOfSessions: Int): IO[Unit] = {
    def fileNamesForId(id: Int) = {
      val idFormatted = f"$id%02d"
      List(
        userWalletDb(id),
        userWalletMnemonic(id),
        userWalletJson(id),
        vkFile,
        s"fundRedeemTx${idFormatted}.pbuf",
        s"fundRedeemTxProved${idFormatted}.pbuf",
        s"redeemTx${idFormatted}.pbuf",
        s"redeemTxProved${idFormatted}.pbuf"
      )
    }

    for {
      _ <- (1 to numberOfSessions).toList.parTraverse { id =>
        for {
          _ <- logger.info(s"Processing deletion for session $id")
          _ <- fileNamesForId(id).parTraverse { baseName =>
            IO {
              try {
                Files.delete(Paths.get(baseName))
              } catch {
                case _: Throwable => logger.info(s"Not successful deleting of ${baseName}") >> IO.unit
              }
            }
          }
        } yield ()
      }
    } yield ()
  }

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
