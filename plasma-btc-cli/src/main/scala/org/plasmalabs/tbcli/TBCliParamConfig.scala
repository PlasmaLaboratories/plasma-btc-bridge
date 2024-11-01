package org.plasmalabs.tbcli
// import org.plasmalabs.bridge.BitcoinNetworkIdentifiers
// import org.plasmalabs.bridge.RegTest

sealed abstract class PlasmaBTCCLICommand

case class InitSession(
  seedFile: String = "",
  password: String = "",
  secret:   String = ""
) extends PlasmaBTCCLICommand

// case class PlasmaBTCCLIParamConfig(
//     btcNetwork: BitcoinNetworkIdentifiers = RegTest,
//     command: Option[PlasmaBTCCLICommand] = None
// )
