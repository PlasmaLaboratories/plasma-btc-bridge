package xyz.stratalab.bridge.consensus.core.pbft

import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.syntax._
import co.topl.brambl.utils.Encoding
import xyz.stratalab.bridge.consensus.core.CheckpointInterval
import xyz.stratalab.bridge.consensus.core.KWatermark
import xyz.stratalab.bridge.consensus.shared.BTCWaitExpirationTime
import xyz.stratalab.bridge.consensus.shared.Lvl
import xyz.stratalab.bridge.consensus.shared.StrataWaitExpirationTime
import xyz.stratalab.bridge.shared.ReplicaCount
import xyz.stratalab.bridge.shared.ReplicaId
import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import java.util.concurrent.ConcurrentHashMap
import xyz.stratalab.bridge.consensus.core.LastReplyMap
import xyz.stratalab.bridge.consensus.core.Fellowship
import xyz.stratalab.bridge.consensus.core.Template
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.config.RegTest

trait SampleData {

  import org.bitcoins.core.currency.SatoshisLong

  val privateKeyFile = "privateKey1.pem"

  val toplHost = "localhost"
  val toplPort = 9084
  val toplSecureConnection = false


  implicit val replicaCount: ReplicaCount = new ReplicaCount(7)

  implicit val kWatermark: KWatermark = new KWatermark(200)

  implicit val checkpointInterval: CheckpointInterval = new CheckpointInterval(
    100
  )

  implicit val replicaId: ReplicaId = new ReplicaId(1)

  val toplWalletFile = "src/test/resources/strata-wallet.json"

  val testStrataPassword = "test"

  val btcUser = "user"
  val btcPassword = "password"

  val btcNetwork = RegTest

  val btcUrl = "http://localhost:18332"

  implicit val toplWaitExpirationTime: StrataWaitExpirationTime =
    new StrataWaitExpirationTime(1000)

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