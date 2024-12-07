package org.plasmalabs.bridge

trait CommonSetupModule {
  lazy val requestPort = 4000
  lazy val clientPort = 6000
  
  lazy val outOfBandRequestPort = 30000

  lazy val apiPort = 5000

  def replicasConfString(replicaCount: Int) =
    (for {
      replicaId <- 0 until replicaCount
    } yield s"""
|        ${replicaId} = {
|          host = "localhost"
|          port = ${requestPort + replicaId}
|          secure = "false"
|          publicKeyFile = "consensusPublicKey${replicaId}.pem"
|          outOfBandRequestHost = "localhost"
|          outOfBandRequestPort = ${outOfBandRequestPort + replicaId}
|        }""").mkString("\n")

}
