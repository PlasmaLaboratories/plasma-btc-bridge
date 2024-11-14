package org.plasmalabs

import cats.effect.IO
import cats.effect.kernel.Resource
import fs2.io.file.Files
import fs2.io.process
import io.circe.generic.auto._
import io.circe.parser._
import org.http4s.circe._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityDecoder, Method, Request, Uri, _}
import org.plasmalabs.bridge.shared.{
  BridgeContants,
  MintingStatusRequest,
  MintingStatusResponse,
  StartPeginSessionRequest,
  StartPeginSessionResponse,
  SyncWalletRequest
}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.io.ByteArrayInputStream

package object bridge extends ProcessOps {

  import org.plasmalabs.bridge.implicits._

  val DOCKER_CMD = "docker"

  case class InputData(
    LockAddress: String,
    Type:        String,
    Id:          Option[String],
    Fungibility: Option[String],
    TokenSupply: Option[String],
    QuantDescr:  Option[String],
    Value:       Int,
    TxoAddress:  Option[String],
    FixedSeries: Option[String]
  )

  def withLogging(
    res: Resource[IO, process.Process[IO]]
  )(implicit l: Logger[IO]) =
    for {
      pair <- res.use(x => getText(x).product(getError(x)))
      (output, error) = pair
      _ <-
        if (error.trim().nonEmpty) {
          error"$error"
        } else {
          info"$output"
        }
    } yield pair

  def withTrace(
    res: Resource[IO, process.Process[IO]]
  )(implicit l: Logger[IO]) =
    for {
      pair <- res.use(x => getText(x).product(getError(x)))
      (output, error) = pair
      _ <-
        if (error.trim().nonEmpty) {
          error"$error"
        } else {
          trace"$output"
        }
    } yield pair

  def withLoggingReturn(
    res: Resource[IO, process.Process[IO]]
  )(implicit l: Logger[IO]) =
    for {
      pair <- res.use(x => getText(x).product(getError(x)))
      (output, error) = pair
      _ <-
        if (error.trim().nonEmpty) {
          error"$error"
        } else {
          info"$output"
        }
    } yield output

  def withTracingReturn(
    res: Resource[IO, process.Process[IO]]
  )(implicit l: Logger[IO]) =
    for {
      pair <- res.use(x => getText(x).product(getError(x)))
      (output, error) = pair
      _ <-
        if (error.trim().nonEmpty) {
          error"$error"
        } else {
          trace"$output"
        }
    } yield output

  def mintPlasmaBlock(node: Int, nbBlocks: Int)(implicit l: Logger[IO]) =
    withLogging(mintBlockP(node, nbBlocks))

  def mintPlasmaBlockDocker(node: Int, nbBlocks: Int)(implicit l: Logger[IO]) =
    withLogging(mintBlockDockerP(node, nbBlocks))

  def initPlasmaWallet(id: Int)(implicit l: Logger[IO]) =
    withLogging(initUserWalletP(id))

  def pwd(implicit l: Logger[IO]) =
    withLogging(pwdP)

  def addSecret(id: Int)(implicit l: Logger[IO]) =
    for {
      secretResponse <- withLoggingReturn(addSecretP(id))
      secret = secretResponse.takeRight(64)
    } yield secret

  def createTx(txId: String, address: String, amount: BigDecimal)(implicit
    l: Logger[IO]
  ) =
    withLoggingReturn(createTxP(txId, address, amount))

  def getTxConfirmationsAndBlockHeight(id: Int, txId: String)(implicit
    l: Logger[IO]
  ): IO[(Int, Int)] = for {
    tx <- withTracingReturn(getTxP(id, txId))
  } yield (extractTxConfirmations(tx), extractBlockHeight(tx))

  def getBitcoinBlockheight(implicit
    l: Logger[IO]
  ): IO[String] = for {
    height <- withTracingReturn(getBlockheightP)
  } yield height

  def initUserBitcoinWallet(implicit l: Logger[IO]) =
    withLogging(initUserBitcoinWalletP)

  def initUserBitcoinWallet(walletName: String)(implicit l: Logger[IO]) =
    withLogging(initUserBitcoinWalletP(walletName))

  def getNewAddress(implicit l: Logger[IO]) =
    withLoggingReturn(getNewaddressP)

  def getNewAddress(walletName: String)(implicit l: Logger[IO]) =
    withLoggingReturn(getNewaddressP(walletName))

  def generateToAddress(nodeId: Int, blocks: Int, address: String, walletName: String = "testwallet")(implicit
    l: Logger[IO]
  ) =
    withTrace(generateToAddressP(nodeId, blocks, address, walletName))

  def addTemplate(id: Int, sha256: String, min: Long, max: Long)(implicit
    l: Logger[IO]
  ) =
    withLogging(addTemplateP(id, sha256, min, max))

  def importVks(userId: Int)(implicit l: Logger[IO]) =
    withLogging(importVksP(userId))

  def fundRedeemAddressTx(userId: Int, redeemAddress: String)(implicit
    l: Logger[IO]
  ) =
    withLogging(fundRedeemAddressTxP(userId, redeemAddress))

  def proveFundRedeemAddressTx(
    id: Int
  )(implicit
    l: Logger[IO]
  ) =
    withLogging(proveFundRedeemAddressTxP(id, userFundRedeemTx(id), userFundRedeemTxProved((id))))

  def proveRedeemAddressTx(
    id: Int
  )(implicit
    l: Logger[IO]
  ) =
    withLogging(proveFundRedeemAddressTxP(id, userRedeemTx(id), userRedeemTxProved(id)))

  def broadcastFundRedeemAddressTx(file: String)(implicit l: Logger[IO]) =
    withLoggingReturn(broadcastFundRedeemAddressTxP(file))

  def currentAddress(id: Int)(implicit l: Logger[IO]) =
    withLoggingReturn(currentAddressP(id))

  def currentAddress(file: String)(implicit l: Logger[IO]) =
    withLoggingReturn(currentAddressP(file))

  def getCurrentUtxosFromAddress(id: Int, address: String)(implicit
    l: Logger[IO]
  ) = for {
    utxo <- withLoggingReturn(getCurrentUtxosFromAddressP(id, address))
  } yield utxo

  def getCurrentUtxosFromAddress(file: String, address: String)(implicit
    l: Logger[IO]
  ) = for {
    utxo <- withTracingReturn(getCurrentUtxosFromAddressP(file, address))
  } yield utxo

  def redeemAddressTx(
    id:            Int,
    redeemAddress: String,
    amount:        Long,
    groupId:       String,
    seriesId:      String
  )(implicit l: Logger[IO]) =
    withLogging(redeemAddressTxP(id, redeemAddress, amount, groupId, seriesId))

  def extractTxConfirmations(tx: String) =
    tx
      .split("\n")
      .filter(_.contains("confirmations"))
      .head
      .split(":")
      .last
      .trim()
      .replaceAll(",", "")
      .toInt

  def extractBlockHeight(tx: String) =
    tx
      .split("\n")
      .filter(_.contains("blockheight"))
      .head
      .split(":")
      .last
      .trim()
      .replaceAll(",", "")
      .toInt

  def extractGroupId(utxo: String) =
    utxo
      .split("\n")
      .filter(_.contains("GroupId"))
      .head
      .split(":")
      .last
      .trim()

  def extractSeriesId(utxo: String) =
    utxo
      .split("\n")
      .filter(_.contains("SeriesId"))
      .head
      .split(":")
      .last
      .trim()

  def extractIpBtc(id: Int, bridgeNetwork: String) = IO.fromEither(
    parse(bridgeNetwork)
      .map(x =>
        (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
          x.filter(x => (x._2 \\ "Name").head.asString.get == "bitcoin" + f"${id}%02d").values.head
        }).get \\ "IPv4Address").head.asString.get
          .split("/")
          .head
      )
  )

  def extractIpNode(id: Int, bridgeNetwork: String) = IO.fromEither(
    parse(bridgeNetwork)
      .map(x =>
        (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
          x.filter(x => (x._2 \\ "Name").head.asString.get == "node" + f"${id}%02d").values.head
        }).get \\ "IPv4Address").head.asString.get
          .split("/")
          .head
      )
  )

  def startSession(sha256: String = "60cd434b2fd6d22cec4cf3c9b16d3f57de4bf4d0bd0da1b16659a76ec7736610") =
    EmberClientBuilder
      .default[IO]
      .build
      .use({ client =>
        client.expect[StartPeginSessionResponse](
          Request[IO](
            method = Method.POST,
            Uri
              .fromString(
                "http://127.0.0.1:5000/api/" + BridgeContants.START_PEGIN_SESSION_PATH
              )
              .toOption
              .get
          ).withContentType(
            `Content-Type`.apply(MediaType.application.json)
          ).withEntity(
            StartPeginSessionRequest(
              pkey = "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a",
              sha256
            )
          )
        )
      })

  def checkMintingStatus(sessionId: String)(implicit l: Logger[IO]) =
    EmberClientBuilder
      .default[IO]
      .build
      .use({ client =>
        client
          .expect[MintingStatusResponse](
            Request[IO](
              method = Method.POST,
              Uri
                .fromString(
                  "http://127.0.0.1:5000/api/" + BridgeContants.PLASMA_MINTING_STATUS
                )
                .toOption
                .get
            ).withContentType(
              `Content-Type`.apply(MediaType.application.json)
            ).withEntity(
              MintingStatusRequest(sessionId)
            )
          )
          .handleErrorWith { e =>
            error"Error getting status response" >> IO(
              e.printStackTrace()
            ) >> IO.raiseError(
              e
            )
          }
      })

  def checkStatus(sessionId: String)(implicit l: Logger[IO]) = EmberClientBuilder
    .default[IO]
    .build
    .use({ client =>
      client
        .status(
          Request[IO](
            method = Method.POST,
            Uri
              .fromString(
                "http://127.0.0.1:5000/api/" + BridgeContants.PLASMA_MINTING_STATUS
              )
              .toOption
              .get
          ).withContentType(
            `Content-Type`.apply(MediaType.application.json)
          ).withEntity(
            MintingStatusRequest(sessionId)
          )
        )
        .handleErrorWith { e =>
          error"Error getting status code" >> IO(
            e.printStackTrace()
          ) >> IO.raiseError(
            e
          )
        }
    })

  def extractGetTxIdAndAmount(implicit l: Logger[IO]) = for {
    unxpentTx <- withTracingReturn(extractGetTxIdP)
    txId <- IO.fromEither(
      parse(unxpentTx).map(x => (x \\ "txid").head.asString.get)
    )
    btcAmount <- IO.fromEither(
      parse(unxpentTx).map(x => (x \\ "amount").head.asNumber.get)
    )
  } yield (
    txId,
    btcAmount.toBigDecimal.get - BigDecimal("0.01"), // why is this subtracted here
    ((btcAmount.toBigDecimal.get - BigDecimal("0.01")) * 100000000L).toLong
  )

  def extractGetTxIdAndAmount(walletName: String)(implicit l: Logger[IO]) = for {
    unxpentTx <- withTracingReturn(extractGetTxIdP(walletName))
    txId <- IO.fromEither(
      parse(unxpentTx).map(x => (x \\ "txid").head.asString.get)
    )
    btcAmount <- IO.fromEither(
      parse(unxpentTx).map(x => (x \\ "amount").head.asNumber.get)
    )
  } yield (
    txId,
    btcAmount.toBigDecimal.get - BigDecimal("0.01"),
    ((btcAmount.toBigDecimal.get - BigDecimal("0.01")) * 100000000L).toLong
  )

  def createVkFile(vkFile: String) = fs2.io
    .readInputStream[IO](
      IO(
        new ByteArrayInputStream(
          "".getBytes()
        )
      ),
      10
    )
    .through(Files[IO].writeAll(fs2.io.file.Path(vkFile)))
    .compile
    .drain

  def parseInput(input: String): List[InputData] = {
    val blocks = input.split("\n\n").toList // Split input into blocks
    blocks.map { block =>
      val lines = block.split("\n").map(_.trim).toList
      val dataMap = lines.map { line =>
        val Array(key, value) = line.split(":", 2).map(_.trim)
        key -> value
      }.toMap

      InputData(
        LockAddress = dataMap("LockAddress"),
        Type = dataMap("Type"),
        Id = dataMap.get("Id"),
        Fungibility = dataMap.get("Fungibility"),
        TokenSupply = dataMap.get("Token-Supply"),
        QuantDescr = dataMap.get("Quant-Descr."),
        Value = dataMap("Value").toInt,
        TxoAddress = dataMap.get("TxoAddress"),
        FixedSeries = dataMap.get("Fixed-Series")
      )
    }
  }

  def extractIds(input: String): (String, String) = {
    val dataList = parseInput(input)
    val seriesConstructorId =
      dataList.filter(_.Type == "Series Constructor").flatMap(_.Id)
    val groupConstructorId =
      dataList.filter(_.Type == "Group Constructor").flatMap(_.Id)

    (groupConstructorId.mkString, seriesConstructorId.mkString)
  }

  object implicits {

    implicit val startSessionRequestDecoder: EntityEncoder[IO, StartPeginSessionRequest] =
      jsonEncoderOf[IO, StartPeginSessionRequest]

    implicit val syncWalletRequestDecoder: EntityEncoder[IO, SyncWalletRequest] =
      jsonEncoderOf[IO, SyncWalletRequest]

    implicit val mintingStatusRequesEncoder: EntityEncoder[IO, MintingStatusRequest] =
      jsonEncoderOf[IO, MintingStatusRequest]

    implicit val startSessionResponse: EntityDecoder[IO, StartPeginSessionResponse] =
      jsonOf[IO, StartPeginSessionResponse]

    implicit val MintingStatusResponseDecoder: EntityDecoder[IO, MintingStatusResponse] =
      jsonOf[IO, MintingStatusResponse]

  }

  val CS_CMD = Option(System.getenv("CI"))
    .map(_ => s"${System.getenv("""GITHUB_WORKSPACE""")}/cs")
    .getOrElse("cs")

  val csParams = Seq(
    "launch",
    "-r",
    "https://s01.oss.sonatype.org/content/repositories/releases",
    "org.plasmalabs:plasma-cli_2.13:0.1.2",
    "--"
  )

  def userSecret(id: Int) = "user-secret" + f"$id%02d"

  def userBitcoinWallet(id: Int) = "bitcoin-wallet" + f"$id%02d"

  def userWalletDb(id: Int) = "user-wallet" + f"$id%02d" + ".db"

  def userWalletMnemonic(id: Int) = "user-wallet-mnemonic" + f"$id%02d" + ".txt"

  def userWalletJson(id: Int) = "user-wallet" + f"$id%02d" + ".json"

  def userVkFile(id: Int) = "key" + f"$id%02d" + ".txt"

  def userRedeemTx(id: Int) = "redeemTx" + f"$id%02d" + ".pbuf"

  def userRedeemTxProved(id: Int) = "redeemTxProved" + f"$id%02d" + ".pbuf"

  def userFundRedeemTx(id: Int) = "fundRedeemTx" + f"$id%02d" + ".pbuf"

  def userFundRedeemTxProved(id: Int) = "fundRedeemTxProved" + f"$id%02d" + ".pbuf"

  // plasma-cli wallet init --network private --password password --newwalletdb user-wallet.db --mnemonicfile user-wallet-mnemonic.txt --output user-wallet.json

  def getCurrentUtxosFromAddressP(id: Int, address: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "indexer-query",
        "utxo-by-address",
        "--host",
        "localhost",
        "--port",
        "9084",
        "--secure",
        "false",
        "--walletdb",
        userWalletDb(id),
        "--from-address",
        address
      ): _*
    )
    .spawn[IO]

  def getCurrentUtxosFromAddressP(file: String, address: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "indexer-query",
        "utxo-by-address",
        "--host",
        "localhost",
        "--port",
        "9084",
        "--secure",
        "false",
        "--walletdb",
        file,
        "--from-address",
        address
      ): _*
    )
    .spawn[IO]

  def templateFromSha(sha256: String, min: Long, max: Long) =
    s"""threshold(1, sha256($sha256) and height($min, $max))"""

  val nodeHostMap =
    Map(1 -> "localhost", 2 -> "localhost")

  val nodePortMap =
    Map(1 -> 9084, 2 -> 9086)

  // plasma-cli templates add --walletdb user-wallet.db --template-name redeemBridge --lock-template
  def addTemplateP(id: Int, sha256: String, min: Long, max: Long) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "templates",
        "add",
        "--walletdb",
        userWalletDb(id),
        "--template-name",
        "redeemBridge" + f"$id%02d",
        "--lock-template",
        templateFromSha(sha256, min, max)
      ): _*
    )
    .spawn[IO]

  // plasma-cli wallet import-vks --walletdb user-wallet.db --input-vks key.txt --fellowship-name bridge --template-name redeemBridge -w password -k user-wallet.json
  def importVksP(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "import-vks",
        "--walletdb",
        userWalletDb(id),
        "--input-vks",
        userVkFile(id),
        "--fellowship-name",
        "bridge",
        "--template-name",
        "redeemBridge" + f"$id%02d",
        "-w",
        "password",
        "-k",
        userWalletJson(id)
      ): _*
    )
    .spawn[IO]

  // plasma-cli wallet current-address --walletdb user-wallet.db
  def currentAddressP(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "current-address",
        "--walletdb",
        userWalletDb(id)
      ): _*
    )
    .spawn[IO]

  def currentAddressP(file: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "current-address",
        "--walletdb",
        file
      ): _*
    )
    .spawn[IO]

  // plasma-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 -t ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p -w password -o genesisTx.pbuf -n private -a 10 -h  localhost --port 9084  --keyfile user-keyfile.json --walletdb user-wallet.db --fee 10 --transfer-token lvl
  def fundRedeemAddressTxP(id: Int, redeemAddress: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "simple-transaction",
        "create",
        "--from-fellowship",
        "nofellowship",
        "--from-template",
        "genesis",
        "--from-interaction",
        "1",
        "--change-fellowship",
        "nofellowship",
        "--change-template",
        "genesis",
        "--change-interaction",
        "1",
        "-t",
        redeemAddress,
        "-w",
        "password",
        "-o",
        userFundRedeemTx(id),
        "-n",
        "private",
        "-a",
        "10",
        "-h",
        "localhost",
        "--port",
        "9084",
        "--keyfile",
        userWalletJson(id),
        "--walletdb",
        userWalletDb(id),
        "--fee",
        "10",
        "--transfer-token",
        "lvl"
      ): _*
    )
    .spawn[IO]

  // plasma-cli simple-transaction create --from-fellowship bridge --from-template redeemBridge -t ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p -w password -o redeemTx.pbuf -n private -a 10 -h  localhost --port 9084  --keyfile user-keyfile.json --walletdb user-wallet.db --fee 10 --transfer-token asset
  def redeemAddressTxP(
    id:            Int,
    redeemAddress: String,
    amount:        Long,
    groupId:       String,
    seriesId:      String
  ) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "simple-transaction",
        "create",
        "--from-fellowship",
        "bridge",
        "--from-template",
        "redeemBridge" + f"$id%02d",
        "-t",
        redeemAddress,
        "-w",
        "password",
        "-o",
        userRedeemTx(id),
        "-n",
        "private",
        "-a",
        amount.toString(),
        "-h",
        "localhost",
        "--port",
        "9084",
        "--keyfile",
        userWalletJson(id),
        "--walletdb",
        userWalletDb(id),
        "--fee",
        "10",
        "--transfer-token",
        "asset",
        "--group-id",
        groupId,
        "--series-id",
        seriesId
      ): _*
    )
    .spawn[IO]

  // plasma-cli tx prove -i fundRedeemTx.pbuf --walletdb user-wallet.db --keyfile user-keyfile.json -w password -o fundRedeemTxProved.pbuf
  def proveFundRedeemAddressTxP(
    id:          Int,
    fileToProve: String,
    provedFile:  String
  ) =
    process
      .ProcessBuilder(
        CS_CMD,
        csParams ++ Seq(
          "tx",
          "prove",
          "-i",
          fileToProve,
          "--walletdb",
          userWalletDb(id),
          "--keyfile",
          userWalletJson(id),
          "-w",
          "password",
          "-o",
          provedFile
        ): _*
      )
      .spawn[IO]

  // plasma-cli tx broadcast -i fundRedeemTxProved.pbuf -h localhost --port 9084
  def broadcastFundRedeemAddressTxP(file: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "tx",
        "broadcast",
        "-i",
        file,
        "-h",
        "localhost",
        "--port",
        "9084"
      ): _*
    )
    .spawn[IO]

  def forceConnection(id: Int, ip: String, port: Int)(implicit l: Logger[IO]) =
    withLogging(forceConnectionP(id, ip, port))

  def setNetworkActive(nodeId: Int, state: Boolean)(implicit l: Logger[IO]) =
    withLogging(setNetworkActiveP(nodeId, state))

  // exec bitcoin01 bitcoin-cli -regtest -rpcuser=bitcoin -rpcpassword=password addnode <ip>:<port> add
  def addNode(nodeId: Int, ip: String, port: Int) = Seq(
    "exec",
    "bitcoin" + f"${nodeId}%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "addnode",
    s"$ip:$port",
    "add"
  )

  // network inspect bridge
  def inspectBridge(networkName: String) =
    Seq("network", "inspect", networkName)

  // docker network ls
  val networkLs = Seq("network", "ls")

  // docker network disconnect bridge bitcoin02
  def disconnectBridgeSeq(networkName: String, containerName: String) =
    Seq("network", "disconnect", networkName, containerName)

  def connectBridgeSeq(networkName: String, containerName: String) =
    Seq("network", "connect", networkName, containerName)

  def disconnectBridgeP(networkName: String, containerName: String) =
    process
      .ProcessBuilder(
        DOCKER_CMD,
        disconnectBridgeSeq(networkName, containerName): _*
      )
      .spawn[IO]

  def connectBridgeP(networkName: String, containerName: String) =
    process
      .ProcessBuilder(
        DOCKER_CMD,
        connectBridgeSeq(networkName, containerName): _*
      )
      .spawn[IO]

  def disconnectBridge(networkName: String, containerName: String)(implicit
    l: Logger[IO]
  ) =
    withLogging(disconnectBridgeP(networkName, containerName))

  def connectBridge(networkName: String, containerName: String)(implicit
    l: Logger[IO]
  ) =
    withLogging(connectBridgeP(networkName, containerName))

  def sendTransactionSeq(signedTx: String) = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "sendrawtransaction",
    signedTx
  )

  def createTxSeq(txId: String, address: String, amount: BigDecimal) = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-tx",
    "-regtest",
    "-create",
    s"in=$txId:0",
    s"outaddr=$amount:$address"
  )

  def getTxSeq(id: Int, txId: String) = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    s"-rpcwallet=${userBitcoinWallet(id)}",
    "gettransaction",
    txId
  )

  def getBlockHeightSeq = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-rpcwallet=testwallet",
    "getblockcount"
  )

  def getText(p: fs2.io.process.Process[IO]): IO[String] =
    p.stdout
      .through(fs2.text.utf8.decode)
      .compile
      .foldMonoid
      .map(_.trim)

  def getError(p: fs2.io.process.Process[IO]) =
    p.stderr
      .through(fs2.text.utf8.decode)
      .compile
      .foldMonoid

  def addFellowship(id: Int)(implicit l: Logger[IO]) =
    withLogging(addFellowshipP(id))

  def signTransaction(tx: String)(implicit l: Logger[IO]) =
    for {
      signedTx <- withLoggingReturn(signTransactionP(tx))
      signedTxHex <- IO.fromEither(
        parse(signedTx).map(x => (x \\ "hex").head.asString.get)
      )
    } yield signedTxHex

  def signTransaction(tx: String, walletName: String)(implicit l: Logger[IO]) =
    for {
      signedTx <- withLoggingReturn(signTransactionP(tx, walletName))
      signedTxHex <- IO.fromEither(
        parse(signedTx).map(x => (x \\ "hex").head.asString.get)
      )
    } yield signedTxHex

  def sendTransaction(signedTx: String)(implicit l: Logger[IO]) =
    withLoggingReturn(sendTransactionP(signedTx))

}
