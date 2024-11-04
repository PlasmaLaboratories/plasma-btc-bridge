import { useState } from 'react';
import { PeginUIState, sessionStarted } from '../controllers/PeginController';

export interface SessionInformation {
  isSet: boolean;
  sessionID: string;
  escrowAddress: string;
  currentState: PeginUIState;
  redeemAddress: string;
  plasmaBridgePKey: string;
  redeemTemplate: string;
  escrowScript: string;
}

export interface StartSessionRequest {
  pkey: string;
  sha256: string;
}

export interface StartSessionResponse {
  sessionID: string;
  script: string;
  escrowAddress: string;
  descriptor: string;
}
export interface ErrorResponse {
  error: string;
}


async function startSession(startSessionRequest: StartSessionRequest): Promise<StartSessionResponse | ErrorResponse> {
  const response = await fetch('/api/start-session-pegin',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(startSessionRequest)
    });
  const data = await response.json();
  return data;
}

function alertInstructions(isSet: boolean) {
  if (!isSet) {
    return (
      <div className="alert alert-primary" role="alert">
        Please enter the hash of the secret to start the peg-in process.
      </div>
    )
  } else {
    return (
      <div className="alert alert-success" role="alert">
        Session started, please transfer the funds to the escrow address.
      </div>
    )
  }
}

function errorValidation(error: string) {
  if (error !== "") {
    return (
      <div className="invalid-feedback">
        {error}
      </div>
    )
  } else {
    return (
      <div></div>
    )
  }
}

function waitingForDeposit(isWaiting: boolean) {
  if (isWaiting) {
    return (
      <div className="mb-3">
        <strong role="status">Waiting for funds to arrive...</strong>
        <div className="d-flex float-end">
          <div className="spinner-border ms-auto" aria-hidden="true"></div>
        </div>
      </div>
    )
  } else {
    return (
      <div></div>
    )
  }
}

function StartSession(session: SessionInformation, setSession: React.Dispatch<React.SetStateAction<SessionInformation>>) {

  const [hash, setHash] = useState<string>("")
  const [pk, setPk] = useState<string>("")
  const [error, setError] = useState<string>("")


  async function handleSubmitSha(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const startSessionRequest: StartSessionRequest = {
      pkey: pk,
      sha256: hash
    }
    const response = await startSession(startSessionRequest);
    if (typeof response === 'object' && !("error" in response)) {
      sessionStarted(setSession, response);
      setError("");
    } else {
      setError(response.error);
    }
  }

  const style = {
    width: '25%'
  };


  return (
    <>
      <div className="card-body">
        <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={25} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-success" style={style}>25%</div>
        </div>
        <div className="row">
          <form className='row g-3' onSubmit={handleSubmitSha}>
            <div className="row">
              <div className="mb-3">
                <label htmlFor="hash" className="form-label">SHA-256 Hash</label>
                <input value={hash} onChange={(e) => setHash(e.target.value)} type="text" className={error === "" ? "form-control" : "form-control is-invalid"} id="hash" placeholder="497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2" />
                {errorValidation(error)}
              </div>
              <div className="mb-3">
                <label htmlFor="pKey" className="form-label">Public Key</label>
                <input value={pk} onChange={(e) => setPk(e.target.value)} type="text" className={error === "" ? "form-control" : "form-control is-invalid"} id="pKey" placeholder="0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a" />
                {errorValidation(error)}
              </div>
              <div className="mb-3">
                <button type="submit" className="btn btn-primary mb-3" disabled={session.isSet}>Start Session</button>
              </div>
            </div>
          </form>
          <div className='row g-3'>
            <div className="row">
              <div>
                <label htmlFor="escrowAddress" className="form-label">Escrow Address</label>
                <input type="text" value={session.escrowAddress} className="form-control" id="escrowAddress" disabled />
              </div>
            </div>
          </div>
          <div className='row g-3'>
            <div className="row">
              <div className="mb-3">
                <label htmlFor="escrowScript" className="form-label">Escrow Script</label>
                <input type="text" value={session.escrowScript} className="form-control" id="escrowScript" disabled />
              </div>
            </div>
          </div>
        </div>
        {waitingForDeposit(session.isSet)}
        {alertInstructions(session.isSet)}
      </div>
    </>
  )
}

export default StartSession