package org.plasmalabs.bridge.consensus

import cats.effect.kernel.{Ref, Sync}
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import org.plasmalabs.bridge.consensus.core.managers.BTCWalletAlgebra
import org.plasmalabs.bridge.consensus.core.pbft.statemachine.PBFTState
import org.plasmalabs.bridge.consensus.service.StateMachineReply.Result
import org.plasmalabs.bridge.shared.ClientId
import quivr.models.KeyPair

import java.security.{MessageDigest, PublicKey}
import java.util.concurrent.ConcurrentHashMap

package object core {

  import scala.concurrent.duration.Duration

  class RequestTimeout(val underlying: Duration) extends AnyVal

  class PeginWalletManager[F[_]](val underlying: BTCWalletAlgebra[F]) extends AnyVal
  class BridgeWalletManager[F[_]](val underlying: BTCWalletAlgebra[F]) extends AnyVal
  class CheckpointInterval(val underlying: Int) extends AnyVal
  class CurrentPlasmaHeightRef[F[_]](val underlying: Ref[F, Long]) extends AnyVal
  class CurrentBTCHeightRef[F[_]](val underlying: Ref[F, Int]) extends AnyVal
  class PlasmaKeypair(val underlying: KeyPair) extends AnyVal

  case class WatermarkRef[F[_]](
    lowAndHigh: Ref[F, (Long, Long)]
  ) extends AnyVal

  case class KWatermark(
    underlying: Int
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
    address:          String,
    port:             Int,
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
