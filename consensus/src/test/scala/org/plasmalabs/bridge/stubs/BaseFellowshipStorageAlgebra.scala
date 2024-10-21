package xyz.stratalab.bridge.stubs

import cats.effect.IO
import xyz.stratalab.sdk.dataApi.{FellowshipStorageAlgebra, WalletFellowship}

class BaseFellowshipStorageAlgebra extends FellowshipStorageAlgebra[IO] {

  override def findFellowships(): IO[Seq[WalletFellowship]] = ???

  override def addFellowship(walletEntity: WalletFellowship): IO[Int] = ???

}
