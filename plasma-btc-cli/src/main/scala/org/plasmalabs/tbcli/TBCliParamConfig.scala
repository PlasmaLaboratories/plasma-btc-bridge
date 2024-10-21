package org.plasmalabs.tbcli
// import org.plasmalabs.bridge.BitcoinNetworkIdentifiers
// import org.plasmalabs.bridge.RegTest

sealed abstract class StrataBTCCLICommand

case class InitSession(
  seedFile: String = "",
  password: String = "",
  secret:   String = ""
) extends StrataBTCCLICommand

// case class StrataBTCCLIParamConfig(
//     btcNetwork: BitcoinNetworkIdentifiers = RegTest,
//     command: Option[StrataBTCCLICommand] = None
// )
