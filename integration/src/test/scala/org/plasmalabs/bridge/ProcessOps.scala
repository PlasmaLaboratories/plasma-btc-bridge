package org.plasmalabs.bridge

import cats.effect.IO
import fs2.io.process

trait ProcessOps {

  def signTransactionSeq(tx: String, walletName: String) = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    s"-rpcwallet=${walletName}",
    "signrawtransactionwithwallet",
    tx
  )

  def signTransactionP(tx: String, walletName: String = "testwallet") = process
    .ProcessBuilder(DOCKER_CMD, signTransactionSeq(tx, walletName): _*)
    .spawn[IO]

  def sendTransactionP(signedTx: String, walletName: String) = process
    .ProcessBuilder(DOCKER_CMD, sendTransactionSeq(signedTx, walletName): _*)
    .spawn[IO]

  def pwdP = process
    .ProcessBuilder("pwd")
    .spawn[IO]

  def addSecretP(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "add-secret",
        "--walletdb",
        userWalletDb(id),
        "--secret",
        secretMap(id),
        "--digest",
        "sha256"
      ): _*
    )
    .spawn[IO]

  // node-query mint-block --nb-blocks -1 -h localhost --port 9084 -s false
  def mintBlockSeq(node: Int, nbBlocks: Int) = Seq(
    "node-query",
    "mint-block",
    "--nb-blocks",
    nbBlocks.toString(),
    "-h",
    nodeHostMap(node),
    "--port",
    nodePortMap(node).toString(),
    "-s",
    "false"
  )

  def mintBlockDockerSeq(node: Int, nbBlocks: Int) = Seq(
    "exec",
    "node" + f"${node}%02d",
    "plasma-cli",
    "node-query",
    "mint-block",
    "--nb-blocks",
    nbBlocks.toString(),
    "-h",
    "localhost",
    "--port",
    9084.toString(),
    "-s",
    "false"
  )

  def mintBlockP(node: Int, nbBlocks: Int) = process
    .ProcessBuilder(CS_CMD, (csParams ++ mintBlockSeq(node, nbBlocks)): _*)
    .spawn[IO]

  def mintBlockDockerP(node: Int, nbBlocks: Int) = process
    .ProcessBuilder(DOCKER_CMD, mintBlockDockerSeq(node, nbBlocks): _*)
    .spawn[IO]

  def setNetworkActiveSeq(nodeId: Int, state: Boolean) = Seq(
    "exec",
    "bitcoin" + f"${nodeId}%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "setnetworkactive",
    state.toString
  )

  def setNetworkActiveP(nodeId: Int, state: Boolean) = process
    .ProcessBuilder(
      DOCKER_CMD,
      setNetworkActiveSeq(nodeId, state): _*
    )
    .spawn[IO]

  def forceConnectionSeq(nodeId: Int, ip: String, port: Int) = Seq(
    "exec",
    "bitcoin" + f"${nodeId}%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "addnode",
    s"$ip:$port",
    "onetry"
  )

  def forceConnectionP(nodeId: Int, ip: String, port: Int) = process
    .ProcessBuilder(
      DOCKER_CMD,
      forceConnectionSeq(nodeId, ip, port): _*
    )
    .spawn[IO]

  val createWalletSeqBase = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-regtest",
    "-named",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "createwallet",
  )

  val getNewaddressSeqBase = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-regtest"
  )

  def getNewaddressP(walletName: String) = process
    .ProcessBuilder(DOCKER_CMD, getNewaddressSeqBase ++ Seq(
      s"-rpcwallet=${walletName}",
      "getnewaddress"
    ): _*)
    .spawn[IO]

  def initUserBitcoinWalletP(walletName: String) = process
    .ProcessBuilder(
      DOCKER_CMD, 
      createWalletSeqBase ++ Seq(
      s"wallet_name=${walletName}"
    ): _*)
    .spawn[IO]
  
  def initUserWalletP(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "init",
        "--network",
        "private",
        "--password",
        "password",
        "--newwalletdb",
        userWalletDb(id),
        "--mnemonicfile",
        userWalletMnemonic(id),
        "--output",
        userWalletJson(id)
      ): _*
    )
    .spawn[IO]

  def generateToAddressSeq(nodeId: Int, blocks: Int, address: String) = Seq(
    "exec",
    "bitcoin" + f"$nodeId%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "generatetoaddress",
    blocks.toString,
    address
  )

  val extractGetTxIdSeq = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-regtest",
  )

  def extractGetTxIdP = process
    .ProcessBuilder(DOCKER_CMD, extractGetTxIdSeq ++ Seq(
      "-rpcwallet=testwallet",
      "listunspent"
    ): _*)
    .spawn[IO]

  def extractGetTxIdP(walletName: String) = process
    .ProcessBuilder(DOCKER_CMD, extractGetTxIdSeq ++ Seq(
      s"-rpcwallet=${walletName}",
      "listunspent"
    ): _*)
    .spawn[IO]

  def generateToAddressP(nodeId: Int, blocks: Int, address: String) = process
    .ProcessBuilder(
      DOCKER_CMD,
      generateToAddressSeq(nodeId, blocks, address): _*
    )
    .spawn[IO]

  // plasma-cli fellowships add --walletdb user-wallet.db --fellowship-name bridge
  def addFellowshipP(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      Seq(
        "launch",
        "-r",
        "https://s01.oss.sonatype.org/content/repositories/releases",
        "org.plasmalabs:plasma-cli_2.13:0.1.0",
        "--",
        "fellowships",
        "add",
        "--walletdb",
        userWalletDb(id),
        "--fellowship-name",
        "bridge"
      ): _*
    )
    .spawn[IO]

  def createTxP(txId: String, address: String, amount: BigDecimal) = process
    .ProcessBuilder(
      DOCKER_CMD,
      createTxSeq(
        txId,
        address,
        amount
      ): _*
    )
    .spawn[IO]

}
