package xyz.stratalab.bridge.consensus.core

import cats.effect.kernel.Sync
import com.typesafe.config.Config
import org.typelevel.log4cats.Logger
import xyz.stratalab.bridge.shared.ReplicaId
import xyz.stratalab.sdk.utils.Encoding

trait InitUtils {

  def printParams[F[_]: Sync: Logger](
    params: StrataBTCBridgeConsensusParamConfig
  ) = {
    import org.typelevel.log4cats.syntax._
    import cats.implicits._
    for {
      // For each parameter, log its value to info
      _ <- info"Command line arguments"
      _ <- info"btc-blocks-to-recover    : ${params.btcWaitExpirationTime}"
      _ <- info"strata-blocks-to-recover   : ${params.toplWaitExpirationTime}"
      _ <-
        info"btc-confirmation-threshold  : ${params.btcConfirmationThreshold}"
      _ <-
        info"strata-confirmation-threshold : ${params.toplConfirmationThreshold}"
      _ <- info"btc-peg-in-seed-file     : ${params.btcPegInSeedFile}"
      _ <- info"btc-peg-in-password      : ******"
      _ <- info"wallet-seed-file         : ${params.btcWalletSeedFile}"
      _ <- info"wallet-password          : ******"
      _ <- info"strata-wallet-seed-file    : ${params.toplWalletSeedFile}"
      _ <- info"strata-wallet-password     : ******"
      _ <- info"strata-wallet-db           : ${params.toplWalletDb}"
      _ <- info"btc-url                  : ${params.btcUrl}"
      _ <- info"btc-user                 : ${params.btcUser}"
      _ <- info"zmq-host                 : ${params.zmqHost}"
      _ <- info"zmq-port                 : ${params.zmqPort}"
      _ <- info"btc-password             : ******"
      _ <- info"btc-network              : ${params.btcNetwork}"
      _ <- info"strata-network             : ${params.toplNetwork}"
      _ <- info"strata-host                : ${params.toplHost}"
      _ <- info"strata-port                : ${params.toplPort}"
      _ <- info"config-file              : ${params.configurationFile.toPath().toString()}"
      _ <- info"strata-secure-connection   : ${params.toplSecureConnection}"
      _ <- info"minting-fee              : ${params.mintingFee}"
      _ <- info"fee-per-byte             : ${params.feePerByte}"
      _ <- info"abtc-group-id            : ${Encoding.encodeToHex(params.groupId.value.toByteArray)}"
      _ <- info"abtc-series-id           : ${Encoding.encodeToHex(params.seriesId.value.toByteArray)}"
      _ <- info"db-file                  : ${params.dbFile.toPath().toString()}"
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
