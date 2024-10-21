package xyz.stratalab.bridge.stubs

import cats.effect.IO
import xyz.stratalab.indexer.services.{Txo, TxoState}
import xyz.stratalab.sdk.dataApi.IndexerQueryAlgebra
import xyz.stratalab.sdk.models.LockAddress

class BaseIndexerQueryAlgebra extends IndexerQueryAlgebra[IO] {

  override def queryUtxo(
    fromAddress: LockAddress,
    txoState:    TxoState
  ): IO[Seq[Txo]] = ???

}
