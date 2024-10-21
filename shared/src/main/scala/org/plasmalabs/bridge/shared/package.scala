package xyz.stratalab.bridge

import xyz.stratalab.bridge.consensus.service.StateMachineReply
import xyz.stratalab.bridge.consensus.pbft.PrePrepareRequest
import xyz.stratalab.bridge.consensus.pbft.PrepareRequest
import xyz.stratalab.bridge.consensus.pbft.CommitRequest
import xyz.stratalab.bridge.consensus.pbft.CheckpointRequest
import xyz.stratalab.bridge.consensus.pbft.ViewChangeRequest
import xyz.stratalab.bridge.consensus.pbft.NewViewRequest

package object shared {

  case class ReplicaId(
    id: Int
  )

  case class SessionId(
    id: String
  )

  case class ClientId(
    id: Int
  )

  case class ConsensusClientMessageId(
    timestamp: Long
  )

  class ReplicaCount(val value: Int) extends AnyVal {

    def maxFailures = (value - 1) / 3
  }

  class ClientCount(val value: Int) extends AnyVal

  case class ReplicaNode[F[_]](
    id:            Int,
    backendHost:   String,
    backendPort:   Int,
    backendSecure: Boolean
  )

  object implicits {

    implicit class ViewChangeRequestOp(val request: ViewChangeRequest) {

      def signableBytes: Array[Byte] =
        BigInt(request.newViewNumber).toByteArray ++
        BigInt(request.lastStableCheckpoinSeqNumber).toByteArray ++
        request.checkpoints.flatMap(_.signableBytes).toArray ++
        request.pms
          .map(x =>
            x.prePrepare
              .map(_.signableBytes)
              .get ++ x.prepares.flatMap(_.signableBytes)
          )
          .toArray
          .flatten ++
        BigInt(request.replicaId).toByteArray
    }

    implicit class NewViewRequestOp(val request: NewViewRequest) {

      def signableBytes: Array[Byte] =
        BigInt(request.newViewNumber).toByteArray ++
        request.viewChanges.flatMap(_.signableBytes).toArray ++
        request.preprepares.flatMap(_.signableBytes).toArray
    }

    implicit class CheckpointRequestOp(val request: CheckpointRequest) {

      def signableBytes: Array[Byte] =
        BigInt(request.sequenceNumber).toByteArray ++
        request.digest.toByteArray() ++
        BigInt(request.replicaId).toByteArray
    }

    implicit class PrePrepareRequestOp(val request: PrePrepareRequest) {

      def signableBytes: Array[Byte] =
        BigInt(request.viewNumber).toByteArray ++
        BigInt(request.sequenceNumber).toByteArray ++
        request.digest.toByteArray()
    }

    implicit class CommitRequestOp(val request: CommitRequest) {

      def signableBytes: Array[Byte] =
        BigInt(request.viewNumber).toByteArray ++
        BigInt(request.sequenceNumber).toByteArray ++
        request.digest.toByteArray() ++
        BigInt(request.replicaId).toByteArray
    }

    implicit class PrepareRequestOp(val request: PrepareRequest) {

      def signableBytes: Array[Byte] =
        BigInt(request.viewNumber).toByteArray ++
        BigInt(request.sequenceNumber).toByteArray ++
        request.digest.toByteArray() ++
        BigInt(request.replicaId).toByteArray
    }

    // add extension method to StateMachineRequest
    implicit class StateMachineRequestOp(val request: StateMachineRequest) extends AnyVal {

      def signableBytes: Array[Byte] =
        BigInt(request.timestamp).toByteArray ++
        BigInt(request.clientNumber).toByteArray ++
        request.operation.startSession
          .map(x => x.pkey.getBytes() ++ x.sha256.getBytes())
          .getOrElse(Array.emptyByteArray) ++
        request.operation.postDepositBTC
          .map(x =>
            x.sessionId.getBytes() ++
            BigInt(x.height).toByteArray ++
            x.txId.getBytes() ++
            BigInt(x.vout).toByteArray ++
            x.amount.toByteArray()
          )
          .getOrElse(Array.emptyByteArray) ++
        request.operation.timeoutDepositBTC
          .map(x => x.sessionId.getBytes() ++ BigInt(x.height).toByteArray)
          .getOrElse(Array.emptyByteArray) ++
        request.operation.timeoutTBTCMint
          .map(x =>
            x.sessionId.getBytes() ++
            BigInt(x.btcHeight).toByteArray
            ++ BigInt(x.toplHeight).toByteArray
          )
          .getOrElse(Array.emptyByteArray) ++
        request.operation.postRedemptionTx
          .map(x =>
            x.sessionId.getBytes() ++
            x.secret.getBytes() ++
            BigInt(x.height).toByteArray ++
            x.utxoTxId.getBytes() ++
            BigInt(x.utxoIndex).toByteArray ++
            x.txId.getBytes() ++
            BigInt(x.vout).toByteArray ++
            x.amount.toByteArray()
          )
          .getOrElse(Array.emptyByteArray) ++
        request.operation.postClaimTx
          .map(x =>
            x.sessionId.getBytes() ++
            BigInt(x.height).toByteArray ++
            x.txId.getBytes() ++
            BigInt(x.vout).toByteArray
          )
          .getOrElse(Array.emptyByteArray)
    }

    // add extension method to StateMachineReply
    implicit class StateMachineReplyOp(val reply: StateMachineReply) extends AnyVal {

      def signableBytes: Array[Byte] =
        BigInt(reply.viewNumber).toByteArray ++
        BigInt(reply.timestamp).toByteArray ++
        BigInt(reply.replicaNumber).toByteArray ++
        reply.result.startSession
          .map(x =>
            x.sessionId.getBytes() ++ x.script.getBytes() ++ x.escrowAddress
              .getBytes() ++ x.descriptor.getBytes() ++ BigInt(
              x.minHeight
            ).toByteArray ++ BigInt(x.maxHeight).toByteArray
          )
          .getOrElse(Array.emptyByteArray)
    }

  }
}
