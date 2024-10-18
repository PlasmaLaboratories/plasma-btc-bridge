package org.plasmalabs.bridge.shared

/**
 * This class is used to create a new session for a peg-in.
 *
 * @param pkey
 *   The public key of the user.
 * @param sha256
 *   The hash of the secret that is used to redeem the peg-in.
 */
case class StartPeginSessionRequest(
  pkey:   String,
  sha256: String
)

case class StartPegoutSessionRequest(
  userBaseKey:   String,
  currentHeight: Int,
  sha256:        String
)

case class MintingStatusRequest(
  sessionID: String
)

sealed trait BridgeResponse

case class MintingStatusResponse(
  mintingStatus: String,
  address:       String,
  redeemScript:  String
) extends BridgeResponse

case class SyncWalletRequest(
  secret: String
)

case object PBFTInternalResponse extends BridgeResponse

case class StartPeginSessionResponse(
  sessionID:     String,
  script:        String,
  escrowAddress: String,
  descriptor:    String,
  minHeight:     Long,
  maxHeight:     Long
) extends BridgeResponse

case class StartPegoutSessionResponse(
  sessionID:     String,
  escrowAddress: String
)

sealed trait BridgeError extends Throwable {
  val error: String
}

case class UnknownError(error: String) extends BridgeError

case class TimeoutError(error: String) extends BridgeError

case class SessionNotFoundError(error: String) extends BridgeError
case class InvalidKey(error: String) extends BridgeError
case class InvalidHash(error: String) extends BridgeError
case class InvalidBase58(error: String) extends BridgeError
case class InvalidInput(error: String) extends BridgeError
case class WalletSetupError(error: String) extends BridgeError
