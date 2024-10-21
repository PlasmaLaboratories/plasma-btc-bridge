package xyz.stratalab.bridge.consensus.core.pbft

import xyz.stratalab.bridge.consensus.pbft.{CommitRequest, PrePrepareRequest, PrepareRequest}
import xyz.stratalab.bridge.shared.ClientId

sealed abstract private[core] class PBFTInternalEvent(
  val requestIdentifier: RequestIdentifier
)

private[pbft] case class PrePreparedInserted(
  request: PrePrepareRequest
) extends PBFTInternalEvent(
      RequestIdentifier(
        ClientId(request.payload.get.clientNumber),
        request.payload.get.timestamp
      )
    )

private[pbft] case class Prepared(
  override val requestIdentifier: RequestIdentifier,
  request:                        PrepareRequest
) extends PBFTInternalEvent(requestIdentifier)

private[pbft] case class Commited(
  override val requestIdentifier: RequestIdentifier,
  request:                        CommitRequest
) extends PBFTInternalEvent(requestIdentifier)

private[pbft] case class PBFTTimeoutEvent(
  override val requestIdentifier: RequestIdentifier
) extends PBFTInternalEvent(requestIdentifier)
