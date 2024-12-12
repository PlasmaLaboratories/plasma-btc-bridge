package org.plasmalabs.bridge.consensus.core

import org.bitcoins.core.currency.{CurrencyUnit, SatoshisLong}
import org.plasmalabs.sdk.models.{GroupId, SeriesId}

import java.io.File

case class PlasmaBTCBridgeConsensusParamConfig(
  btcWaitExpirationTime:       Int = 100, // the number of blocks to wait before the user can reclaim their funds
  btcConfirmationThreshold:    Int = 6, // the number of confirmations required for a peg-in transaction
  plasmaWaitExpirationTime:    Int = 2000, // the number of blocks to wait before the user can reclaim their funds
  plasmaConfirmationThreshold: Int = 6, // the number of confirmations required for a peg-out transaction
  checkpointInterval:          Int = 100, // the number of requests between checkpoints
  requestTimeout:              Int = 15, // the timeout for requests in seconds
  viewChangeTimeout:           Int = 5, // the timeout for view changes in seconds
  kWatermark:                  Int = 200, // the gap between the low and high watermark
  btcPegInSeedFile:            String = "pegin-wallet.json",
  btcPegInPassword:            String = "password",
  btcWalletSeedFile:           String = "wallet.json",
  walletPassword:              String = "password",
  plasmaWalletSeedFile:        String = "plasma-wallet.json",
  plasmaWalletPassword:        String = "password",
  plasmaWalletDb:              String = "plasma-wallet.db",
  btcUrl:                      String = "http://localhost",
  btcUser:                     String = "bitcoin",
  zmqHost:                     String = "localhost",
  zmqPort:                     Int = 28332,
  btcPassword:                 String = "password",
  btcNetwork:                  BitcoinNetworkIdentifiers = RegTest,
  plasmaNetwork:               PlasmaNetworkIdentifiers = PlasmaPrivatenet,
  plasmaHost:                  String = "localhost",
  plasmaPort:                  Int = 9084,
  btcRetryThreshold:           Int = 6,
  mintingFee:                  Long = 10,
  feePerByte:                  CurrencyUnit = 4.satoshis, // needed to be increased to reach min relay fee
  groupId:                     GroupId,
  seriesId:                    SeriesId,
  configurationFile:           File = new File("application.conf"),
  dbFile:                      File = new File("bridge.db"),
  plasmaSecureConnection:      Boolean = false,
  multiSigM:                   Int = 5, // M = min threshold for multisig
  multiSigN:                   Int = 7 // N = total number of public keys used for multisig
)
