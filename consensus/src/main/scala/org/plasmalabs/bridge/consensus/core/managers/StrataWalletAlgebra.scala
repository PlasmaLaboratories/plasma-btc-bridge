package org.plasmalabs.bridge.consensus.core.managers

import cats.data.OptionT
import cats.effect.kernel.Sync
import com.google.protobuf.ByteString
import io.circe.Json
import quivr.models.{KeyPair, VerificationKey}
import org.plasmalabs.bridge.consensus.core.{Fellowship, StrataNetworkIdentifiers, Template}
import org.plasmalabs.bridge.consensus.shared.Lvl
import org.plasmalabs.bridge.shared.{InvalidHash, InvalidInput, InvalidKey}
import org.plasmalabs.indexer.services.Txo
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.codecs.AddressCodecs
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.dataApi.{
  FellowshipStorageAlgebra,
  IndexerQueryAlgebra,
  TemplateStorageAlgebra,
  WalletFellowship,
  WalletStateAlgebra,
  WalletTemplate
}
import org.plasmalabs.sdk.models.box.AssetMintingStatement
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.models.{LockAddress, LockId}
import org.plasmalabs.sdk.utils.Encoding
import org.plasmalabs.sdk.wallet.WalletApi

object StrataWalletAlgebra {

  import WalletApiHelpers._
  import AssetMintingOps._

  private def computeSerializedTemplateMintLock[F[_]: Sync](
    sha256: String,
    min:    Long,
    max:    Long
  ) = {
    import cats.implicits._
    for {
      decodedHex <- OptionT(
        Encoding
          .decodeFromHex(sha256)
          .toOption
          .map(x => Sync[F].delay(x))
          .orElse(
            Some(
              Sync[F].raiseError[Array[Byte]](
                new InvalidHash(
                  s"Invalid hash $sha256"
                )
              )
            )
          )
          .sequence
      )
      lockTemplateAsJson <- OptionT(
        Sync[F].delay(templateFromSha(decodedHex, min, max).some)
      )
    } yield lockTemplateAsJson
  }

  def setupBridgeWalletForMinting[F[_]: Sync](
    fromFellowship:   String,
    mintTemplateName: String,
    keypair:          KeyPair,
    sha256:           String,
    min:              Long,
    max:              Long
  )(implicit
    fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
    templateStorageAlgebra:   TemplateStorageAlgebra[F],
    tba:                      TransactionBuilderApi[F],
    walletApi:                WalletApi[F],
    wsa:                      WalletStateAlgebra[F]
  ): F[Option[(String, String)]] = {

    import cats.implicits._
    import TransactionBuilderApi.implicits._
    implicit class ImplicitConversion[A](x: F[A]) {
      def optionT = OptionT(x.map(_.some))
    }
    implicit class ImplicitConversion1[A](x: F[Option[A]]) {
      def liftT = OptionT(x)
    }
    (for {
      lockTemplateAsJson <- computeSerializedTemplateMintLock(
        sha256,
        min,
        max
      )
      _ <- fellowshipStorageAlgebra
        .addFellowship(
          WalletFellowship(0, fromFellowship)
        )
        .optionT
      _ <- templateStorageAlgebra
        .addTemplate(
          WalletTemplate(0, mintTemplateName, lockTemplateAsJson)
        )
        .optionT
      indices <- wsa
        .getNextIndicesForFunds(
          fromFellowship,
          mintTemplateName
        )
        .liftT
      bk <- walletApi
        .deriveChildKeysPartial(
          keypair,
          indices.x,
          indices.y
        )
        .optionT
      bridgeVk <- walletApi
        .deriveChildVerificationKey(
          bk.vk,
          1
        )
        .optionT
      lockTempl <- wsa
        .getLockTemplate(mintTemplateName)
        .liftT
      bridgePartialVk = Encoding.encodeToBase58(
        bk.vk.toByteArray
      )
      deriveChildKeyBridgeString = Encoding.encodeToBase58(
        bridgeVk.toByteArray
      )
      lock <- lockTempl
        .build(
          bridgeVk :: Nil
        )
        .map(_.toOption)
        .liftT
      lockAddress <- tba.lockAddress(lock).optionT
      _ <- wsa
        .updateWalletState(
          Encoding.encodeToBase58Check(
            lock.getPredicate.toByteArray
          ), // lockPredicate
          lockAddress.toBase58(), // lockAddress
          Some("ExtendedEd25519"),
          Some(deriveChildKeyBridgeString),
          indices
        )
        .optionT
      _ <- wsa
        .addEntityVks(
          fromFellowship,
          mintTemplateName,
          bridgePartialVk :: Nil
        )
        .optionT
      currentAddress <- wsa
        .getAddress(
          fromFellowship,
          mintTemplateName,
          None
        )
        .liftT
    } yield (currentAddress, bridgePartialVk)).value
  }

  private def computeSerializedTemplate[F[_]: Sync](
    sha256:        String,
    waitTime:      Int,
    currentHeight: Int
  ) = {
    import cats.implicits._
    for {
      decodedHex <- OptionT(
        Encoding
          .decodeFromHex(sha256)
          .toOption
          .map(x => Sync[F].delay(x))
          .orElse(
            Some(
              Sync[F].raiseError[Array[Byte]](
                new InvalidHash(
                  s"Invalid hash $sha256"
                )
              )
            )
          )
          .sequence
      )
      _ <-
        if (currentHeight <= 0) {
          OptionT(
            Sync[F].raiseError[Option[Unit]](
              new InvalidInput(
                s"Invalid block height $currentHeight"
              )
            )
          )
        } else {
          OptionT.some[F](())
        }
      lockTemplateAsJson <- OptionT(Sync[F].delay(s"""{
                "threshold":1,
                "innerTemplates":[
                  {
                    "left": {"routine":"ExtendedEd25519","entityIdx":0,"type":"signature"},
                    "right": {"chain":"header","min": ${currentHeight + waitTime + 1},"max":9223372036854775807,"type":"height"},
                    "type": "and"
                  },
                  {
                    "left": {"chain":"header","min": ${currentHeight},"max": ${currentHeight + waitTime},"type":"height"},
                    "right": {
                      "type": "and",
                      "left": {"routine":"ExtendedEd25519","entityIdx":1,"type":"signature"},
                      "right": {"routine":"Sha256","digest": "${Encoding
          .encodeToBase58(decodedHex)}","type":"digest"}
                    },
                    "type": "and"
                  }
                ],
                "type":"predicate"}
              """.some))
    } yield lockTemplateAsJson
  }

  def setupBridgeWallet[F[_]: Sync](
    networkId:      StrataNetworkIdentifiers,
    keypair:        KeyPair,
    userBaseKey:    String,
    fellowshipName: String,
    templateName:   String,
    sha256:         String,
    waitTime:       Int,
    currentHeight:  Int
  )(implicit
    fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
    templateStorageAlgebra:   TemplateStorageAlgebra[F],
    walletApi:                WalletApi[F],
    wsa:                      WalletStateAlgebra[F]
  ): F[Option[String]] = {

    import cats.implicits._
    import org.plasmalabs.sdk.common.ContainsEvidence.Ops
    import org.plasmalabs.sdk.common.ContainsImmutable.instances._
    import TransactionBuilderApi.implicits._
    implicit class ImplicitConversion[A](x: F[A]) {
      def optionT = OptionT(x.map(_.some))
    }
    implicit class ImplicitConversion1[A](x: F[Option[A]]) {
      def liftT = OptionT(x)
    }
    (for {
      _ <-
        fellowshipStorageAlgebra
          .addFellowship(
            WalletFellowship(0, fellowshipName)
          )
          .optionT
      lockTemplateAsJson <- computeSerializedTemplate(
        sha256,
        waitTime,
        currentHeight
      )
      _ <- templateStorageAlgebra
        .addTemplate(
          WalletTemplate(0, templateName, lockTemplateAsJson)
        )
        .optionT
      indices <- wsa
        .getCurrentIndicesForFunds(
          fellowshipName,
          templateName,
          None
        )
        .liftT
      bk <- Sync[F]
        .fromEither(Encoding.decodeFromBase58(userBaseKey))
        .handleErrorWith(_ =>
          Sync[F].raiseError(
            new InvalidKey(
              s"Invalid key $userBaseKey"
            )
          )
        )
        .optionT
      userVk <- walletApi
        .deriveChildVerificationKey(
          VerificationKey.parseFrom(
            bk
          ),
          1
        )
        .optionT
      lockTempl <- wsa
        .getLockTemplate(templateName)
        .liftT
      deriveChildKeyUserString = Encoding.encodeToBase58(
        userVk.toByteArray
      )
      bridgeKey <- walletApi
        .deriveChildKeys(keypair, indices)
        .map(_.vk)
        .optionT
      deriveChildKeyBridgeString = Encoding.encodeToBase58(
        bridgeKey.toByteArray
      )
      lock <- lockTempl
        .build(
          userVk :: bridgeKey :: Nil
        )
        .map(_.toOption)
        .liftT
      lockAddress = LockAddress(
        networkId.networkId,
        NetworkConstants.MAIN_LEDGER_ID,
        LockId(lock.sizedEvidence.digest.value)
      )
      _ <- wsa
        .updateWalletState(
          Encoding.encodeToBase58Check(
            lock.getPredicate.toByteArray
          ), // lockPredicate
          lockAddress.toBase58(), // lockAddress
          Some("ExtendedEd25519"),
          Some(deriveChildKeyBridgeString),
          indices
        )
        .optionT
      _ <- wsa
        .addEntityVks(
          fellowshipName,
          templateName,
          deriveChildKeyUserString :: deriveChildKeyBridgeString :: Nil
        )
        .optionT
      currentAddress <- wsa
        .getAddress(
          fellowshipName,
          templateName,
          None
        )
        .liftT
    } yield currentAddress).value
  }

  private def sharedOps[F[_]: Sync](
    fromFellowship:      Fellowship,
    fromTemplate:        Template,
    someFromInteraction: Option[Int]
  )(implicit
    wsa: WalletStateAlgebra[F]
  ) = {
    import cats.implicits._
    for {
      someCurrentIndices <- getCurrentIndices(
        fromFellowship,
        fromTemplate,
        someFromInteraction
      )
      predicateFundsToUnlock <- getPredicateFundsToUnlock[F](someCurrentIndices)
      someNextIndices        <- getNextIndices(fromFellowship, fromTemplate)
      changeLock <- getChangeLockPredicate[F](
        someNextIndices,
        fromFellowship,
        fromTemplate
      )
    } yield (
      predicateFundsToUnlock.get,
      someCurrentIndices,
      someNextIndices,
      changeLock
    )
  }

  def createSimpleAssetMintingTransactionFromParams[F[_]: Sync](
    keyPair:               KeyPair,
    fromFellowship:        Fellowship,
    fromTemplate:          Template,
    someFromInteraction:   Option[Int],
    fee:                   Lvl,
    ephemeralMetadata:     Option[Json],
    commitment:            Option[ByteString],
    assetMintingStatement: AssetMintingStatement,
    redeemLockAddress:     String
  )(implicit
    tba:         TransactionBuilderApi[F],
    walletApi:   WalletApi[F],
    wsa:         WalletStateAlgebra[F],
    utxoAlgebra: IndexerQueryAlgebra[F]
  ): F[IoTransaction] = {
    import cats.implicits._
    for {
      tuple <- sharedOps(
        fromFellowship,
        fromTemplate,
        someFromInteraction
      )
      (
        predicateFundsToUnlock,
        someCurrentIndices,
        someNextIndices,
        changeLock
      ) = tuple
      fromAddress <- tba.lockAddress(
        predicateFundsToUnlock
      )
      response <- utxoAlgebra
        .queryUtxo(fromAddress)
        .attempt
        .flatMap {
          _ match {
            case Left(_) =>
              Sync[F].raiseError(
                CreateTxError("Problem contacting network")
              ): F[Seq[Txo]]
            case Right(txos) => Sync[F].pure(txos: Seq[Txo])
          }
        }
      lvlTxos = response.filter(
        _.transactionOutput.value.value.isLvl
      )
      nonLvlTxos = response.filter(x =>
        (
          !x.transactionOutput.value.value.isLvl &&
          x.outputAddress != assetMintingStatement.groupTokenUtxo &&
          x.outputAddress != assetMintingStatement.seriesTokenUtxo
        )
      )
      groupTxo <- response
        .filter(
          _.transactionOutput.value.value.isGroup
        )
        .find(_.outputAddress == assetMintingStatement.groupTokenUtxo)
        .map(Sync[F].delay(_))
        .getOrElse(
          Sync[F].raiseError(
            new Exception(
              "Group token utxo not found"
            )
          )
        )
      seriesTxo <- response
        .filter(
          _.transactionOutput.value.value.isSeries
        )
        .find(_.outputAddress == assetMintingStatement.seriesTokenUtxo)
        .map(Sync[F].delay(_))
        .getOrElse(
          Sync[F].raiseError(
            new Exception(
              "Series token utxo not found"
            )
          )
        )
      ioTransaction <- buildAssetTxAux(
        keyPair,
        lvlTxos,
        nonLvlTxos,
        groupTxo,
        seriesTxo,
        fromAddress,
        predicateFundsToUnlock.getPredicate,
        fee,
        someNextIndices,
        assetMintingStatement,
        ephemeralMetadata,
        commitment,
        changeLock,
        AddressCodecs.decodeAddress(redeemLockAddress).toOption.get
      )
    } yield ioTransaction
  }
}
