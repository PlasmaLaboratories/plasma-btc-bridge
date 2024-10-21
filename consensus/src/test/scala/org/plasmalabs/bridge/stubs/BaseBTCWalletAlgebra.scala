package xyz.stratalab.bridge.stubs

import cats.effect.IO
import org.bitcoins.crypto.{ECDigitalSignature, ECPublicKey}
import scodec.bits.ByteVector
import xyz.stratalab.bridge.consensus.core.managers.BTCWalletAlgebra

class BaseBTCWalletAlgebra extends BTCWalletAlgebra[IO] {

  override def getCurrentPubKeyAndPrepareNext(): IO[(Int, ECPublicKey)] = ???

  override def getCurrentPubKey(): IO[ECPublicKey] = ???

  override def signForIdx(
    idx:     Int,
    txBytes: ByteVector
  ): IO[ECDigitalSignature] = ???

}
