package org.plasmalabs.bridge.consensus.subsystems.monitor

import org.plasmalabs.sdk.codecs.AddressCodecs
import org.plasmalabs.sdk.models.box.Attestation
import org.plasmalabs.sdk.monitoring.BitcoinMonitor.BitcoinBlockSync
import org.plasmalabs.sdk.monitoring.NodeMonitor
import org.plasmalabs.sdk.utils.Encoding

import scala.util.Try

object BlockProcessor {

  private def extractFromStrataTx(proof: Attestation): String = {
    // The following is possible because we know the exact structure of the attestation
    val attestation = proof.getPredicate
    val preimage = attestation.responses.head.getAnd.left.getDigest.preimage
    new String(
      preimage.input.toByteArray
    )
  }

  def process[F[_]](
    initialBTCHeight:    Int,
    initialStrataHeight: Long
  ): Either[BitcoinBlockSync, NodeMonitor.NodeBlockSync] => fs2.Stream[
    F,
    BlockchainEvent
  ] = {
    var btcHeight = initialBTCHeight
    var toplHeight =
      initialStrataHeight
    var btcAscending = false
    var toplAscending = false
    def processAux[F[_]](
      block: Either[BitcoinBlockSync, NodeMonitor.NodeBlockSync]
    ): fs2.Stream[F, BlockchainEvent] = block match {
      case Left(b) =>
        val allTransactions = fs2.Stream(
          b.block.transactions.flatMap(transaction =>
            transaction.inputs.map(input =>
              BTCFundsWithdrawn(
                input.previousOutput.txIdBE.hex,
                input.previousOutput.vout.toLong
              )
            )
          ) ++ b.block.transactions.flatMap(transaction =>
            transaction.outputs.zipWithIndex.map { outputAndVout =>
              val (output, vout) = outputAndVout
              BTCFundsDeposited(
                b.height,
                output.scriptPubKey.asmHex,
                transaction.txIdBE.hex,
                vout,
                output.value
              )
            }
          ): _*
        )
        if (btcHeight == 0)
          btcHeight = b.height - 1
        val transactions =
          if (b.height == (btcHeight + 1)) { // going up as expected, include all transaction
            btcAscending = true
            fs2.Stream(NewBTCBlock(b.height)) ++ allTransactions
          } else if (b.height == (btcHeight - 1)) { // going down by one, we ommit transactions
            btcAscending = false
            fs2.Stream(NewBTCBlock(b.height))
          } else if (b.height > (btcHeight + 1)) { // we went up by more than one
            btcAscending = true
            fs2.Stream(
              SkippedBTCBlock(b.height)
            )
          } else if (b.height < (btcHeight - 1)) { // we went down by more than one, we ommit transactions
            btcAscending = false
            fs2.Stream(NewBTCBlock(b.height))
          } else {
            // we stayed the same
            if (btcAscending) {
              // if we are ascending, it means the current block was just unapplied
              // we don't pass the transactions that we have already seen
              btcAscending = false
              fs2.Stream(NewBTCBlock(b.height))
            } else {
              // if we are descending, it means the current block was just applied
              // we need to pass all transactions
              btcAscending = true
              fs2.Stream(NewBTCBlock(b.height)) ++ allTransactions
            }
          }
        btcHeight = b.height
        transactions
      case Right(b) =>
        val allTransactions = fs2.Stream(
          b.block.transactions.flatMap(transaction =>
            transaction.inputs
              .filter(x => isLvlSeriesGroupOrAsset(x.value.value))
              .map { input =>
                NodeFundsWithdrawn(
                  b.height,
                  Encoding.encodeToBase58(input.address.id.value.toByteArray()),
                  input.address.index,
                  Try(extractFromStrataTx(input.attestation))
                    .getOrElse(""), // TODO: Make this safer
                  toCurrencyUnit(input.value.value)
                )
              }
          ) ++ b.block.transactions.flatMap(transaction =>
            transaction.outputs.zipWithIndex.map { outputAndIdx =>
              val (output, idx) = outputAndIdx
              val nodeCurrencyUnit = toCurrencyUnit(output.value.value)
              NodeFundsDeposited(
                b.height,
                AddressCodecs.encodeAddress(output.address),
                Encoding.encodeToBase58(
                  transaction.transactionId.get.value.toByteArray()
                ),
                idx,
                nodeCurrencyUnit
              )
            }
          ): _*
        )
        if (toplHeight == 0)
          toplHeight = b.height - 1
        val transactions =
          if (b.height == (toplHeight + 1)) { // going up as expected, include all transaction
            toplAscending = true
            fs2.Stream(NewStrataBlock(b.height)) ++ allTransactions
          } else if (b.height == (toplHeight - 1)) { // going down by one, we ommit transactions
            toplAscending = false
            fs2.Stream(NewStrataBlock(b.height))
          } else if (b.height > (toplHeight + 1)) { // we went up by more than one
            toplAscending = true
            fs2.Stream(
              SkippedStrataBlock(b.height)
            )
          } else if (b.height < (toplHeight - 1)) { // we went down by more than one, we ommit transactions
            toplAscending = false
            fs2.Stream()
          } else {
            // we stayed the same
            if (toplAscending) {
              // if we are ascending, it means the current block was just unapplied
              // we don't pass the transactions that we have already seen
              toplAscending = false
              fs2.Stream(NewStrataBlock(b.height))
            } else {
              // if we are descending, it means the current block was just applied
              // we need to pass all transactions
              toplAscending = true
              fs2.Stream(NewStrataBlock(b.height)) ++ allTransactions
            }
          }
        toplHeight = b.height
        transactions
    }
    processAux

  }

}
