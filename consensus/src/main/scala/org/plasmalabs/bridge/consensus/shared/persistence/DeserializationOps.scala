package xyz.stratalab.bridge.consensus.shared.persistence

import org.bitcoins.core.currency.Satoshis
import quivr.models.Int128
import scodec.bits.ByteVector
import xyz.stratalab.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BtcFundsDeposited => BtcFundsDepositedEvent,
  BtcFundsWithdrawn,
  Empty,
  NewBTCBlock => NewBTCBlockEvent,
  NewStrataBlock => NewStrataBlockEvent,
  NodeFundsDeposited => NodeFundsDepositedEvent,
  NodeFundsWithdrawn => NodeFundsWithdrawnEvent,
  SkippedBTCBlock => SkippedBTCBlockEvent,
  SkippedStrataBlock => SkippedStrataBlockEvent
}
import xyz.stratalab.bridge.consensus.protobuf.NodeCurrencyUnit.Currency.{
  AssetToken => AssetTokenCurrency,
  GroupToken => GroupTokenCurrency,
  Lvl => LvlCurrency,
  SeriesToken => SeriesTokenCurrency
}
import xyz.stratalab.bridge.consensus.protobuf.{
  BlockchainEvent => BlockchainEventPb,
  NodeCurrencyUnit => NodeCurrencyUnitPb
}
import xyz.stratalab.bridge.consensus.shared.{AssetToken, GroupToken, Lvl, SeriesToken}
import xyz.stratalab.bridge.consensus.subsystems.monitor.{
  BTCFundsDeposited,
  BTCFundsWithdrawn,
  BlockchainEvent,
  NewBTCBlock,
  NewStrataBlock,
  NodeFundsDeposited,
  NodeFundsWithdrawn,
  SkippedBTCBlock,
  SkippedStrataBlock
}

trait DeserializationOps {

  def fromProtobuf(someAmount: Option[NodeCurrencyUnitPb]) =
    someAmount match {
      case Some(amount) =>
        amount.currency match {
          case LvlCurrency(value) =>
            Lvl(Int128(value.amount))
          case SeriesTokenCurrency(value) =>
            SeriesToken(
              value.id,
              Int128(value.amount)
            )
          case GroupTokenCurrency(value) =>
            GroupToken(
              value.id,
              Int128(value.amount)
            )
          case AssetTokenCurrency(value) =>
            AssetToken(
              value.groupId,
              value.seriesId,
              Int128(value.amount)
            )
          case _ => throw new IllegalStateException("Unknown currency type")
        }
      case None => throw new IllegalStateException("Amount is missing")
    }

  def fromProtobuf(blockchainEventPb: BlockchainEventPb): BlockchainEvent =
    blockchainEventPb.event match {
      case BtcFundsDepositedEvent(value) =>
        BTCFundsDeposited(
          value.fundsDepositedHeight,
          value.address,
          value.txId,
          value.vout,
          Satoshis.fromBytes(ByteVector(value.amount.toByteArray()))
        )
      case SkippedBTCBlockEvent(value) =>
        SkippedBTCBlock(value.height)
      case NodeFundsDepositedEvent(value) =>
        NodeFundsDeposited(
          value.currentStrataBlockHeight,
          value.address,
          value.utxoTxId,
          value.utxoIndex,
          fromProtobuf(value.amount)
        )
      case NewStrataBlockEvent(value) =>
        NewStrataBlock(value.height)
      case SkippedStrataBlockEvent(value) =>
        SkippedStrataBlock(value.height)
      case NodeFundsWithdrawnEvent(value) =>
        NodeFundsWithdrawn(
          value.currentStrataBlockHeight,
          value.txId,
          value.txIndex,
          value.secret,
          fromProtobuf(value.amount)
        )
      case NewBTCBlockEvent(value) =>
        NewBTCBlock(value.height)
      case Empty =>
        throw new IllegalStateException("Empty event")
      case BtcFundsWithdrawn(value) =>
        BTCFundsWithdrawn(value.txId, value.vout)
    }
}
