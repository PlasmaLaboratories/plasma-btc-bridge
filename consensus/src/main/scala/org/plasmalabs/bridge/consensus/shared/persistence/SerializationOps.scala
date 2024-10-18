package org.plasmalabs.bridge.consensus.shared.persistence

import com.google.protobuf.ByteString
import org.plasmalabs.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  AssetToken => AssetTokenCurrency,
  GroupToken => GroupTokenCurrency,
  Lvl => LvlCurrency,
  SeriesToken => SeriesTokenCurrency
}
import org.plasmalabs.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BifrostFundsDeposited => BifrostFundsDepositedEvent,
  BifrostFundsWithdrawn => BifrostFundsWithdrawnEvent,
  BtcFundsDeposited => BtcFundsDepositedEvent,
  BtcFundsWithdrawn => BtcFundsWithdrawnEvent,
  NewBTCBlock => NewBTCBlockEvent,
  NewStrataBlock => NewStrataBlockEvent,
  SkippedBTCBlock => SkippedBTCBlockEvent,
  SkippedStrataBlock => SkippedStrataBlockEvent
}
import org.plasmalabs.bridge.consensus.protobuf.{
  AssetToken => AssetTokenPb,
  BTCFundsDeposited => BTCFundsDepositedPb,
  BTCFundsWithdrawn => BTCFundsWithdrawnPb,
  BifrostCurrencyUnit => BifrostCurrencyUnitPb,
  BifrostFundsDeposited => BifrostFundsDepositedPb,
  BifrostFundsWithdrawn => BifrostFundsWithdrawnPb,
  BlockchainEvent => BlockchainEventPb,
  GroupToken => GroupTokenPb,
  Lvl => LvlPb,
  NewBTCBlock => NewBTCBlockPb,
  NewStrataBlock => NewStrataBlockPb,
  SeriesToken => SeriesTokenPb,
  SkippedBTCBlock => SkippedBTCBlockPb,
  SkippedStrataBlock => SkippedStrataBlockPb
}
import org.plasmalabs.bridge.consensus.shared.{AssetToken, BifrostCurrencyUnit, GroupToken, Lvl, SeriesToken}
import org.plasmalabs.bridge.consensus.subsystems.monitor.{
  BTCFundsDeposited,
  BTCFundsWithdrawn,
  BifrostFundsDeposited,
  BifrostFundsWithdrawn,
  BlockchainEvent,
  NewBTCBlock,
  NewStrataBlock,
  SkippedBTCBlock,
  SkippedStrataBlock
}

trait SerializationOps {

  def toProtobuf(amount: BifrostCurrencyUnit) = amount match {
    case Lvl(amount) =>
      Some(
        BifrostCurrencyUnitPb(
          LvlCurrency(
            LvlPb(amount.value)
          )
        )
      )
    case SeriesToken(id, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          SeriesTokenCurrency(
            SeriesTokenPb(id, amount.value)
          )
        )
      )
    case GroupToken(id, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          GroupTokenCurrency(
            GroupTokenPb(id, amount.value)
          )
        )
      )
    case AssetToken(groupId, seriesId, amount) =>
      Some(
        BifrostCurrencyUnitPb(
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
      case BifrostFundsDeposited(
            currentStrataBlockHeight,
            address,
            utxoTxId,
            utxoIndex,
            amount
          ) =>
        BlockchainEventPb(
          BifrostFundsDepositedEvent(
            BifrostFundsDepositedPb(
              currentStrataBlockHeight,
              address,
              utxoTxId,
              utxoIndex,
              toProtobuf(amount)
            )
          )
        )
      case BifrostFundsWithdrawn(height, txId, txIndex, secret, amount) =>
        BlockchainEventPb(
          BifrostFundsWithdrawnEvent(
            BifrostFundsWithdrawnPb(
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
