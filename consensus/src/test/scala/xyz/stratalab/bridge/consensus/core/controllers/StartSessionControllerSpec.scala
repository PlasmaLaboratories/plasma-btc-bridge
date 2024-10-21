package xyz.stratalab.bridge.consensus.core.controllers

import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite
import xyz.stratalab.bridge.consensus.core.controllers.StartSessionController
import xyz.stratalab.bridge.consensus.core.managers.{BTCWalletAlgebraImpl, WalletManagementUtils}
import xyz.stratalab.bridge.consensus.core.utils.KeyGenerationUtils
import xyz.stratalab.bridge.consensus.core.{
  BridgeWalletManager,
  CurrentStrataHeightRef,
  PeginWalletManager,
  RegTest,
  StrataKeypair,
  StrataPrivatenet
}
import xyz.stratalab.bridge.shared.{InvalidHash, InvalidKey, StartSessionOperation}
import xyz.stratalab.sdk.builders.TransactionBuilderApi
import xyz.stratalab.sdk.constants.NetworkConstants
import xyz.stratalab.sdk.dataApi.RpcChannelResource
import xyz.stratalab.sdk.servicekit.{
  FellowshipStorageApi,
  TemplateStorageApi,
  WalletKeyApi,
  WalletStateApi,
  WalletStateResource
}
import xyz.stratalab.sdk.wallet.WalletApi

import java.nio.file.{Files, Path, Paths}

class StartSessionControllerSpec
    extends CatsEffectSuite
    with WalletStateResource
    with RpcChannelResource
    with SharedData {

  val tmpDirectory = FunFixture[Path](
    setup = { _ =>
      try
        Files.delete(Paths.get(toplWalletDb))
      catch {
        case _: Throwable => ()
      }
      val initialWalletDb = Paths.get(toplWalletDbInitial)
      Files.copy(initialWalletDb, Paths.get(toplWalletDb))
    },
    teardown = { _ =>
      Files.delete(Paths.get(toplWalletDb))
    }
  )

  tmpDirectory.test("StartSessionController should start a pegin session") { _ =>
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
