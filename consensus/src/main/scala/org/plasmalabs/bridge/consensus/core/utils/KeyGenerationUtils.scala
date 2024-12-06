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

trait KeyGenerationUtils[F[_]] {

  /** Signs transaction bytes using a key manager.
    *
    * @param km BIP39 key manager instance
    * @param txBytes Transaction bytes to sign
    * @param currentIdx Current derivation index
    * @return Hex-encoded signature
    */
  def signWithKeyManager(
    km: BIP39KeyManager,
    txBytes: ByteVector,
    currentIdx: Int
  ): F[String]

  /** Loads an existing key manager from a seed file.
    *
    * @param btcNetwork Bitcoin network identifiers
    * @param seedFile Path to seed file
    * @param password Password for decryption
    * @return Loaded key manager
    */
  def loadKeyManager(
    btcNetwork: BitcoinNetworkIdentifiers,
    seedFile: String,
    password: String
  ): F[BIP39KeyManager]

  /** Creates a new key manager with a fresh mnemonic.
    *
    * @param btcNetwork Bitcoin network identifiers
    * @param seedFile Path to store seed
    * @param password Password for encryption
    * @return Created key manager
    */
  def createKeyManager(
    btcNetwork: BitcoinNetworkIdentifiers,
    seedFile: String,
    password: String
  ): F[BIP39KeyManager]

  /** Generates a public key at specified index.
    *
    * @param km Key manager to derive from
    * @param currentIdx Derivation index
    * @return Generated public key
    */
  def generateKey(
    km: BIP39KeyManager,
    currentIdx: Int
  ): F[ECPublicKey]

  /** Generates sharable extended public key from key manager.
    *
    * @param km Key manager to derive from
    * @return Extended public key
    */
  def generateSharableKey(km: BIP39KeyManager): ExtPublicKey

  /** Derives child key from shared public key.
    *
    * @param extendedPubKey Parent extended public key
    * @param currentIdx Derivation index
    * @return Derived public key
    */
  def deriveChildFromSharedPublicKey(
    extendedPubKey: ExtPublicKey,
    currentIdx: Int
  ): F[ECPublicKey]

  /** Derives child keys from multiple shared public keys.
    *
    * @param extendedPubKeys List of ID and extended public key pairs
    * @param currentIdx Derivation index
    * @return List of derived ID and public key pairs
    */
  def deriveChildrenFromSharedPublicKeys(
    extendedPubKeys: List[(Int, ExtPublicKey)],
    currentIdx: Int
  ): F[List[(Int, ECPublicKey)]]
}

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

  def generateSharableKey(km: BIP39KeyManager): ExtPublicKey = {
    val hdAccount = HDAccount.fromPath(BIP32Path.fromString("m/84'/1'/0'")).get // TODO: verify that this path is correct
    km.deriveXPub(hdAccount).get
  }

  def deriveChildFromSharedPublicKey[F[_]: Sync](
    extendedPubKey: ExtPublicKey,
    currentIdx:     Int
  ): F[ECPublicKey] =
    for {
      childKey <- Sync[F].delay(
        extendedPubKey
          .deriveChildPubKey(BIP32Path.fromString(s"m/0/$currentIdx"))
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
