package org.plasmalabs.bridge.consensus.shared.persistence

import munit.CatsEffectSuite
import org.bitcoins.core.currency.Satoshis
import org.plasmalabs.bridge.consensus.shared.persistence.{DeserializationOps, SerializationOps}
import org.plasmalabs.bridge.consensus.shared.{AssetToken, GroupToken, Lvl, SeriesToken}
import org.plasmalabs.bridge.consensus.subsystems.monitor.{
  BTCFundsDeposited,
  BTCFundsWithdrawn,
  NewBTCBlock,
  NewPlasmaBlock,
  NodeFundsDeposited,
  NodeFundsWithdrawn,
  SkippedBTCBlock,
  SkippedPlasmaBlock
}

class SerializationDeserializationSpec extends CatsEffectSuite with SerializationOps with DeserializationOps {

  test("Serialization and Deserialization of BTCFundsWithdrawn") {
    val event = BTCFundsWithdrawn("txId", 1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of NewBTCBlock") {
    val event = NewBTCBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of SkippedBTCBlock") {
    val event = SkippedBTCBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of SkippedPlasmaBlock") {
    val event = SkippedPlasmaBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of NewPlasmaBlock") {
    val event = NewPlasmaBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of BTCFundsDeposited") {
    val event = BTCFundsDeposited(1, "scriptPubKey", "txId", 1, Satoshis(1))
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of NodeFundsDeposited") {
    import org.plasmalabs.sdk.syntax._
    val eventLvl = NodeFundsDeposited(1, "address", "utxoTxId", 1, Lvl(1L))
    assertEquals(fromProtobuf(toProtobuf(eventLvl)), eventLvl)
    val eventSeriesToken =
      NodeFundsDeposited(1, "address", "utxoTxId", 1, SeriesToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventSeriesToken)), eventSeriesToken)
    val eventGroupToken =
      NodeFundsDeposited(1, "address", "utxoTxId", 1, GroupToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventGroupToken)), eventGroupToken)
    val eventAssetToken = NodeFundsDeposited(
      1,
      "address",
      "utxoTxId",
      1,
      AssetToken("groupId", "seriesId", 1L)
    )
    assertEquals(fromProtobuf(toProtobuf(eventAssetToken)), eventAssetToken)
  }

  test("Serialization and Deserialization of NodeFundsWithdrawn") {
    import org.plasmalabs.sdk.syntax._
    val eventLvl = NodeFundsWithdrawn(1L, "txId", 1, "secret", Lvl(1))
    assertEquals(fromProtobuf(toProtobuf(eventLvl)), eventLvl)
    val eventSeriesToken =
      NodeFundsWithdrawn(1L, "txId", 1, "secret", SeriesToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventSeriesToken)), eventSeriesToken)
    val eventGroupToken =
      NodeFundsWithdrawn(1L, "txId", 1, "secret", GroupToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventGroupToken)), eventGroupToken)
    val eventAssetToken = NodeFundsWithdrawn(
      1L,
      "txId",
      1,
      "secret",
      AssetToken("groupId", "seriesId", 1L)
    )
    assertEquals(fromProtobuf(toProtobuf(eventAssetToken)), eventAssetToken)
  }

}
