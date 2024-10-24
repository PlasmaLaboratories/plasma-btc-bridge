package org.plasmalabs.bridge.stubs

import cats.effect.IO
import org.bitcoins.crypto.{ECDigitalSignature, ECPublicKey}
import org.plasmalabs.bridge.consensus.core.managers.BTCWalletAlgebra
import scodec.bits.ByteVector

class BaseBTCWalletAlgebra extends BTCWalletAlgebra[IO] {

  override def getCurrentPubKeyAndPrepareNext(): IO[(Int, ECPublicKey)] = ???

  override def getCurrentPubKey(): IO[ECPublicKey] = ???

  override def signForIdx(
    idx:     Int,
    txBytes: ByteVector
  ): IO[ECDigitalSignature] = ???

}
