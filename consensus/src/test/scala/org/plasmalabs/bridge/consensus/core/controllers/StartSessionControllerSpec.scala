package org.plasmalabs.bridge.consensus.core.controllers

import cats.effect.IO
import cats.effect.kernel.Ref
import munit.CatsEffectSuite
import org.plasmalabs.bridge.consensus.core.controllers.StartSessionController
import org.plasmalabs.bridge.consensus.core.managers.{BTCWalletAlgebraImpl, WalletManagementUtils}
import org.plasmalabs.bridge.consensus.core.utils.KeyGenerationUtils
import org.plasmalabs.bridge.consensus.core.{
  BridgeWalletManager,
  CurrentPlasmaHeightRef,
  PeginWalletManager,
  PlasmaKeypair,
  PlasmaPrivatenet,
  RegTest
}
import org.plasmalabs.bridge.shared.{InvalidHash, InvalidKey, StartSessionOperation}
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.dataApi.RpcChannelResource
import org.plasmalabs.sdk.servicekit.{
  FellowshipStorageApi,
  TemplateStorageApi,
  WalletKeyApi,
  WalletStateApi,
  WalletStateResource
}
import org.plasmalabs.sdk.wallet.WalletApi
import org.bitcoins.core.crypto.ExtPublicKey

import java.nio.file.{Files, Path, Paths}

class StartSessionControllerSpec
    extends CatsEffectSuite
    with WalletStateResource
    with RpcChannelResource
    with SharedData {

  private val tmpDirectory = FunFixture[Path](
    setup = { _ =>
      try
        Files.delete(Paths.get(plasmaWalletDb))
      catch {
        case _: Throwable => ()
      }
      val initialWalletDb = Paths.get(plasmaWalletDbInitial)
      Files.copy(initialWalletDb, Paths.get(plasmaWalletDb))
    },
    teardown = { _ =>
      Files.delete(Paths.get(plasmaWalletDb))
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
      .make[IO](walletResource[IO](plasmaWalletDb), walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      PlasmaPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )

    implicit val fellowshipStorageApi =
      FellowshipStorageApi.make(walletResource[IO](plasmaWalletDb))
    implicit val templateStorageApi =
      TemplateStorageApi.make(walletResource[IO](plasmaWalletDb))
    assertIOBoolean(
      (for {
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        keyPair <- walletManagementUtils.loadKeys(
          plasmaWalletFile,
          testPlasmaPassword
        )
        currentPlasmaHeight <- Ref[IO].of(1L)
      } yield {
        implicit val peginWallet =
          new PeginWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
        implicit val bridgeWallet =
          new BridgeWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
        implicit val plasmaKeypair = new PlasmaKeypair(keyPair)
        implicit val currentPlasmaHeightRef =
          new CurrentPlasmaHeightRef[IO](currentPlasmaHeight)
        implicit val btcNetwork = RegTest
        import org.bitcoins.core.crypto.ExtPublicKey

        implicit val allReplicasPublicKeys: List[(Int, ExtPublicKey)] = List.empty

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
      .make[IO](walletResource[IO](plasmaWalletDb), walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      PlasmaPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )

    implicit val fellowshipStorageApi =
      FellowshipStorageApi.make(walletResource[IO](plasmaWalletDb))
    implicit val templateStorageApi =
      TemplateStorageApi.make(walletResource[IO](plasmaWalletDb))
    assertIOBoolean((for {
      keypair <- walletManagementUtils.loadKeys(
        plasmaWalletFile,
        testPlasmaPassword
      )
      km0 <- KeyGenerationUtils.createKeyManager[IO](
        RegTest,
        peginWalletFile,
        testPassword
      )
      currentPlasmaHeight <- Ref[IO].of(1L)
    } yield {
      implicit val peginWallet =
        new PeginWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
      implicit val bridgeWallet =
        new BridgeWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
      implicit val plasmaKeypair = new PlasmaKeypair(keypair)
      implicit val currentPlasmaHeightRef =
        new CurrentPlasmaHeightRef[IO](currentPlasmaHeight)
      implicit val btcNetwork = RegTest
      import org.bitcoins.core.crypto.ExtPublicKey

      implicit val allReplicasPublicKeys: List[(Int, ExtPublicKey)] = List.empty
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
      .make[IO](walletResource[IO](plasmaWalletDb), walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      PlasmaPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )

    implicit val fellowshipStorageApi =
      FellowshipStorageApi.make(walletResource[IO](plasmaWalletDb))
    implicit val templateStorageApi =
      TemplateStorageApi.make(walletResource[IO](plasmaWalletDb))

    assertIOBoolean(
      (for {
        keypair <- walletManagementUtils.loadKeys(
          plasmaWalletFile,
          testPlasmaPassword
        )
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        currentPlasmaHeight <- Ref[IO].of(1L)

      } yield {
        implicit val peginWallet =
          new PeginWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
        implicit val bridgeWallet =
          new BridgeWalletManager(BTCWalletAlgebraImpl.make[IO](km0).unsafeRunSync())
        implicit val plasmaKeypair = new PlasmaKeypair(keypair)
        import org.bitcoins.core.crypto.ExtPublicKey

        implicit val allReplicasPublicKeys: List[(Int, ExtPublicKey)] = List.empty
        implicit val currentPlasmaHeightRef =
          new CurrentPlasmaHeightRef[IO](currentPlasmaHeight)
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
