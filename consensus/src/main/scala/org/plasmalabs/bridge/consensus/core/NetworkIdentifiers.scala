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

sealed abstract class PlasmaNetworkIdentifiers(
  val i:         Int,
  val name:      String,
  val networkId: Int
) {
  override def toString: String = name
}

case object PlasmaNetworkIdentifiers {

  def values = Set(PlasmaMainnet, PlasmaTestnet, PlasmaPrivatenet)

  def fromString(s: String): Option[PlasmaNetworkIdentifiers] =
    s match {
      case "mainnet" => Some(PlasmaMainnet)
      case "testnet" => Some(PlasmaTestnet)
      case "private" => Some(PlasmaPrivatenet)
      case _         => None
    }
}

case object PlasmaMainnet
    extends PlasmaNetworkIdentifiers(
      0,
      "mainnet",
      NetworkConstants.MAIN_NETWORK_ID
    )

case object PlasmaTestnet
    extends PlasmaNetworkIdentifiers(
      1,
      "testnet",
      NetworkConstants.TEST_NETWORK_ID
    )

case object PlasmaPrivatenet
    extends PlasmaNetworkIdentifiers(
      2,
      "private",
      NetworkConstants.PRIVATE_NETWORK_ID
    )
