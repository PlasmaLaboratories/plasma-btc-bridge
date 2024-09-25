package xyz.stratalab.bridge.consensus.core.controllers

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.wallet.WalletApi
import xyz.stratalab.bridge.consensus.shared.BTCWaitExpirationTime
import xyz.stratalab.bridge.consensus.core.BitcoinNetworkIdentifiers
import xyz.stratalab.bridge.consensus.core.BridgeWalletManager
import xyz.stratalab.bridge.consensus.core.CurrentStrataHeightRef
import xyz.stratalab.bridge.consensus.shared.PeginSessionState
import xyz.stratalab.bridge.consensus.core.PeginWalletManager
import xyz.stratalab.bridge.consensus.core.StrataKeypair
import xyz.stratalab.bridge.consensus.shared.StrataWaitExpirationTime
import xyz.stratalab.bridge.consensus.shared.PeginSessionInfo
import xyz.stratalab.bridge.consensus.core.managers.StrataWalletAlgebra
import xyz.stratalab.bridge.consensus.core.utils.BitcoinUtils
import xyz.stratalab.bridge.shared.StartSessionOperation
import xyz.stratalab.bridge.shared.BridgeError
import xyz.stratalab.bridge.shared.InvalidHash
import xyz.stratalab.bridge.shared.InvalidInput
import xyz.stratalab.bridge.shared.InvalidKey
import xyz.stratalab.bridge.shared.StartPeginSessionResponse
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.P2WPKHWitnessSPKV0
import org.bitcoins.core.protocol.script.WitnessScriptPubKey
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto._
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.util.UUID

object StartSessionController {

  private def createPeginSessionInfo[F[_]: Sync](
      btcPeginCurrentWalletIdx: Int,
      btcBridgeCurrentWalletIdx: Int,
      mintTemplateName: String,
      sha256: String,
      pUserPKey: String,
      btcPeginBridgePKey: String,
      btcBridgePKey: ECPublicKey,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      btcNetwork: BitcoinNetworkIdentifiers,
      redeemAddress: String,
      minHeight: Long,
      maxHeight: Long
  ): F[(String, PeginSessionInfo)] = {
    import cats.implicits._
    for {
      hash <- Sync[F].fromOption(
        ByteVector.fromHex(sha256.toLowerCase()),
        InvalidHash(s"Invalid hash $sha256")
      )
      _ <- Sync[F].delay(
        if (hash.size != 32)
          throw InvalidHash(s"Sha length is too short, only ${hash.size} bytes")
      )
      userPKey <- Sync[F]
        .delay(ECPublicKey.fromHex(pUserPKey))
        .handleError(_ => throw InvalidKey(s"Invalid key $pUserPKey"))
      asm =
        BitcoinUtils.buildScriptAsm(
          userPKey,
          ECPublicKey.fromHex(btcPeginBridgePKey),
          hash,
          btcWaitExpirationTime.underlying
        )
      scriptAsm = BytesUtil.toByteVector(asm)
      scriptHash = CryptoUtil.sha256(scriptAsm)
      push_op = BitcoinScriptUtil.calculatePushOp(hash)
      address = Bech32Address
        .apply(
          WitnessScriptPubKey
            .apply(
              Seq(OP_0) ++
                push_op ++
                Seq(ScriptConstant.fromBytes(scriptHash.bytes))
            ),
          btcNetwork.btcNetwork
        )
        .value
      claimAddress = Bech32Address
        .apply(
          P2WPKHWitnessSPKV0(btcBridgePKey),
          btcNetwork.btcNetwork
        )
        .value

    } yield (
      address,
      PeginSessionInfo(
        btcPeginCurrentWalletIdx,
        btcBridgeCurrentWalletIdx,
        mintTemplateName,
        redeemAddress,
        address,
        scriptAsm.toHex,
        sha256,
        minHeight,
        maxHeight,
        claimAddress,
        PeginSessionState.PeginSessionStateWaitingForBTC
      )
    )
  }

  def startPeginSession[F[_]: Async: Logger](
      sessionId: String,
      req: StartSessionOperation,
      )(implicit
      toplKeypair: StrataKeypair,
      btcNetwork: BitcoinNetworkIdentifiers,
      currentStrataHeight: CurrentStrataHeightRef[F],
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: StrataWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F]
  ): F[Either[BridgeError, (PeginSessionInfo, StartPeginSessionResponse)]] = {
    import cats.implicits._
    import StrataWalletAlgebra._

    import org.typelevel.log4cats.syntax._

    (for {
      idxAndnewKey <- pegInWalletManager.underlying.getCurrentPubKeyAndPrepareNext()
      (btcPeginCurrentWalletIdx, btcPeginBridgePKey) = idxAndnewKey
      bridgeIdxAndnewKey <- bridgeWalletManager.underlying.getCurrentPubKeyAndPrepareNext()
      (btcBridgeCurrentWalletIdx, btcBridgePKey) = bridgeIdxAndnewKey
      mintTemplateName <- Sync[F].delay(UUID.randomUUID().toString)
      fromFellowship = mintTemplateName
      minStrataHeight <- currentStrataHeight.underlying.get
      _ <-
        if (minStrataHeight == 0)
          Sync[F].raiseError(new IllegalStateException("Strata height is 0"))
        else Sync[F].unit
      maxStrataHeight = minStrataHeight + toplWaitExpirationTime.underlying
      someRedeemAdressAndKey <- setupBridgeWalletForMinting(
        fromFellowship,
        mintTemplateName,
        toplKeypair.underlying,
        req.sha256,
        minStrataHeight,
        maxStrataHeight
      )
      someRedeemAdress = someRedeemAdressAndKey.map(_._1)
      _ = assert(
        someRedeemAdress.isDefined,
        "Redeem address was not generated correctly"
      )
      bridgeBifrostKey = someRedeemAdressAndKey.map(_._2).get
      addressAndsessionInfo <- createPeginSessionInfo(
        btcPeginCurrentWalletIdx,
        btcBridgeCurrentWalletIdx,
        mintTemplateName,
        req.sha256,
        req.pkey,
        btcPeginBridgePKey.hex,
        btcBridgePKey,
        btcWaitExpirationTime,
        btcNetwork,
        someRedeemAdress.get,
        minStrataHeight,
        maxStrataHeight
      )
      (address, sessionInfo) = addressAndsessionInfo
    } yield (
      sessionInfo,
      StartPeginSessionResponse(
        sessionId,
        sessionInfo.scriptAsm,
        address,
        BitcoinUtils
          .createDescriptor(btcPeginBridgePKey.hex, req.pkey, req.sha256),
        minStrataHeight,
        maxStrataHeight
      )
    ).asRight[BridgeError]).handleErrorWith {
      case e: BridgeError =>
        error"Error handling start pegin session request: $e"
        Sync[F].delay(Left(e))
      case t: Throwable =>
        error"Error handling start pegin session request $t" >> Sync[F].delay(
          Left(InvalidInput("Unknown error"))
        )
    }
  }


}