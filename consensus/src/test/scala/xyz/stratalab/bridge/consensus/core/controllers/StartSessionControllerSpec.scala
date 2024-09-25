package xyz.stratalab.bridge.consensus.core.controllers

import cats.effect.IO
import cats.effect.kernel.Ref
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.RpcChannelResource
import co.topl.brambl.servicekit.FellowshipStorageApi
import co.topl.brambl.servicekit.TemplateStorageApi
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.brambl.servicekit.WalletStateApi
import co.topl.brambl.servicekit.WalletStateResource
import co.topl.brambl.wallet.WalletApi
import xyz.stratalab.bridge.consensus.core.BridgeWalletManager
import xyz.stratalab.bridge.consensus.core.CurrentStrataHeightRef
import xyz.stratalab.bridge.consensus.core.PeginWalletManager
import xyz.stratalab.bridge.consensus.core.RegTest
import xyz.stratalab.bridge.consensus.core.StrataKeypair
import xyz.stratalab.bridge.consensus.core.StrataPrivatenet
import xyz.stratalab.bridge.consensus.core.managers.BTCWalletAlgebraImpl
import xyz.stratalab.bridge.consensus.core.managers.WalletManagementUtils
import xyz.stratalab.bridge.consensus.core.utils.KeyGenerationUtils
import xyz.stratalab.bridge.shared.StartSessionOperation
import xyz.stratalab.bridge.shared.InvalidHash
import xyz.stratalab.bridge.shared.InvalidKey
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import xyz.stratalab.bridge.consensus.core.controllers.StartSessionController

class StartSessionControllerSpec
    extends CatsEffectSuite
    with WalletStateResource
    with RpcChannelResource
    with SharedData {

  val tmpDirectory = FunFixture[Path](
    setup = { _ =>
      try {
        Files.delete(Paths.get(toplWalletDb))
      } catch {
        case _: Throwable => ()
      }
      val initialWalletDb = Paths.get(toplWalletDbInitial)
      Files.copy(initialWalletDb, Paths.get(toplWalletDb))
    },
    teardown = { _ =>
      Files.delete(Paths.get(toplWalletDb))
    }
  )

  tmpDirectory.test("StartSessionController should start a pegin session") {
    _ =>
      val walletKeyApi = WalletKeyApi.make[IO]()
      implicit val walletApi = WalletApi.make[IO](walletKeyApi)
      val walletManagementUtils = new WalletManagementUtils(
        walletApi,
        walletKeyApi
      )
      implicit val walletStateAlgebra = WalletStateApi
        .make[IO](walletResource(toplWalletDb), walletApi)
      implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
        StrataPrivatenet.networkId,
        NetworkConstants.MAIN_LEDGER_ID
      )

      implicit val fellowshipStorageApi =
        FellowshipStorageApi.make(walletResource(toplWalletDb))
      implicit val templateStorageApi =
        TemplateStorageApi.make(walletResource(toplWalletDb))
      assertIOBoolean(
        (for {
          km0 <- KeyGenerationUtils.createKeyManager[IO](
            RegTest,
            peginWalletFile,
            testPassword
          )
          keyPair <- walletManagementUtils.loadKeys(
            toplWalletFile,
            testStrataPassword
          )
          currentStrataHeight <- Ref[IO].of(1L)
        } yield {
          implicit val peginWallet =
            new PeginWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
          implicit val bridgeWallet =
            new BridgeWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
          implicit val toplKeypair = new StrataKeypair(keyPair)
          implicit val currentStrataHeightRef =
            new CurrentStrataHeightRef[IO](currentStrataHeight)
          implicit val btcNetwork = RegTest
          (for {
            res <- StartSessionController.startPeginSession[IO](
              "pegin",
              StartSessionOperation(
                None,
                testKey,
                testHash
              )
            )
          } yield (res.toOption.get._1.btcPeginCurrentWalletIdx == 0))
        }).flatten
      )
  }

  tmpDirectory.test(
    "StartSessionController should fai with invalid key (pegin)"
  ) { _ =>
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    implicit val walletStateAlgebra = WalletStateApi
      .make[IO](walletResource(toplWalletDb), walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      StrataPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )

    implicit val fellowshipStorageApi =
      FellowshipStorageApi.make(walletResource(toplWalletDb))
    implicit val templateStorageApi =
      TemplateStorageApi.make(walletResource(toplWalletDb))
    assertIOBoolean((for {
      keypair <- walletManagementUtils.loadKeys(
        toplWalletFile,
        testStrataPassword
      )
      km0 <- KeyGenerationUtils.createKeyManager[IO](
        RegTest,
        peginWalletFile,
        testPassword
      )
      currentStrataHeight <- Ref[IO].of(1L)
    } yield {
      implicit val peginWallet =
        new PeginWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
      implicit val bridgeWallet =
        new BridgeWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
      implicit val toplKeypair = new StrataKeypair(keypair)
      implicit val currentStrataHeightRef =
        new CurrentStrataHeightRef[IO](currentStrataHeight)
      implicit val btcNetwork = RegTest
      (for {
        res <- StartSessionController.startPeginSession[IO](
          "pegin",
          StartSessionOperation(
            None,
            "invalidKey",
            testHash
          )
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidKey(
        "Invalid key invalidKey"
      ))
    }).flatten)
  }

  test("StartSessionController should fai with invalid hash") {
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    implicit val walletStateAlgebra = WalletStateApi
      .make[IO](walletResource(toplWalletDb), walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      StrataPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )

    implicit val fellowshipStorageApi =
      FellowshipStorageApi.make(walletResource(toplWalletDb))
    implicit val templateStorageApi =
      TemplateStorageApi.make(walletResource(toplWalletDb))

    assertIOBoolean(
      (for {
        keypair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testStrataPassword
        )
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        currentStrataHeight <- Ref[IO].of(1L)

      } yield {
        implicit val peginWallet =
          new PeginWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
        implicit val bridgeWallet =
          new BridgeWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
        implicit val toplKeypair = new StrataKeypair(keypair)
        implicit val currentStrataHeightRef =
          new CurrentStrataHeightRef[IO](currentStrataHeight)
        implicit val btcNetwork = RegTest
        for {
          res <- StartSessionController.startPeginSession[IO](
            "pegin",
            StartSessionOperation(
              None,
              testKey,
              "invalidHash"
            )
          )
        } yield res.isLeft && res.swap.toOption.get == InvalidHash(
          "Invalid hash invalidHash"
        )
      }).flatten
    )
  }

}