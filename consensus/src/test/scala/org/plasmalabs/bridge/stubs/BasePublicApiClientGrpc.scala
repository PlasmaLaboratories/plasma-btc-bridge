package org.plasmalabs.bridge.stubs

import cats.effect.IO
import org.plasmalabs.bridge.consensus.core.PublicApiClientGrpc
import org.plasmalabs.bridge.consensus.service.StateMachineReply
import org.plasmalabs.bridge.shared.Empty

class BasePublicApiClientGrpc extends PublicApiClientGrpc[IO] {

  override def replyStartPegin(
    timestamp:       Long,
    currentView:     Long,
    startSessionRes: StateMachineReply.Result
  ): IO[Empty] = ???

}
