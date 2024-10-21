package org.plasmalabs.bridge.consensus.core

import org.plasmalabs.sdk.constants.NetworkConstants

sealed abstract class BitcoinNetworkIdentifiers(
  val name: String
) {
  override def toString: String = name

  def btcNetwork: org.bitcoins.core.config.BitcoinNetwork =
    this match {
      case Mainnet => org.bitcoins.core.config.MainNet
      case Testnet => org.bitcoins.core.config.TestNet3
      case RegTest => org.bitcoins.core.config.RegTest
    }
}

case object Mainnet extends BitcoinNetworkIdentifiers("mainnet")
case object Testnet extends BitcoinNetworkIdentifiers("testnet")
case object RegTest extends BitcoinNetworkIdentifiers("regtest")

case object BitcoinNetworkIdentifiers {

  def values = Set(Mainnet, Testnet, RegTest)

  def fromString(s: String): Option[BitcoinNetworkIdentifiers] =
    s match {
      case "mainnet" => Some(Mainnet)
      case "testnet" => Some(Testnet)
      case "regtest" => Some(RegTest)
      case _         => None
    }
}

sealed abstract class StrataNetworkIdentifiers(
  val i:         Int,
  val name:      String,
  val networkId: Int
) {
  override def toString: String = name
}

case object StrataNetworkIdentifiers {

  def values = Set(StrataMainnet, StrataTestnet, StrataPrivatenet)

  def fromString(s: String): Option[StrataNetworkIdentifiers] =
    s match {
      case "mainnet" => Some(StrataMainnet)
      case "testnet" => Some(StrataTestnet)
      case "private" => Some(StrataPrivatenet)
      case _         => None
    }
}

case object StrataMainnet
    extends StrataNetworkIdentifiers(
      0,
      "mainnet",
      NetworkConstants.MAIN_NETWORK_ID
    )

case object StrataTestnet
    extends StrataNetworkIdentifiers(
      1,
      "testnet",
      NetworkConstants.TEST_NETWORK_ID
    )

case object StrataPrivatenet
    extends StrataNetworkIdentifiers(
      2,
      "private",
      NetworkConstants.PRIVATE_NETWORK_ID
    )
