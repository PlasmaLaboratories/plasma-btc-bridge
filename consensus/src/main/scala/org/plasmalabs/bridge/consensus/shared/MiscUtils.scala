package xyz.stratalab.bridge.consensus.shared

import xyz.stratalab.bridge.consensus.shared.{PeginSessionInfo, SessionInfo}

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}
