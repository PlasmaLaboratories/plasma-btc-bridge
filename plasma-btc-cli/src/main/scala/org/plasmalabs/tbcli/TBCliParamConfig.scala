package org.plasmalabs.tbcli
// import co.topl.bridge.BitcoinNetworkIdentifiers
// import co.topl.bridge.RegTest

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
