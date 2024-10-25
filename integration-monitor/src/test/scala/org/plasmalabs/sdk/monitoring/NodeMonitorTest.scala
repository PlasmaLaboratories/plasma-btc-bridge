package org.plasmalabs.sdk.monitoring

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.toTraverseOps
import org.plasmalabs.sdk.builders.TransactionBuilderApi
import org.plasmalabs.sdk.builders.locks.LockTemplate.PredicateTemplate
import org.plasmalabs.sdk.builders.locks.PropositionTemplate.HeightTemplate
import org.plasmalabs.sdk.common.ContainsSignable.ContainsSignableTOps
import org.plasmalabs.sdk.common.ContainsSignable.instances.ioTransactionSignable
import org.plasmalabs.sdk.constants.NetworkConstants.{MAIN_LEDGER_ID, PRIVATE_NETWORK_ID}
import org.plasmalabs.sdk.dataApi.{IndexerQueryAlgebra, NodeQueryAlgebra, RpcChannelResource}
import org.plasmalabs.sdk.models.box.Attestation
import org.plasmalabs.bridge.consensus.subsystems.monitor.NodeMonitor.{AppliedNodeBlock, UnappliedNodeBlock}
import org.plasmalabs.sdk.syntax.{ioTransactionAsTransactionSyntaxOps, LvlType}
import io.grpc.ManagedChannel

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import org.plasmalabs.quivr.api.Prover
import org.plasmalabs.bridge.consensus.subsystems.monitor.utils.{connectNodeNodes, disconnectNodeNodes}

class NodeMonitorTest extends munit.CatsEffectSuite {

  override val munitTimeout: FiniteDuration = Duration(250, "s")

  val channelResource1: Resource[IO, ManagedChannel] =
    RpcChannelResource.channelResource[IO]("localhost", 9184, secureConnection = false)

  val channelResource2: Resource[IO, ManagedChannel] =
    RpcChannelResource.channelResource[IO]("localhost", 9086, secureConnection = false)
  val NodeQuery1: NodeQueryAlgebra[IO] = NodeQueryAlgebra.make[IO](channelResource1)
  val NodeQuery2: NodeQueryAlgebra[IO] = NodeQueryAlgebra.make[IO](channelResource2)

  test("Monitor blocks with a reorg") {
    assertIO(
      NodeMonitor("localhost", 9184, secureConnection = false, NodeQuery1).use { blockStream =>
        blockStream.interruptAfter(80.seconds).compile.toList <& (for {
          // ensure the 2 nodes are in sync prior to starting
          _        <- IO.println("connecting the nodes")
          _        <- connectNodeNodes("Node02", "Node01").start.andWait(20.seconds)
          _        <- IO.println("after connect")
          node1Tip <- NodeQuery1.blockByDepth(0)
          _        <- IO.println(node1Tip)
          node2Tip <- NodeQuery2.blockByDepth(0)
          _        <- IO.println(node2Tip)
          // The 2 nodes start disconnected
          _ <- IO.println("disconnecting the nodes")
          _ <- disconnectNodeNodes("Node02").start.andWait(20.seconds)
          _ <- IO.println("starting monitor")

          _ <- IO.println("making blocks: node 1")
          _ <- NodeQuery1.makeBlocks(1)
          _ <- IO.println("making blocks: node 2")
          _ <- NodeQuery2.makeBlocks(2)

          _ <- IO.println("waiting after making blocks").andWait(15.seconds)

          // connect blocks. the monitor should unapply the 1 block, and then apply the 2 new blocks
          _ <- IO.println("connecting the nodes")
          _ <- connectNodeNodes("Node02", "Node01").start.andWait(20.seconds)
        } yield ()) map { blocks =>
          println(s"blocks:  $blocks") // applied, unapplied, applied, applied
          blocks.length == 4 &&
          blocks.head.isInstanceOf[AppliedNodeBlock] &&
          blocks(1).isInstanceOf[UnappliedNodeBlock] &&
          blocks(2).isInstanceOf[AppliedNodeBlock] &&
          blocks(3).isInstanceOf[AppliedNodeBlock]
        }
      },
      true
    )
  }

  test("Monitor only new blocks (empty)") {
    assertIO(
      NodeMonitor("localhost", 9184, secureConnection = false, NodeQuery1).use { blockStream =>
        (blockStream.interruptAfter(15.seconds).compile.toList <& NodeQuery1.makeBlocks(2)) map { blocks =>
          println(blocks)
          blocks.count(_.isInstanceOf[AppliedNodeBlock]) == 2
        }
      },
      true
    )
  }

  test("Monitor only new blocks (trivial transaction)") {
    val heightLockTemplate = PredicateTemplate(Seq(HeightTemplate[IO]("header", 1, Long.MaxValue)), 1)
    val IndexerQueryApi = IndexerQueryAlgebra.make[IO](channelResource1)
    val txBuilder = TransactionBuilderApi.make[IO](PRIVATE_NETWORK_ID, MAIN_LEDGER_ID)
    assertIO(
      NodeMonitor("localhost", 9184, secureConnection = false, NodeQuery1).use { blockStream =>
        (for {
          blockUpdates <- blockStream
            .through(_.map(b => (b.block.transactions.map(_.computeId), b.height)))
            .interruptAfter(20.seconds)
            .compile
            .toList
          // Ensure the reported height is correct
          queriedHeights <- blockUpdates
            .map(b => NodeQuery1.blockByHeight(b._2).map(r => (r.get._4.map(_.computeId), r.get._2.height)))
            .sequence
        } yield (blockUpdates, queriedHeights)) both (for {
          heightLock    <- heightLockTemplate.build(Nil).map(_.toOption.get)
          heightAddress <- txBuilder.lockAddress(heightLock)
          txos          <- IndexerQueryApi.queryUtxo(heightAddress)
          tx <- txBuilder
            .buildTransferAmountTransaction(
              LvlType,
              txos,
              heightLock.getPredicate,
              100L,
              heightAddress, // Trivial, resend to genesis address
              heightAddress,
              1L
            )
            .map(_.toOption.get)
          proof <- Prover.heightProver[IO].prove((), tx.signable)
          provedTx = tx.withInputs(
            tx.inputs.map(in =>
              in.withAttestation(Attestation().withPredicate(in.attestation.getPredicate.withResponses(Seq(proof))))
            )
          )
          // Then broadcast transaction
          txId <- NodeQuery1.broadcastTransaction(provedTx).andWait(5.seconds)
          _    <- NodeQuery1.makeBlocks(1).andWait(5.seconds)
        } yield txId) map { res =>
          val ((blockUpdates, queriedHeights), txId) = res
          val foundUpdate = blockUpdates.find(_._1.contains(txId))
          foundUpdate.isDefined && queriedHeights.exists(_._2 == foundUpdate.get._2) &&
          (foundUpdate.get._1 equals queriedHeights.find(_._2 == foundUpdate.get._2).get._1)
        }
      },
      true
    )
  }

  test("Monitor live and retroactive blocks") {
    val startingBlockId = NodeQuery1.blockByDepth(0).map(_.get._1).unsafeRunSync()
    assertIO(
      NodeMonitor("localhost", 9184, secureConnection = false, NodeQuery1, Some(startingBlockId)).use { blockStream =>
        blockStream.interruptAfter(20.seconds).compile.toList both (for {
          retroactiveHeight <- NodeQuery1.blockById(startingBlockId).map(_.get._2.height)
          _                 <- NodeQuery1.makeBlocks(2)
        } yield retroactiveHeight) map { res =>
          val (blocks, startingHeight) = res
          println(startingHeight)
          println(s"blocks: $blocks")
          val expectedHeights = Seq.range(startingHeight, startingHeight + blocks.length).toList
          val tests = Seq(
            blocks.headOption.map(_.id).contains(startingBlockId), // retroactive block is reported
            blocks.tail.length == 2, // ensure live blocks are reported
            (blocks.map(_.height) equals expectedHeights), // ensure heights are correct
            blocks.map(_.id).distinct.length == blocks.length // ensure no duplicate reporting
          )
          println(tests)
          tests.forall(_ == true)
        }
      },
      true
    )
  }

}
