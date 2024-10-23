package xyz.stratalab.bridge

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

  override val munitIOTimeout = Duration(180, "s")

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
        (for {
          _ <- IO.race(
            fiber01.traverse(_._1.cancel),
            fiber02.traverse(_._1.cancel)
          )
          _ <- IO.sleep(5.seconds) // Give some time for the cancellation to take effect
        } yield ()).void.unsafeToFuture()
      }
    }
  
  def killFiber(replicaId: Int): IO[Unit] = IO.defer {
        val consensusFiberOpt = fiber02.find(_._2 == replicaId)
        val publicApiFiberOpt = fiber01.find(_._2 == replicaId)

        (consensusFiberOpt, publicApiFiberOpt) match {
          case (Some((consensusFiber, _)), Some((publicApiFiber, _))) =>
            for {
              _ <- consensusFiber.cancel
              _ <- publicApiFiber.cancel
              _ <- IO.delay {
                fiber02 = fiber02.filter(_._2 != replicaId)
                fiber01 = fiber01.filter(_._2 != replicaId)
              }
              _ <- IO.println(s"Killed both consensus and public API fibers for replica $replicaId")
            } yield ()
          case (_, _) =>
            IO.pure(())
        }
      }

      def restoreFiber(replicaId: Int): IO[Unit] = IO.defer {
  if (fiber02.exists(_._2 == replicaId) || fiber01.exists(_._2 == replicaId)) {
    IO.raiseError(new Exception(s"Fibers already exist for replica $replicaId"))
  } else {
    for {
      // First recreate any deleted configuration files if needed
      _ <- IO(Try(Files.delete(Paths.get(s"replicaConfig${replicaId}.conf"))))
      _ <- IO(Try(Files.delete(Paths.get(s"clientConfig${replicaId * 2}.conf"))))
      _ <- IO(Try(Files.delete(Paths.get(s"replica${replicaId}.db"))))
      
      // Recreate configuration files
      _ <- fs2.Stream(consensusConfString(replicaId, replicaCount))
           .through(fs2.text.utf8.encode)
           .through(file.Files[IO].writeAll(fs2.io.file.Path(s"replicaConfig${replicaId}.conf")))
           .compile
           .drain
           
      _ <- fs2.Stream(publicApiConfString(replicaId * 2, replicaCount))
           .through(fs2.text.utf8.encode)
           .through(file.Files[IO].writeAll(fs2.io.file.Path(s"clientConfig${replicaId * 2}.conf")))
           .compile
           .drain

      // Get current wallet info
      currentAddress <- currentAddress(toplWalletDb(0))
      utxo <- getCurrentUtxosFromAddress(toplWalletDb(0), currentAddress)
      (groupId, seriesId) = extractIds(utxo)
      
      // Launch consensus and wait a bit
      consensusFiber <- launchConsensus(replicaId, groupId, seriesId)
      _ <- IO.delay {
        fiber02 = (consensusFiber, replicaId) :: fiber02
      }
      _ <- IO.sleep(5.seconds)
      
      // Launch public API and wait
      publicApiFiber <- launchPublicApi(replicaId)
      _ <- IO.delay {
        fiber01 = (publicApiFiber, replicaId) :: fiber01
      }
      _ <- IO.sleep(5.seconds)
      
      _ <- IO.println(s"Restored both consensus and public API fibers for replica $replicaId")
    } yield ()
  }
}
  
  private def waitForAllFibers: IO[Unit] = {
    def allFibersPresent = {
      val consensusFibers = (0 until replicaCount).forall(id => fiber02.exists(_._2 == id))
      val publicApiFibers = (0 until replicaCount).forall(id => fiber01.exists(_._2 == id))
      consensusFibers && publicApiFibers
    }

    def restoreMissingFibers = {
      val missingIds = (0 until replicaCount).filterNot(id => 
        fiber02.exists(_._2 == id) && fiber01.exists(_._2 == id)
      )
      
      missingIds.toList.traverse_ { id =>
        IO.println(s"Attempting to restore missing fiber for replica $id") >>
        restoreFiber(id).attempt.flatMap {
          case Left(e) => IO.println(s"Failed to restore fiber $id: ${e.getMessage}")
          case Right(_) => IO.println(s"Successfully restored fiber $id")
        }
      }
    }

    IO.println("Checking fibers status...") >>
    (for {
      present <- IO.delay(allFibersPresent)
      _ <- if (!present) {
        IO.println("Some fibers are missing, attempting to restore...") >>
        restoreMissingFibers >>
        IO.sleep(5.seconds)
      } else IO.unit
    } yield present)
      .iterateUntil(identity)
      .timeout(30.seconds)
      .attempt.flatMap {
        case Left(e) => 
          IO.println(s"Fiber check failed: ${e.getMessage}") >>
          IO.println(s"Current consensus fibers: ${fiber02.map(_._2).sorted}") >>
          IO.println(s"Current public API fibers: ${fiber01.map(_._2).sorted}") >>
          IO.raiseError(e)
        case Right(_) => 
          IO.println("All fibers are running correctly") >>
          IO.unit
      }
  }

  val cleanupDir = FunFixture[Unit](
    setup = { _ =>
      (for {
        // First ensure all fibers are running
        _ <- waitForAllFibers
        
        // Then perform file cleanup
        _ <- IO {
          List(
            (userWalletDb(1), "userWalletDb1"),
            (userWalletMnemonic(1), "userWalletMnemonic1"),
            (userWalletJson(1), "userWalletJson1"),
            (userWalletDb(2), "userWalletDb2"),
            (userWalletMnemonic(2), "userWalletMnemonic2"),
            (userWalletJson(2), "userWalletJson2"),
            (vkFile, "vkFile"),
            ("fundRedeemTx.pbuf", "fundRedeemTx"),
            ("fundRedeemTxProved.pbuf", "fundRedeemTxProved"),
            ("redeemTx.pbuf", "redeemTx"),
            ("redeemTxProved.pbuf", "redeemTxProved")
          ).foreach { case (path, name) =>
            try {
              Files.delete(Paths.get(path))
              IO.println(s"Deleted $name")
            } catch {
              case _: Throwable => () // File might not exist, which is fine
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
