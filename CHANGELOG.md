

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - 2023-mm-dd (this date should be changed on release)

### Added

- Minting status is now displayed in the UI.
- Dependency on `akka-slf4j`.
- New parameters for the launcher:  `--btc-url`, `--btc-port`, `--btc-user`, `--btc-password`, `--zmq-url`, `--zmq-port`. These allow to connect to the Bitcoin node and the ZMQ server.
- Support for monitoring the Bitcoin network.
- Support for monitoring the Topl network.
- Logging all command line arguments when starting the application.
- Support for sad path: User did not send the funds.
- Support for sad path: Bridge did not mint the tokens in time.
- Support for sad path: User did not redeem the tokens in time.
- Add new parameter `--plasma-blocks-to-recover` to specify the number of blocks to wait for the Topl network to recover.
- Add new parameter `--btc-confirmation-threshold` to specify the number of confirmations needed for the deposit.
- Add two new fields to the `StartPeginSessionResponse` message: `minHeight` and `maxHeight`.
This is needed to know the range of blocks to wait for the redemption.
- Added parameters `--abtc-group-id` and `--abtc-series-id` to specify the group and series of the asset to mint.
- Added `replica.clientId` key in `consensus` module configuration.
- Added `responses` configurat ion key in `consensus` module configuration.
- Added `checkpoint-interval` parameter.


### Changed

- Default port is now 4000.
- `brambl-cli` version (for IT) is now 2.0.0-beta3.
- Updated BramblSc to 2.0.0-beta4.
- Integration tests to use our own Bitcoin regtest node Docker image.
- Front-end to use the new minting status.
- No longer hardcodes the user's public key. User can provide it during session creation
- Session creation also displays the escrow script for the user
- Rename `--blocks-to-recover` to `--btc-blocks-to-recover`.
- Update the contract for the redemption to include the height. The new contract
`threshold(1, sha256($sha256) and height($min, $max))`.
- We now require the group and series tokens to be present at start time.
- Final state is not 404, added new states for successful finish and failure.


### Removed

- `/api/confirm-deposit-btc` WS was removed.
- `/api/confirm-redemption` WS was removed.
- Removed the signature from the contract for the redemption.
- Removed the bridge public key from the `MintingStatusResponse`, as it is not
needed anymore.
