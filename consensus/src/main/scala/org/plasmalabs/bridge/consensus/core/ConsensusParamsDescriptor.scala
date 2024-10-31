package org.plasmalabs.bridge.consensus.core

import org.bitcoins.core.currency.{CurrencyUnit, SatoshisLong}
import org.plasmalabs.sdk.models.{GroupId, SeriesId}
import scopt.OParser

import java.io.File

trait ConsensusParamsDescriptor {

  import ParamParser._

  val builder = OParser.builder[PlasmaBTCBridgeConsensusParamConfig]

  val parser = {
    import builder._

    OParser.sequence(
      programName("plasma-btc-bridge-consensus"),
      head("plasma-btc-bridge-consensus", "0.1"),
      opt[Int]("checkpoint-interval")
        .action((x, c) => c.copy(checkpointInterval = x))
        .text(
          "The number of requests between checkpoints. (default: 100)"
        ),
      opt[Int]("k-watermark")
        .action((x, c) => c.copy(checkpointInterval = x))
        .text(
          "Gap between low and high watermark. (default: 200)"
        ),
      opt[BitcoinNetworkIdentifiers]("btc-network")
        .action((x, c) => c.copy(btcNetwork = x))
        .text(
          "Network name: Possible values: mainnet, testnet, regtest. (mandatory)"
        ),
      opt[PlasmaNetworkIdentifiers]("plasma-network")
        .action((x, c) => c.copy(toplNetwork = x))
        .text(
          "Network name: Possible values: mainnet, testnet, private. (mandatory)"
        ),
      opt[Int]("btc-blocks-to-recover")
        .action((x, c) => c.copy(btcWaitExpirationTime = x))
        .text(
          "The number of blocks that the user needs to wait before they can reclaim their funds. (default: 100)"
        ),
      opt[Int]("plasma-blocks-to-recover")
        .action((x, c) => c.copy(toplWaitExpirationTime = x))
        .text(
          "The number of blocks that the bridge needs to wait before it can burn the block. (default: 2000)"
        ),
      opt[String]("plasma-wallet-seed-file")
        .action((x, c) => c.copy(toplWalletSeedFile = x))
        .text(
          "The path to the tolp wallet seed file. (default: plasma-wallet.json)"
        ),
      opt[String]("plasma-wallet-password")
        .action((x, c) => c.copy(toplWalletPassword = x))
        .text(
          "The password to the topl seed file. (default: password)"
        ),
      opt[String]("plasma-wallet-db")
        .action((x, c) => c.copy(toplWalletDb = x))
        .text(
          "The topl wallet db. (default: plasma-wallet.db)"
        ),
      opt[String]("btc-peg-in-seed-file")
        .action((x, c) => c.copy(btcPegInSeedFile = x))
        .text(
          "The path to the peg in seed file. (default: pegin-wallet.json)"
        ),
      opt[String]("btc-peg-in-password")
        .action((x, c) => c.copy(btcPegInPassword = x))
        .text(
          "The password to the seed file. (default: password)"
        ),
      opt[String]("btc-wallet-seed-file")
        .action((x, c) => c.copy(btcWalletSeedFile = x))
        .text(
          "The path to the seed file. (default: wallet.json)"
        ),
      opt[String]("password")
        .action((x, c) => c.copy(walletPassword = x))
        .text(
          "The password to the seed file. (default: password)"
        ),
      opt[String]("plasma-host")
        .action((x, c) => c.copy(toplHost = x))
        .text("The host of the Plasma node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty) failure("Plasma node host may not be empty")
          else success
        ),
      opt[String]("btc-url")
        .action((x, c) => c.copy(btcUrl = x))
        .text("The url of the Bitcoin node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty) failure("Bitcoin node url may not be empty")
          else success
        ),
      opt[String]("btc-user")
        .action((x, c) => c.copy(btcUser = x))
        .text("The username for the Bitcoin node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty)
            failure("Bitcoin node username may not be empty")
          else success
        ),
      opt[String]("btc-password")
        .action((x, c) => c.copy(btcPassword = x))
        .text("The password for the Bitcoin node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty)
            failure("Bitcoin node password may not be empty")
          else success
        ),
      opt[Int]("plasma-port")
        .action((x, c) => c.copy(toplPort = x))
        .text("Port for Plasma node. (mandatory)")
        .validate(x =>
          if (x >= 0 && x <= 65536) success
          else failure("Port must be between 0 and 65536")
        ),
      opt[Int]("zmq-port")
        .action((x, c) => c.copy(zmqPort = x))
        .text("Port for ZMQ. (optional)")
        .validate(x =>
          if (x >= 0 && x <= 65536) success
          else failure("Port must be between 0 and 65536")
        ),
      opt[String]("zmq-host")
        .action((x, c) => c.copy(zmqHost = x))
        .text("Host for ZMQ. (optional)")
        .validate(x =>
          if (x.trim().isEmpty) failure("ZMQ host may not be empty")
          else success
        ),
      opt[Boolean]("plasma-secure")
        .action((x, c) => c.copy(toplSecureConnection = x))
        .text("Enables the secure connection to the node. (optional)"),
      opt[Long]("minting-fee")
        .action((x, c) => c.copy(mintingFee = x))
        .text("The fee for minting. (optional)"),
      opt[CurrencyUnit]("fee-per-byte")
        .action((x, c) => c.copy(feePerByte = x))
        .validate(x =>
          if (x > 0.satoshis) success
          else failure("Fee per byte must be stricly greater than 0")
        )
        .text("The fee per byte in satoshis. (optional)"),
      opt[GroupId]("abtc-group-id")
        .action((x, c) => c.copy(groupId = x))
        .text("Group id of the aBTC asset.")
        .required(),
      opt[SeriesId]("abtc-series-id")
        .action((x, c) => c.copy(seriesId = x))
        .text("Series id of the aBTC asset.")
        .required(),
      opt[Int]("btc-confirmation-threshold")
        .action((x, c) => c.copy(btcConfirmationThreshold = x))
        .text(
          "The number of confirmations required for a peg-in transaction in the BTC network. (mandatory)"
        )
        .validate( // check that it is a positive number
          x =>
            if (x > 0) success
            else failure("Confirmation threshold must be a positive number")
        ),
      opt[Int]("plasma-confirmation-threshold")
        .action((x, c) => c.copy(toplConfirmationThreshold = x))
        .text(
          "The number of confirmations required for a peg-in transaction in the Plasma network. (mandatory)"
        )
        .validate( // check that it is a positive number
          x =>
            if (x > 0) success
            else failure("Confirmation threshold must be a positive number")
        ),
      opt[File]("config-file")
        .action((x, c) => c.copy(configurationFile = x))
        .validate(x =>
          if (x.exists) success
          else failure(s"Configuration file does not exist: ${x.getAbsolutePath}")
        )
        .text(
          "Configuration file for the consensus service"
        ),
      opt[File]("db-file")
        .action((x, c) => c.copy(dbFile = x))
        .text(
          "Database file for the consensus service"
        )
    )
  }

}
