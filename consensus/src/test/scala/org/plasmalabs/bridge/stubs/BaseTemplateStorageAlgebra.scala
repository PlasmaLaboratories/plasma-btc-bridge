package org.plasmalabs.bridge.stubs

import cats.effect.IO
import co.topl.brambl.dataApi.{TemplateStorageAlgebra, WalletTemplate}

class BaseTemplateStorageAlgebra extends TemplateStorageAlgebra[IO] {

  override def findTemplates(): IO[Seq[WalletTemplate]] = ???

  override def addTemplate(walletTemplate: WalletTemplate): IO[Int] = ???

}
