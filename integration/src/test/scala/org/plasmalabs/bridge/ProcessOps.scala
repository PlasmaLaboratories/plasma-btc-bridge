package org.plasmalabs.bridge

import cats.effect.IO
import fs2.io.process

trait ProcessOps {

  def signTransactionSeq(tx: String, walletName: String = "testwallet") = Seq(
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

  def signTransactionP(tx: String) = process
    .ProcessBuilder(DOCKER_CMD, signTransactionSeq(tx): _*)
    .spawn[IO]

  def signTransactionP(tx: String, walletName: String) = process
    .ProcessBuilder(DOCKER_CMD, signTransactionSeq(tx, walletName): _*)
    .spawn[IO]

  def sendTransactionP(signedTx: String) = process
    .ProcessBuilder(DOCKER_CMD, sendTransactionSeq(signedTx): _*)
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
        userSecret(id),
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

  def createWalletSeq(walletName: String = "testwallet") = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-regtest",
    "-named",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "createwallet",
    s"wallet_name=${walletName}"
  )

  def getNewaddressSeq(walletName: String = "testwallet") = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-regtest",
    s"-rpcwallet=${walletName}",
    "getnewaddress"
  )

  def getNewaddressP = process
    .ProcessBuilder(DOCKER_CMD, getNewaddressSeq(): _*)
    .spawn[IO]

  def getNewaddressP(walletName: String) = process
    .ProcessBuilder(DOCKER_CMD, getNewaddressSeq(walletName): _*)
    .spawn[IO]

  def initUserBitcoinWalletP = process
    .ProcessBuilder(DOCKER_CMD, createWalletSeq(): _*)
    .spawn[IO]

  def initUserBitcoinWalletP(walletName: String) = process
    .ProcessBuilder(DOCKER_CMD, createWalletSeq(walletName): _*)
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

  def generateToAddressSeq(nodeId: Int, blocks: Int, address: String, walletName: String) = Seq(
    "exec",
    "bitcoin" + f"$nodeId%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    s"-rpcwallet=${walletName}",
    "generatetoaddress",
    blocks.toString,
    address
  )

  def extractGetTxIdSeq(walletName: String) = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    s"-rpcwallet=${walletName}",
    "-regtest",
    "listunspent"
  )

  def extractGetTxIdP = process
    .ProcessBuilder(DOCKER_CMD, extractGetTxIdSeq("testwallet"): _*)
    .spawn[IO]

  def extractGetTxIdP(walletName: String) = process
    .ProcessBuilder(DOCKER_CMD, extractGetTxIdSeq(walletName): _*)
    .spawn[IO]

  def generateToAddressP(nodeId: Int, blocks: Int, address: String, walletName: String) = process
    .ProcessBuilder(
      DOCKER_CMD,
      generateToAddressSeq(nodeId, blocks, address, walletName): _*
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

  def getTxP(id: Int, txId: String) = process
    .ProcessBuilder(
      DOCKER_CMD,
      getTxSeq(
        id,
        txId
      ): _*
    )
    .spawn[IO]

  def getBlockheightP = process
    .ProcessBuilder(
      DOCKER_CMD,
      getBlockHeightSeq: _*
    )
    .spawn[IO]

  def createTxPMultiple(txId: String, addresses: Seq[String], amount: BigDecimal) = process
    .ProcessBuilder(
      DOCKER_CMD,
      createTxSeqMultiple(
        txId,
        addresses,
        amount
      ): _*
    )
    .spawn[IO]

}
