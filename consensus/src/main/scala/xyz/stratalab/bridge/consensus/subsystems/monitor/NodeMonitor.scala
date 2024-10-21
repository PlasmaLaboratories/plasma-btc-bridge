package xyz.stratalab.bridge.consensus.subsystems.monitor

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.{catsSyntaxParallelSequence1, toTraverseOps}
import fs2.Stream
import io.grpc.Metadata
import xyz.stratalab.bridge.consensus.subsystems.monitor.NodeMonitor.{AppliedNodeBlock, UnappliedNodeBlock}
import xyz.stratalab.consensus.models.{BlockHeader, BlockId}
import xyz.stratalab.node.models.FullBlockBody
import xyz.stratalab.node.services.SynchronizationTraversalRes.Status.{Applied, Empty, Unapplied}
import xyz.stratalab.node.services.{NodeRpcFs2Grpc, SynchronizationTraversalReq, SynchronizationTraversalRes}
import xyz.stratalab.sdk.dataApi.NodeQueryAlgebra
import xyz.stratalab.sdk.dataApi.RpcChannelResource.channelResource
import xyz.stratalab.sdk.display.blockIdDisplay.display
import xyz.stratalab.sdk.models.transaction.IoTransaction

/**
 * Class to monitor incoming node blocks via an iterator.
 * @param blockStream The stream in which block changes are reported through
 * @param startingBlocks Past blocks that should be reported.
 */
class NodeMonitor(
  blockStream:    Stream[IO, SynchronizationTraversalRes],
  getFullBlock:   BlockId => IO[(BlockHeader, FullBlockBody)],
  startingBlocks: Vector[NodeBlockSync]
) {

  def pipe(in: Stream[IO, SynchronizationTraversalRes]): Stream[IO, NodeBlockSync] = in.evalMapFilter(sync =>
    sync.status match {
      case Applied(blockId) =>
        getFullBlock(blockId).map(block => Some(AppliedNodeBlock(block._2, blockId, block._1.height)))
      case Unapplied(blockId) =>
        getFullBlock(blockId).map(block => Some(UnappliedNodeBlock(block._2, blockId, block._1.height)))
      case Empty => IO.pure(None)
    }
  )

  /**
   * Return a stream of block updates.
   * @return The infinite stream of block updatess. If startingBlocks was provided, they will be at the front of the stream.
   */
  def monitorBlocks(): Stream[IO, NodeBlockSync] = Stream.emits(startingBlocks) ++ blockStream.through(pipe)

}

/**
 * A wrapper for a Node Block Sync update.
 */
trait NodeBlockSync {
  // The node Block being wrapped. This represents either an Applied block or an Unapplied block
  val block: FullBlockBody
  val id: BlockId
  val height: Long
  def transactions[F[_]]: Stream[F, IoTransaction] = Stream.emits(block.transactions)
}

object NodeMonitor {

  // Represents a new block applied to the chain tip
  case class AppliedNodeBlock(block: FullBlockBody, id: BlockId, height: Long) extends NodeBlockSync
  // Represents an existing block that has been unapplied from the chain tip
  case class UnappliedNodeBlock(block: FullBlockBody, id: BlockId, height: Long) extends NodeBlockSync

  /**
   * Initialize and return a NodeMonitor instance.
   * @param nodeQuery The node query api to retrieve updates from
   * @param startBlock The blockId of a past block. Used to retroactively report blocks. The node monitor will report all blocks starting at this block.
   * @return An instance of a NodeMonitor
   */
  def apply(
    address:          String,
    port:             Int,
    secureConnection: Boolean,
    nodeQuery:        NodeQueryAlgebra[IO],
    startBlock:       Option[BlockId] = None
  ): Resource[IO, Stream[IO, NodeBlockSync]] = {
    def getFullBlock(blockId: BlockId): IO[(BlockHeader, FullBlockBody)] = for {
      block <- nodeQuery.blockById(blockId)
    } yield block match {
      case None                      => throw new Exception(s"Unable to query block ${display(blockId)}")
      case Some((_, header, _, txs)) => (header, FullBlockBody(txs))
    }
    def getBlockIds(startHeight: Option[Long], tipHeight: Option[Long]): IO[Vector[BlockId]] =
      (startHeight, tipHeight) match {
        case (Some(start), Some(tip)) if (start >= 1 && tip >= start) =>
          (for (curHeight <- start to tip)
            yield
            // For all blocks from starting Height to current tip height, fetch blockIds
            nodeQuery.blockByHeight(curHeight).map(_.map(_._1)).map(_.toList)).toVector.parSequence.map(_.flatten)
        case _ => IO.pure(Vector.empty)
      }
    for {
      // The height of the startBlock
      startBlockHeight <- startBlock
        .map(bId => nodeQuery.blockById(bId))
        .sequence
        .map(_.flatten.map(_._2.height))
        .toResource
      // the height of the chain tip
      tipHeight        <- nodeQuery.blockByDepth(0).map(_.map(_._2.height)).toResource
      startingBlockIds <- getBlockIds(startBlockHeight, tipHeight).toResource
      startingBlocks <- startingBlockIds
        .map(bId => getFullBlock(bId).map(block => AppliedNodeBlock(block._2, bId, block._1.height)))
        .sequence
        .toResource
      channel <- channelResource[IO](address, port, secureConnection)
      stub    <- NodeRpcFs2Grpc.stubResource[IO](channel)
      stream <- IO(stub.synchronizationTraversal(SynchronizationTraversalReq(), new Metadata()).handleErrorWith { e =>
        e.printStackTrace()
        println("Error in NodeMonitor")
        Stream.empty[IO]
      }).toResource
    } yield new NodeMonitor(stream, getFullBlock, startingBlocks).monitorBlocks()
  }

}
