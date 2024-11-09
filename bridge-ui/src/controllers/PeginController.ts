
import { getCookie, setCookie } from '../cookie-typescript-utils';
import { SessionInformation, StartSessionResponse } from '../views/StartSession';

export enum PeginUIState {
  InitialState,
  SessionStarted,
  TimeOutBTCNotSent,
  MintingTBTC,
  TimeOutMintingTBTC,
  WaitingForRedemption,
  WaitingForClaim
}

function stringToPeginUIState(state: string): PeginUIState {
  switch (state) {
    case "InitialState":
      return PeginUIState.InitialState;
    case "PeginSessionStateWaitingForBTC":
      return PeginUIState.SessionStarted;
    case "PeginSessionStateMintingTBTC": // MintingTBTC
      return PeginUIState.MintingTBTC;
    case "PeginSessionWaitingForRedemption":
      return PeginUIState.WaitingForRedemption;
    case "PeginSessionWaitingForClaim":
      return PeginUIState.WaitingForClaim;
    case "PeginSessionTimeOutBTCNotSent":
      return PeginUIState.TimeOutBTCNotSent;
    case "PeginSessionTimeOutMintingTBTC":
      return PeginUIState.TimeOutMintingTBTC;
    default:
      return PeginUIState.InitialState;
  }
}

export function setupSession(session: SessionInformation, setSession: React.Dispatch<React.SetStateAction<SessionInformation>>) {
  if (!session.isSet) {
    const sessionId = getCookie("sessionID");
    const escrowAddress = getCookie("escrowAddress");
    const escrowScript = getCookie("escrowScript");
    const currentState = getCookie("currentState");
    const redeemAddress = getCookie("redeemAddress");
    const plasmaBridgePKey = getCookie("plasmaBridgePKey");
    const redeemTemplate = getCookie("redeemTemplate");

    if (sessionId !== undefined && escrowAddress !== undefined && escrowScript !== undefined && currentState !== undefined && redeemAddress !== undefined && plasmaBridgePKey !== undefined && redeemTemplate !== undefined) {
      console.log("Session exists in cookie")
      setSession({ isSet: true, sessionID: sessionId, escrowAddress: escrowAddress, escrowScript: escrowScript, currentState: stringToPeginUIState(currentState), redeemAddress: redeemAddress, plasmaBridgePKey: plasmaBridgePKey, redeemTemplate: redeemTemplate });
    }
  }
}

export function sessionStarted(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, response: StartSessionResponse) {
  setCookie("sessionID", response.sessionID);
  setCookie("escrowAddress", response.escrowAddress);
  setCookie("escrowScript", response.script);
  setCookie("currentState", "PeginSessionStateWaitingForBTC");
  setSession({ isSet: true, sessionID: response.sessionID, escrowAddress: response.escrowAddress, escrowScript: response.script, currentState: PeginUIState.SessionStarted, redeemAddress: "", plasmaBridgePKey: "", redeemTemplate: "" });
}

export function btcArrived(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionStateMintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, escrowScript: session.escrowScript, currentState: PeginUIState.MintingTBTC, redeemAddress: "", plasmaBridgePKey: "", redeemTemplate: "" });
}

export function mintingBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionStateMintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, escrowScript: session.escrowScript, currentState: PeginUIState.MintingTBTC, redeemAddress: "", plasmaBridgePKey: "", redeemTemplate: "" });
}

export function timeOutMintingBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionTimeOutMintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, escrowScript: session.escrowScript, currentState: PeginUIState.TimeOutMintingTBTC, redeemAddress: "", plasmaBridgePKey: "", redeemTemplate: "" });
}

export function timeOutBTCNotSent(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionTimeOutBTCNotSent");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, escrowScript: session.escrowScript, currentState: PeginUIState.TimeOutBTCNotSent, redeemAddress: "", plasmaBridgePKey: "", redeemTemplate: "" });
}


export function mintedBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation, address: string, plasmaBridgePKey: string, redeemTemplate: string) {
  setCookie("currentState", "PeginSessionWaitingForRedemption");
  setCookie("redeemAddress", address);
  setCookie("plasmaBridgePKey", plasmaBridgePKey);
  setCookie("redeemTemplate", redeemTemplate);
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, escrowScript: session.escrowScript, currentState: PeginUIState.WaitingForRedemption, redeemAddress: address, plasmaBridgePKey: plasmaBridgePKey, redeemTemplate: redeemTemplate });
}

export function claimedTBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionWaitingForClaim");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, escrowScript: session.escrowScript, currentState: PeginUIState.WaitingForClaim, redeemAddress: session.redeemAddress, plasmaBridgePKey: session.plasmaBridgePKey, redeemTemplate: session.redeemTemplate });
}