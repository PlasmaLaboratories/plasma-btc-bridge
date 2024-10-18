package org.plasmalabs.bridge.stubs

import cats.effect.IO
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.models.LockAddress
import co.topl.genus.services.{Txo, TxoState}

class BaseGenusQueryAlgebra extends GenusQueryAlgebra[IO] {

  override def queryUtxo(
    fromAddress: LockAddress,
    txoState:    TxoState
  ): IO[Seq[Txo]] = ???

}
