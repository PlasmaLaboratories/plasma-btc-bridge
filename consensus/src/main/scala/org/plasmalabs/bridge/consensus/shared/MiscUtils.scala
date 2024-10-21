package org.plasmalabs.bridge.consensus.shared

import org.plasmalabs.bridge.consensus.shared.{PeginSessionInfo, SessionInfo}

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}
