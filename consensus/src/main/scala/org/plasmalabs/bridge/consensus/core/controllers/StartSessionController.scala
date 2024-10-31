package org.plasmalabs.bridge.consensus.core.controllers

import cats.effect.kernel.{Async, Sync}
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.{P2WPKHWitnessSPKV0, WitnessScriptPubKey}
import org.bitcoins.core.script.constant.{OP_0, ScriptConstant}
import org.bitcoins.core.util.{BitcoinScriptUtil, BytesUtil}
import org.bitcoins.crypto.{ECPublicKey, _}
import org.plasmalabs.bridge.consensus.core.managers.PlasmaWalletAlgebra
import org.plasmalabs.bridge.consensus.core.utils.BitcoinUtils
import org.plasmalabs.bridge.consensus.core.{
  BitcoinNetworkIdentifiers,
  BridgeWalletManager,
  CurrentPlasmaHeightRef,
  PeginWalletManager,
  PlasmaKeypair
}
import org.plasmalabs.bridge.consensus.shared.{
  BTCWaitExpirationTime,
  PeginSessionInfo,
  PeginSessionState,
  PlasmaWaitExpirationTime
}
import org.plasmalabs.bridge.shared.{
  BridgeError,
  InvalidHash,
  InvalidInput,
  InvalidKey,
  StartPeginSessionResponse,
  StartSessionOperation
}
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.dataApi.{FellowshipStorageAlgebra, TemplateStorageAlgebra, WalletStateAlgebra}
import org.plasmalabs.sdk.wallet.WalletApi
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.util.UUID

object StartSessionController {

  private def createPeginSessionInfo[F[_]: Sync](
    btcPeginCurrentWalletIdx:  Int,
    btcBridgeCurrentWalletIdx: Int,
    mintTemplateName:          String,
    sha256:                    String,
    pUserPKey:                 String,
    btcPeginBridgePKey:        String,
    btcBridgePKey:             ECPublicKey,
    btcWaitExpirationTime:     BTCWaitExpirationTime,
    btcNetwork:                BitcoinNetworkIdentifiers,
    redeemAddress:             String,
    minHeight:                 Long,
    maxHeight:                 Long
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
    req:       StartSessionOperation
  )(implicit
    toplKeypair:              PlasmaKeypair,
    btcNetwork:               BitcoinNetworkIdentifiers,
    currentPlasmaHeight:      CurrentPlasmaHeightRef[F],
    pegInWalletManager:       PeginWalletManager[F],
    bridgeWalletManager:      BridgeWalletManager[F],
    fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
    templateStorageAlgebra:   TemplateStorageAlgebra[F],
    toplWaitExpirationTime:   PlasmaWaitExpirationTime,
    btcWaitExpirationTime:    BTCWaitExpirationTime,
    tba:                      TransactionBuilderApi[F],
    walletApi:                WalletApi[F],
    wsa:                      WalletStateAlgebra[F]
  ): F[Either[BridgeError, (PeginSessionInfo, StartPeginSessionResponse)]] = {
    import cats.implicits._
    import PlasmaWalletAlgebra._

    import org.typelevel.log4cats.syntax._

    (for {
      idxAndnewKey <- pegInWalletManager.underlying.getCurrentPubKeyAndPrepareNext()
      (btcPeginCurrentWalletIdx, btcPeginBridgePKey) = idxAndnewKey
      bridgeIdxAndnewKey <- bridgeWalletManager.underlying.getCurrentPubKeyAndPrepareNext()
      (btcBridgeCurrentWalletIdx, btcBridgePKey) = bridgeIdxAndnewKey
      mintTemplateName <- Sync[F].delay(UUID.randomUUID().toString)
      fromFellowship = mintTemplateName
      minPlasmaHeight <- currentPlasmaHeight.underlying.get
      _ <-
        if (minPlasmaHeight == 0)
          Sync[F].raiseError(new IllegalStateException("Plasma height is 0"))
        else Sync[F].unit
      maxPlasmaHeight = minPlasmaHeight + toplWaitExpirationTime.underlying
      someRedeemAdressAndKey <- setupBridgeWalletForMinting(
        fromFellowship,
        mintTemplateName,
        toplKeypair.underlying,
        req.sha256,
        minPlasmaHeight,
        maxPlasmaHeight
      )
      someRedeemAdress = someRedeemAdressAndKey.map(_._1)
      _ = assert(
        someRedeemAdress.isDefined,
        "Redeem address was not generated correctly"
      )
      bridgeNodeKey = someRedeemAdressAndKey.map(_._2).get
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
        minPlasmaHeight,
        maxPlasmaHeight
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
        minPlasmaHeight,
        maxPlasmaHeight
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
