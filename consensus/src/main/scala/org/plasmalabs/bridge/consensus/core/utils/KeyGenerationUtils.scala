package org.plasmalabs.bridge.consensus.core.utils

import cats.effect.kernel.Sync
import cats.implicits._
import org.bitcoins.core.crypto.{ExtPublicKey, MnemonicCode}
import org.bitcoins.core.hd.{BIP32Path, HDAccount, HDPath, HDPurposes}
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto.{AesPassword, ECDigitalSignature, ECPublicKey, HashType}
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.plasmalabs.bridge.consensus.core.BitcoinNetworkIdentifiers
import scodec.bits.ByteVector

object KeyGenerationUtils {

  def signWithKeyManager[F[_]: Sync](
    km:         BIP39KeyManager,
    txBytes:    ByteVector,
    currentIdx: Int
  ): F[String] =
    for {
      signed <- Sync[F].delay(
        km.toSign(HDPath.fromString("m/84'/1'/0'/0/" + currentIdx))
          .sign(txBytes)
      )
      canonicalSignature <- Sync[F].delay(
        ECDigitalSignature(
          signed.bytes ++ ByteVector.fromByte(HashType.sigHashAll.byte)
        )
      )
    } yield canonicalSignature.hex

  def loadKeyManager[F[_]: Sync](
    btcNetwork: BitcoinNetworkIdentifiers,
    seedFile:   String,
    password:   String
  ): F[BIP39KeyManager] =
    for {
      seedPath <- Sync[F].delay(
        new java.io.File(seedFile).getAbsoluteFile.toPath
      )
      purpose = HDPurposes.SegWit
      kmParams = KeyManagerParams(seedPath, purpose, btcNetwork.btcNetwork)
      aesPasswordOpt = Some(AesPassword.fromString(password))
      km <- Sync[F].fromEither(
        BIP39KeyManager
          .fromParams(
            kmParams,
            aesPasswordOpt,
            None
          )
          .left
          .map(_ => new IllegalArgumentException("Invalid params"))
      )
    } yield km

  def createKeyManager[F[_]: Sync](
    btcNetwork: BitcoinNetworkIdentifiers,
    seedFile:   String,
    password:   String
  ): F[BIP39KeyManager] =
    for {
      seedPath <- Sync[F].delay(
        new java.io.File(seedFile).getAbsoluteFile.toPath
      )
      purpose = HDPurposes.SegWit
      kmParams = KeyManagerParams(seedPath, purpose, btcNetwork.btcNetwork)
      aesPasswordOpt = Some(AesPassword.fromString(password))
      entropy = MnemonicCode.getEntropy256Bits
      mnemonic = MnemonicCode.fromEntropy(entropy)
      km <- Sync[F].fromEither(
        BIP39KeyManager.initializeWithMnemonic(
          aesPasswordOpt,
          mnemonic,
          None,
          kmParams
        )
      )
    } yield km

  def generateKey[F[_]: Sync](
    km:         BIP39KeyManager,
    currentIdx: Int
  ): F[ECPublicKey] =
    for {
      hdAccount <- Sync[F].fromOption(
        HDAccount.fromPath(
          BIP32Path.fromString("m/84'/1'/0'")
        ) // this is the standard account path for segwit
        ,
        new IllegalArgumentException("Invalid account path")
      )
      pKey <- Sync[F].delay(
        km.deriveXPub(hdAccount)
          .get
          .deriveChildPubKey(BIP32Path.fromString("m/0/" + currentIdx.toString))
          .get
          .key
      )
    } yield (pKey)

  def generateSharableKey(
    km: BIP39KeyManager
  ): ExtPublicKey = km.getRootXPub

  def deriveChildFromSharedPublicKey[F[_]: Sync](
    extendedPubKey: ExtPublicKey,
    currentIdx:     Int
  ): F[ECPublicKey] =
    for {
      childKey <- Sync[F].delay(
        extendedPubKey
          .deriveChildPubKey(BIP32Path.fromString("m/84/1/0/0/" + currentIdx.toString))
          .get
          .key
      )
    } yield childKey

  def deriveChildrenFromSharedPublicKeys[F[_]: Sync](
    extendedPubKeys: List[(Int, ExtPublicKey)],
    currentIdx:      Int
  ): F[List[(Int, ECPublicKey)]] =
    for {
      childKeys <- extendedPubKeys.traverse { case (id, key) =>
        deriveChildFromSharedPublicKey(key, currentIdx).map { ecKey =>
          (id, ECPublicKey.fromHex(ecKey.hex))
        }
      }
    } yield (childKeys)
}
