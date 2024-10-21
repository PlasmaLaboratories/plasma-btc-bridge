package xyz.stratalab.bridge.consensus.core.utils

import cats.effect.kernel.Sync
import org.bitcoins.core.crypto.MnemonicCode
import org.bitcoins.core.hd.{BIP32Path, HDAccount, HDPath, HDPurposes}
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto.{AesPassword, ECDigitalSignature, ECPublicKey, HashType}
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import scodec.bits.ByteVector
import xyz.stratalab.bridge.consensus.core.BitcoinNetworkIdentifiers

object KeyGenerationUtils {

  def signWithKeyManager[F[_]: Sync](
    km:         BIP39KeyManager,
    txBytes:    ByteVector,
    currentIdx: Int
  ): F[String] = {
    import cats.implicits._
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
  }

  def loadKeyManager[F[_]: Sync](
    btcNetwork: BitcoinNetworkIdentifiers,
    seedFile:   String,
    password:   String
  ): F[BIP39KeyManager] = {
    import cats.implicits._
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
  }

  def createKeyManager[F[_]: Sync](
    btcNetwork: BitcoinNetworkIdentifiers,
    seedFile:   String,
    password:   String
  ) = {
    import cats.implicits._
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
  }

  def generateKey[F[_]: Sync](
    km:         BIP39KeyManager,
    currentIdx: Int
  ): F[ECPublicKey] = {
    import cats.implicits._
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
  }
}
