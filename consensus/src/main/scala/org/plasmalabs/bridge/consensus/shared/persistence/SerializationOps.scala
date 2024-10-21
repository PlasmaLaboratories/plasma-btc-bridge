package org.plasmalabs.bridge.consensus.shared.persistence

import com.google.protobuf.ByteString
import org.plasmalabs.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BtcFundsDeposited => BtcFundsDepositedEvent,
  BtcFundsWithdrawn => BtcFundsWithdrawnEvent,
  NewBTCBlock => NewBTCBlockEvent,
  NewStrataBlock => NewStrataBlockEvent,
  NodeFundsDeposited => NodeFundsDepositedEvent,
  NodeFundsWithdrawn => NodeFundsWithdrawnEvent,
  SkippedBTCBlock => SkippedBTCBlockEvent,
  SkippedStrataBlock => SkippedStrataBlockEvent
}
import org.plasmalabs.bridge.consensus.protobuf.NodeCurrencyUnit.Currency.{
  AssetToken => AssetTokenCurrency,
  GroupToken => GroupTokenCurrency,
  Lvl => LvlCurrency,
  SeriesToken => SeriesTokenCurrency
}
import org.plasmalabs.bridge.consensus.protobuf.{
  AssetToken => AssetTokenPb,
  BTCFundsDeposited => BTCFundsDepositedPb,
  BTCFundsWithdrawn => BTCFundsWithdrawnPb,
  BlockchainEvent => BlockchainEventPb,
  GroupToken => GroupTokenPb,
  Lvl => LvlPb,
  NewBTCBlock => NewBTCBlockPb,
  NewStrataBlock => NewStrataBlockPb,
  NodeCurrencyUnit => NodeCurrencyUnitPb,
  NodeFundsDeposited => NodeFundsDepositedPb,
  NodeFundsWithdrawn => NodeFundsWithdrawnPb,
  SeriesToken => SeriesTokenPb,
  SkippedBTCBlock => SkippedBTCBlockPb,
  SkippedStrataBlock => SkippedStrataBlockPb
}
import org.plasmalabs.bridge.consensus.shared.{AssetToken, GroupToken, Lvl, NodeCurrencyUnit, SeriesToken}
import org.plasmalabs.bridge.consensus.subsystems.monitor.{
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

trait SerializationOps {

  def toProtobuf(amount: NodeCurrencyUnit) = amount match {
    case Lvl(amount) =>
      Some(
        NodeCurrencyUnitPb(
          LvlCurrency(
            LvlPb(amount.value)
          )
        )
      )
    case SeriesToken(id, amount) =>
      Some(
        NodeCurrencyUnitPb(
          SeriesTokenCurrency(
            SeriesTokenPb(id, amount.value)
          )
        )
      )
    case GroupToken(id, amount) =>
      Some(
        NodeCurrencyUnitPb(
          GroupTokenCurrency(
            GroupTokenPb(id, amount.value)
          )
        )
      )
    case AssetToken(groupId, seriesId, amount) =>
      Some(
        NodeCurrencyUnitPb(
          AssetTokenCurrency(
            AssetTokenPb(groupId, seriesId, amount.value)
          )
        )
      )
  }

  def toProtobuf(event: BlockchainEvent): BlockchainEventPb =
    event match {
      case NewBTCBlock(height) =>
        BlockchainEventPb(
          NewBTCBlockEvent(NewBTCBlockPb(height))
        )
      case BTCFundsWithdrawn(txId, vout) =>
        BlockchainEventPb(
          BtcFundsWithdrawnEvent(BTCFundsWithdrawnPb(txId, vout))
        )
      case SkippedBTCBlock(height) =>
        BlockchainEventPb(
          SkippedBTCBlockEvent(SkippedBTCBlockPb(height))
        )
      case SkippedStrataBlock(height) =>
        BlockchainEventPb(
          SkippedStrataBlockEvent(SkippedStrataBlockPb(height))
        )
      case NewStrataBlock(height) =>
        BlockchainEventPb(
          NewStrataBlockEvent(NewStrataBlockPb(height))
        )
      case BTCFundsDeposited(
            fundsDepositedHeight,
            scriptPubKey,
            txId,
            vout,
            amount
          ) =>
        BlockchainEventPb(
          BtcFundsDepositedEvent(
            BTCFundsDepositedPb(
              fundsDepositedHeight,
              scriptPubKey,
              txId,
              vout,
              ByteString.copyFrom(amount.satoshis.bytes.toArray)
            )
          )
        )
      case NodeFundsDeposited(
            currentStrataBlockHeight,
            address,
            utxoTxId,
            utxoIndex,
            amount
          ) =>
        BlockchainEventPb(
          NodeFundsDepositedEvent(
            NodeFundsDepositedPb(
              currentStrataBlockHeight,
              address,
              utxoTxId,
              utxoIndex,
              toProtobuf(amount)
            )
          )
        )
      case NodeFundsWithdrawn(height, txId, txIndex, secret, amount) =>
        BlockchainEventPb(
          NodeFundsWithdrawnEvent(
            NodeFundsWithdrawnPb(
              height,
              txId,
              txIndex,
              secret,
              toProtobuf(amount)
            )
          )
        )
    }
}
