package org.plasmalabs.bridge.consensus

import quivr.models.Int128

package object shared {

  sealed trait NodeCurrencyUnit {
    val amount: Int128
  }

  case class Lvl(amount: Int128) extends NodeCurrencyUnit
  case class SeriesToken(id: String, amount: Int128) extends NodeCurrencyUnit
  case class GroupToken(id: String, amount: Int128) extends NodeCurrencyUnit
  case class AssetToken(groupId: String, seriesId: String, amount: Int128) extends NodeCurrencyUnit

  class BTCConfirmationThreshold(val underlying: Int) extends AnyVal

  class BTCRetryThreshold(val underlying: Int) extends AnyVal

  class BTCWaitExpirationTime(val underlying: Int) extends AnyVal

  class PlasmaWaitExpirationTime(val underlying: Int) extends AnyVal

  class PlasmaConfirmationThreshold(val underlying: Int) extends AnyVal

  sealed trait SessionInfo

  /**
   * This class is used to store the session information for a pegin.
   *
   * @param btcPeginCurrentWalletIdx
   *   The index of the pegin wallet that is currently being used.
   * @param btcBridgeCurrentWalletIdx
   *   The index of the bridge wallet that is currently being used.
   * @param mintTemplateName
   *   The name under which the mint template is stored.
   * @param redeemAddress
   *   The address where the pegin will be redeemed.
   * @param escrowAddress
   *   The address where the BTC to peg in will be deposited.
   * @param scriptAsm
   *   The script that is used to redeem the pegin.
   * @param sha256
   *   The hash of the secret that is used to redeem the pegin.
   * @param mintingBTCState
   *   The state of the minting process for this session.
   */
  case class PeginSessionInfo(
    btcPeginCurrentWalletIdx:  Int,
    btcBridgeCurrentWalletIdx: Int,
    mintTemplateName:          String,
    redeemAddress:             String,
    escrowAddress:             String,
    scriptAsm:                 String,
    sha256:                    String,
    minHeight:                 Long,
    maxHeight:                 Long,
    claimAddress:              String,
    mintingBTCState:           PeginSessionState
  ) extends SessionInfo

  sealed trait PeginSessionState

  case object PeginSessionState {
    case object PeginSessionStateSuccessfulPegin extends PeginSessionState
    case object PeginSessionStateTimeout extends PeginSessionState
    case object PeginSessionStateWaitingForBTC extends PeginSessionState
    case object PeginSessionStateMintingTBTC extends PeginSessionState
    case object PeginSessionWaitingForRedemption extends PeginSessionState
    case object PeginSessionWaitingForClaim extends PeginSessionState
    case object PeginSessionMintingTBTCConfirmation extends PeginSessionState
    case object PeginSessionConfirmingRedemption extends PeginSessionState
    case object PeginSessionWaitingForEscrowBTCConfirmation extends PeginSessionState
    case object PeginSessionWaitingForClaimBTCConfirmation extends PeginSessionState

    def withName(s: String): Option[PeginSessionState] = s match {
      case "PeginSessionConfirmingRedemption" =>
        Some(PeginSessionConfirmingRedemption)
      case "PeginSessionStateSuccessfulPegin" =>
        Some(PeginSessionStateSuccessfulPegin)
      case "PeginSessionStateTimeout" =>
        Some(PeginSessionStateTimeout)
      case "PeginSessionStateWaitingForBTC" =>
        Some(PeginSessionStateWaitingForBTC)
      case "PeginSessionStateMintingTBTC" => Some(PeginSessionStateMintingTBTC)
      case "PeginSessionWaitingForRedemption" =>
        Some(PeginSessionWaitingForRedemption)
      case "PeginSessionWaitingForClaim" => Some(PeginSessionWaitingForClaim)
      case "PeginSessionMintingTBTCConfirmation" =>
        Some(PeginSessionMintingTBTCConfirmation)
      case "PeginSessionWaitingForEscrowBTCConfirmation" =>
        Some(PeginSessionWaitingForEscrowBTCConfirmation)
      case "PeginSessionWaitingForClaimBTCConfirmation" =>
        Some(PeginSessionWaitingForClaimBTCConfirmation)
      case _ => None
    }
  }

}
