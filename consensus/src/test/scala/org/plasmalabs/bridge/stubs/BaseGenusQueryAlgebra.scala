package org.plasmalabs.bridge.stubs

import cats.effect.IO
import org.plasmalabs.sdk.dataApi.IndexerQueryAlgebra
import org.plasmalabs.sdk.models.LockAddress
import org.plasmalabs.indexer.services.{Txo, TxoState}

class BaseIndexerQueryAlgebra extends IndexerQueryAlgebra[IO] {

  override def queryUtxo(
    fromAddress: LockAddress,
    txoState:    TxoState
  ): IO[Seq[Txo]] = ???

}
