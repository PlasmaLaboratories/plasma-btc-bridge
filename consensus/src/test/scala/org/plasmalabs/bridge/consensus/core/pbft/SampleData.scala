package org.plasmalabs.bridge.consensus.core.pbft

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.currency.CurrencyUnit
import org.plasmalabs.bridge.consensus.core.{CheckpointInterval, Fellowship, KWatermark, LastReplyMap, Template}
import org.plasmalabs.bridge.consensus.shared.{BTCWaitExpirationTime, Lvl, PlasmaWaitExpirationTime}
import org.plasmalabs.bridge.shared.{ReplicaCount, ReplicaId}
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import org.plasmalabs.sdk.syntax._
import org.plasmalabs.sdk.utils.Encoding

import java.util.concurrent.ConcurrentHashMap

trait SampleData {

  import org.bitcoins.core.currency.SatoshisLong

  val privateKeyFile = "privateKey1.pem"

  val plasmaHost = "localhost"
  val plasmaPort = 9084
  val plasmaSecureConnection = false

  implicit val replicaCount: ReplicaCount = new ReplicaCount(7)

  implicit val kWatermark: KWatermark = new KWatermark(200)

  implicit val checkpointInterval: CheckpointInterval = new CheckpointInterval(
    100
  )

  implicit val replicaId: ReplicaId = new ReplicaId(1)

  val plasmaWalletFile = "src/test/resources/plasma-wallet.json"

  val testPlasmaPassword = "test"

  val btcUser = "user"
  val btcPassword = "password"

  val btcNetwork = RegTest

  val btcUrl = "http://localhost:18332"

  implicit val plasmaWaitExpirationTime: PlasmaWaitExpirationTime =
    new PlasmaWaitExpirationTime(1000)

  implicit val btcWaitExpirationTime: BTCWaitExpirationTime =
    new BTCWaitExpirationTime(100)

  implicit val defaultMintingFee: Lvl = Lvl(100)

  implicit val astReplyMap: LastReplyMap = new LastReplyMap(
    new ConcurrentHashMap()
  )

  implicit val defaultFromFellowship: Fellowship = new Fellowship("default")

  implicit val defaultFromTemplate: Template = new Template("default")

  implicit val defaultFeePerByte: CurrencyUnit = 2.sats

  implicit val groupIdIdentifier: GroupId = GroupId(
    ByteString.copyFrom(
      Encoding
        .decodeFromHex(
          "a02be091b487960668958b39168e122210a8d5f5464deffb69ffebb3b2cfa131"
        )
        .toOption
        .get
    )
  )

  implicit val seriesIdIdentifier: SeriesId = SeriesId(
    ByteString.copyFrom(
      Encoding
        .decodeFromHex(
          "f323dd59469b53faf7fde28d234f6f1acc8c43405e976c7eec4a388e66c82479"
        )
        .toOption
        .get
    )
  )

  val conf = ConfigFactory.parseString(
    """
      |bridge.replica.consensus.replicas {
      |  0 {
      |    publicKeyFile = "publicKey0.pem"
      |  }
      |  1 {
      |    publicKeyFile = "publicKey1.pem"
      |  }
      |  2 {
      |    publicKeyFile = "publicKey2.pem"
      |  }
      |  3 {
      |    publicKeyFile = "publicKey3.pem"
      |  }
      |  4 {
      |    publicKeyFile = "publicKey4.pem"
      |  }
      |  5 {
      |    publicKeyFile = "publicKey5.pem"
      |  }
      |  6 {
      |    publicKeyFile = "publicKey6.pem"
      |  }
      |}
      |""".stripMargin
  )

}
