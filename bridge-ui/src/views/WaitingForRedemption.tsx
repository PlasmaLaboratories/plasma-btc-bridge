import { SessionInformation } from "./StartSession";



const style75 = {
  width: '75%'
};


function WaitingForRedemption(session: SessionInformation): JSX.Element {
  return (
    <>
      <div className="card-body">
        <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={75} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-success" style={style75}>75%</div>
        </div>
        <div className="row">
          <form className='row g-3'>
            <div className="row">
              <div className="mb-3">
                <div className="d-flex align-items-center">
                  <strong role="status">Tokens minted sucessfully</strong>
                </div>
                <div className="input-group mb-3">
                  <span className="input-group-text" id="redeem-address">Redeem address</span>
                  <input disabled value={session.redeemAddress} type="text" className="form-control" aria-label="Redeem address" aria-describedby="basic-addon1" />
                </div>
                <div className="input-group mb-3">
                  <span className="input-group-text" id="bridge-key">Bridge Key</span>
                  <input disabled value={session.plasmaBridgePKey} type="text" className="form-control" aria-label="Redeem template" aria-describedby="basic-addon1" />
                </div>
                <div className="input-group mb-3">
                  <span className="input-group-text" id="redeem-template">Redeem template</span>
                  <input disabled value={session.redeemTemplate} type="text" className="form-control" aria-label="Redeem template" aria-describedby="basic-addon1" />
                </div>
                <div className="mb-3">
                  <strong role="status">Waiting for funds to be redeemed...</strong>
                  <div className="d-flex float-end">
                    <div className="spinner-border ms-auto" aria-hidden="true"></div>
                  </div>
                </div>
              </div>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}

export default WaitingForRedemption;