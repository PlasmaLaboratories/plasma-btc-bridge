package xyz.stratalab.tbcli
// import xyz.stratalab.bridge.BitcoinNetworkIdentifiers
// import xyz.stratalab.bridge.RegTest

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
