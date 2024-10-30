package org.plasmalabs.bridge.consensus.core.controllers

import cats.effect.IO
import com.google.protobuf.ByteString
import org.plasmalabs.bridge.consensus.core.StrataPrivatenet
import org.plasmalabs.bridge.consensus.shared.{
  BTCConfirmationThreshold,
  BTCRetryThreshold,
  BTCWaitExpirationTime,
  StrataConfirmationThreshold,
  StrataWaitExpirationTime
}
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import org.plasmalabs.sdk.utils.Encoding
import org.typelevel.log4cats.SelfAwareStructuredLogger

trait SharedData {

  implicit val logger: SelfAwareStructuredLogger[IO] =
    org.typelevel.log4cats.slf4j.Slf4jLogger
      .getLoggerFromName[IO]("test-logger")

  val testKey =
    "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a"

  val pegoutTestKey =
    "GeMD3jTdhpE1zpMBPwJWj2gUrFG5GxkFAc8GFDqjUaXXH2TyXXaDjmrxcnA18JUkgWFxBAzkuiQzJPbh3QdQoKg1oW4fbP3nn2"

  val testHash =
    "497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2"

  val testInputTx =
    "08d9a63c8e05945d83dd8460d9418e506beb041dd719104108fc53733e8de1d0"

  val peginWalletFile = "src/test/resources/pegin-wallet.json"

  val walletFile = "src/test/resources/wallet.json"

  val toplWalletFile = "src/test/resources/plasma-wallet.json"

  val toplWalletDbInitial = "src/universal/plasma-wallet.db"

  val toplWalletDb = "src/universal/plasma-wallet-instance.db"

  val testSecret = "secret"

  val testPassword = "password"

  val testStrataPassword = "test"

  val testTx =
    "02000000000101d0e18d3e7353fc08411019d71d04eb6b508e41d96084dd835d94058e3ca6d908000000000000000000016879070000000000160014"

  implicit val btcWaitExpirationTime: BTCWaitExpirationTime =
    new BTCWaitExpirationTime(100)

  implicit val toplWaitExpirationTime: StrataWaitExpirationTime =
    new StrataWaitExpirationTime(2000)

  implicit val btcConfirmationThreshold: BTCConfirmationThreshold = new BTCConfirmationThreshold(6)

  implicit val toplConfirmationThreshold: StrataConfirmationThreshold = new StrataConfirmationThreshold(6)

  implicit val btcRetryThreshold: BTCRetryThreshold = new BTCRetryThreshold(6)

  val testStrataNetworkId = StrataPrivatenet

  implicit val groupId: GroupId = GroupId(
    ByteString.copyFrom(
      Encoding
        .decodeFromHex(
          "fdae7b6ea08b7d5489c3573abba8b1765d39365b4e803c4c1af6b97cf02c54bf"
        )
        .toOption
        .get
    )
  )

  implicit val seriesId: SeriesId = SeriesId(
    ByteString.copyFrom(
      Encoding
        .decodeFromHex(
          "1ed1caaefda61528936051929c525a17a0d43ea6ae09592da06c9735d9416c03"
        )
        .toOption
        .get
    )
  )

}
