package co.topl.bridge.consensus

import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.core.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.core.pbft.statemachine.PBFTState
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.shared.ClientId
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import quivr.models.KeyPair

import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

package object core {

  import scala.concurrent.duration.Duration

  class RequestTimeout(val underlying: Duration) extends AnyVal

  class PeginWalletManager[F[_]](val underlying: BTCWalletAlgebra[F])
      extends AnyVal
  class BridgeWalletManager[F[_]](val underlying: BTCWalletAlgebra[F])
      extends AnyVal
  class CurrentViewRef[F[_]](val underlying: Ref[F, Long]) extends AnyVal
  class CheckpointInterval(val underlying: Int) extends AnyVal
  class CurrentToplHeightRef[F[_]](val underlying: Ref[F, Long]) extends AnyVal
  class CurrentBTCHeightRef[F[_]](val underlying: Ref[F, Int]) extends AnyVal
  class ToplKeypair(val underlying: KeyPair) extends AnyVal
  case class StableCheckpoint(
      sequenceNumber: Long,
      certificates: Map[Int, CheckpointRequest],
      state: Map[String, PBFTState]
  )
  case class StateSnapshotRef[F[_]](
      state: Ref[F, (Long, String, Map[String, PBFTState])]
  ) extends AnyVal

  case class WatermarkRef[F[_]](
      lowAndHigh: Ref[F, (Long, Long)]
  ) extends AnyVal

  case class KWatermark(
      underlying: Int
  ) extends AnyVal

  case class StableCheckpointRef[F[_]](
      val underlying: Ref[F, StableCheckpoint]
  ) extends AnyVal

  // the key is a pair of the height and digest of the checkpoint
  case class UnstableCheckpointsRef[F[_]](
      val underlying: Ref[
        F,
        Map[
          (Long, String),
          Map[Int, CheckpointRequest]
        ]
      ]
  ) extends AnyVal
  class PublicApiClientGrpcMap[F[_]](
      val underlying: Map[
        ClientId,
        (PublicApiClientGrpc[F], PublicKey)
      ]
  ) extends AnyVal

  class LastReplyMap(
      val underlying: ConcurrentHashMap[(ClientId, Long), Result]
  ) extends AnyVal

  def channelResource[F[_]: Sync](
      address: String,
      port: Int,
      secureConnection: Boolean
  ) =
    (if (secureConnection)
       ManagedChannelBuilder
         .forAddress(address, port)
         .useTransportSecurity()
     else
       ManagedChannelBuilder
         .forAddress(address, port)
         .usePlaintext()).resource[F]

  def stateDigest(state: Map[String, PBFTState]) = {
    val stateBytes =
      state.toList
        .sortBy(_._1)
        .map(x => x._1.getBytes ++ x._2.toBytes)
        .flatten
    MessageDigest
      .getInstance("SHA-256")
      .digest(stateBytes.toArray)
  }

  class Fellowship(val underlying: String) extends AnyVal

  class Template(val underlying: String) extends AnyVal

}
