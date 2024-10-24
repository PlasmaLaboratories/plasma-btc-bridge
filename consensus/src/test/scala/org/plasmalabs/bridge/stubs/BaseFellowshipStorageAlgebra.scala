package org.plasmalabs.bridge.stubs

import cats.effect.IO
import org.plasmalabs.sdk.dataApi.{FellowshipStorageAlgebra, WalletFellowship}

class BaseFellowshipStorageAlgebra extends FellowshipStorageAlgebra[IO] {

  override def findFellowships(): IO[Seq[WalletFellowship]] = ???

  override def addFellowship(walletEntity: WalletFellowship): IO[Int] = ???

}
