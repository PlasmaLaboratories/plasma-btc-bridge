package xyz.stratalab.bridge.consensus.shared.persistence

import munit.CatsEffectSuite
import org.bitcoins.core.currency.Satoshis
import xyz.stratalab.bridge.consensus.shared.persistence.{DeserializationOps, SerializationOps}
import xyz.stratalab.bridge.consensus.shared.{AssetToken, GroupToken, Lvl, SeriesToken}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{
  BTCFundsDeposited,
  BTCFundsWithdrawn,
  NewBTCBlock,
  NewStrataBlock,
  NodeFundsDeposited,
  NodeFundsWithdrawn,
  SkippedBTCBlock,
  SkippedStrataBlock
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

  test("Serialization and Deserialization of SkippedStrataBlock") {
    val event = SkippedStrataBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of NewStrataBlock") {
    val event = NewStrataBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of BTCFundsDeposited") {
    val event = BTCFundsDeposited(1, "scriptPubKey", "txId", 1, Satoshis(1))
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of NodeFundsDeposited") {
    import xyz.stratalab.sdk.syntax._
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
    import xyz.stratalab.sdk.syntax._
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
