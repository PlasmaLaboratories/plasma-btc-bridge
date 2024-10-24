package org.plasmalabs.bridge.shared

import cats.effect.kernel.Sync
import org.bouncycastle.openssl.PEMParser

import java.io.FileReader
import java.security.PrivateKey
import java.security.Signature
import cats.effect.kernel.Resource
import java.security.KeyPair
import java.security.PublicKey
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo

object BridgeCryptoUtils {

  import cats.implicits._

  private def pemParser[F[_]: Sync](filePath: String): Resource[F, PEMParser] =
    Resource.make(Sync[F].delay(new PEMParser(new FileReader(filePath)))) { pemReader =>
      Sync[F].delay(pemReader.close())
    }

  def getKeyPair[F[_]: Sync](filePath: String): Resource[F, KeyPair] =
    pemParser(filePath)
      .map { pemReader =>
        val pemObject = pemReader.readObject()
        val converter = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter()

        val keyPair =
          converter.getKeyPair(
            pemObject.asInstanceOf[org.bouncycastle.openssl.PEMKeyPair]
          )
        keyPair
      }

  // read public key from file
  def getPublicKey[F[_]: Sync](filePath: String): Resource[F, PublicKey] =
    pemParser(filePath).map { pemReader =>
      val pemObject = pemReader.readObject()
      val converter = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter()
      val publicKey = converter.getPublicKey(
        pemObject.asInstanceOf[SubjectPublicKeyInfo]
      )
      publicKey
    }

  def signBytes[F[_]: Sync](
    privateKey: PrivateKey,
    bytes:      Array[Byte]
  ): F[Array[Byte]] = {
    val signature = Signature.getInstance("SHA256withECDSA", "BC")
    for {
      _           <- Sync[F].delay(signature.initSign(privateKey))
      _           <- Sync[F].delay(signature.update(bytes))
      signedBytes <- Sync[F].delay(signature.sign())
    } yield signedBytes
  }

  // verify bytes
  def verifyBytes[F[_]: Sync](
    publicKey: PublicKey,
    bytes:     Array[Byte],
    signature: Array[Byte]
  ): F[Boolean] = {
    val sig = Signature.getInstance("SHA256withECDSA", "BC")
    for {
      _ <- Sync[F].delay(sig.initVerify(publicKey))
      _ <- Sync[F].delay(sig.update(bytes))
      verified <- Sync[F]
        .delay(sig.verify(signature))
        .handleErrorWith(_ => Sync[F].pure(false))
    } yield verified
  }
}
