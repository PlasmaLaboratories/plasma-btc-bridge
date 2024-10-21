package xyz.stratalab.bridge.stubs

import cats.effect.IO
import xyz.stratalab.sdk.dataApi.{TemplateStorageAlgebra, WalletTemplate}

class BaseTemplateStorageAlgebra extends TemplateStorageAlgebra[IO] {

  override def findTemplates(): IO[Seq[WalletTemplate]] = ???

  override def addTemplate(walletTemplate: WalletTemplate): IO[Int] = ???

}
