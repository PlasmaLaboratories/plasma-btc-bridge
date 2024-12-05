package org.plasmalabs.bridge.consensus.core

import cats.effect.kernel.Sync
import com.typesafe.config.Config
import org.plasmalabs.bridge.shared.ReplicaId
import org.plasmalabs.sdk.utils.Encoding
import org.typelevel.log4cats.Logger

trait InitUtils {

  def printParams[F[_]: Sync: Logger](
    params: PlasmaBTCBridgeConsensusParamConfig
  ) = {
    import org.typelevel.log4cats.syntax._
    import cats.implicits._
    for {
      // For each parameter, log its value to info
      _ <- info"Command line arguments"
      _ <- info"btc-blocks-to-recover    : ${params.btcWaitExpirationTime}"
      _ <- info"plasma-blocks-to-recover   : ${params.plasmaWaitExpirationTime}"
      _ <-
        info"btc-confirmation-threshold  : ${params.btcConfirmationThreshold}"
      _ <-
        info"plasma-confirmation-threshold : ${params.plasmaConfirmationThreshold}"
      _ <- info"btc-peg-in-seed-file     : ${params.btcPegInSeedFile}"
      _ <- info"btc-peg-in-password      : ******"
      _ <- info"wallet-seed-file         : ${params.btcWalletSeedFile}"
      _ <- info"wallet-password          : ******"
      _ <- info"plasma-wallet-seed-file    : ${params.plasmaWalletSeedFile}"
      _ <- info"plasma-wallet-password     : ******"
      _ <- info"plasma-wallet-db           : ${params.plasmaWalletDb}"
      _ <- info"btc-url                  : ${params.btcUrl}"
      _ <- info"btc-user                 : ${params.btcUser}"
      _ <- info"zmq-host                 : ${params.zmqHost}"
      _ <- info"zmq-port                 : ${params.zmqPort}"
      _ <- info"btc-password             : ******"
      _ <- info"btc-network              : ${params.btcNetwork}"
      _ <- info"plasma-network             : ${params.plasmaNetwork}"
      _ <- info"plasma-host                : ${params.plasmaHost}"
      _ <- info"plasma-port                : ${params.plasmaPort}"
      _ <- info"config-file              : ${params.configurationFile.toPath().toString()}"
      _ <- info"plasma-secure-connection   : ${params.plasmaSecureConnection}"
      _ <- info"minting-fee              : ${params.mintingFee}"
      _ <- info"fee-per-byte             : ${params.feePerByte}"
      _ <- info"abtc-group-id            : ${Encoding.encodeToHex(params.groupId.value.toByteArray)}"
      _ <- info"abtc-series-id           : ${Encoding.encodeToHex(params.seriesId.value.toByteArray)}"
      _ <- info"db-file                  : ${params.dbFile.toPath().toString()}"
      _ <- info"db-file-signatures       : ${params.dbFile.toPath().toString()}"
    } yield ()
  }

  def replicaHost(implicit conf: Config) =
    conf.getString("bridge.replica.requests.host")

  def replicaPort(implicit conf: Config) =
    conf.getInt("bridge.replica.requests.port")

  def privateKeyFile(implicit conf: Config) =
    conf.getString("bridge.replica.security.privateKeyFile")

  def responseHost(implicit conf: Config) =
    conf.getString("bridge.replica.responses.host")

  def responsePort(implicit conf: Config) =
    conf.getInt("bridge.replica.responses.port")

  def internalReqHost(implicit conf: Config) =
    conf.getString("bridge.replica.internalRequests.host")

  def internalReqPort(implicit conf: Config) =
    conf.getInt("bridge.replica.internalRequests.port")

  def internalResHost(implicit conf: Config) =
    conf.getString("bridge.replica.internalResponses.host")

  def internalResPort(implicit conf: Config) =
    conf.getInt("bridge.replica.internalResponses.port")

  def printConfig[F[_]: Sync: Logger](implicit
    conf:      Config,
    replicaId: ReplicaId
  ) = {

    import org.typelevel.log4cats.syntax._
    import cats.implicits._
    for {
      _ <- info"Configuration arguments"
      _ <- info"bridge.replica.security.privateKeyFile : ${privateKeyFile}"
      _ <- info"bridge.replica.requests.host           : ${replicaHost}"
      _ <- info"bridge.replica.requests.port           : ${replicaPort}"
      _ <- info"bridge.replica.replicaId               : ${replicaId.id}"
    } yield ()
  }

}
