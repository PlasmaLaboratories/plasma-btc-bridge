import Dependencies.Versions._
import sbt._

object Dependencies {

  object Versions {
    val catsCoreVersion = "2.12.0"
    val http4sVersion = "0.23.29"
    val slf4jVersion = "2.0.16"
    val mUnitTeVersion = "0.7.29"
    val bitcoinsVersion = "1.9.9"
    val btcVersionZmq = "1.9.8"
    val monocleVersion = "3.3.0"
    val plasmaVersion = "0.2.1"
    val ioGrpcVersion = "1.68.1"
  }

  private val pekkoActorTyped = Seq(
    "org.apache.pekko" %% "pekko-actor-typed" % "1.0.2"
  )

  private val logback = Seq(
    "ch.qos.logback" % "logback-classic" % "1.5.12"
  )

  private val log4cats = Seq(
    "org.typelevel" %% "log4cats-slf4j" % "2.7.0",
    "org.slf4j"      % "slf4j-api"      % slf4jVersion
  )

  private val bouncycastle = Seq(
    "org.bouncycastle" % "bcprov-jdk15on" % "1.68",
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.68"
  )

  private val plasma = Seq(
    "org.plasmalabs" %% "plasma-sdk"  % plasmaVersion,
    "org.plasmalabs" %% "crypto"      % plasmaVersion,
    "org.plasmalabs" %% "service-kit" % plasmaVersion
  )

  private val mUnit = Seq(
    "org.scalameta" %% "munit"                   % "1.0.2",
    "org.scalameta" %% "munit-scalacheck"        % "1.0.0",
    "org.typelevel" %% "munit-cats-effect"       % "2.0.0",
    "org.typelevel" %% "scalacheck-effect-munit" % "1.0.4"
  )

  private val sqlite = Seq(
    "org.xerial" % "sqlite-jdbc" % "3.47.0.0"
  )

  private val ip4score = Seq(
    "com.comcast" %% "ip4s-core" % "3.6.0"
  )

  private val cats = Seq(
    "org.typelevel" %% "cats-core"   % catsCoreVersion,
    "org.typelevel" %% "cats-effect" % "3.5.6"
  )

  private val grpcNetty =
    Seq("io.grpc" % "grpc-netty-shaded" % ioGrpcVersion)

  private val grpcRuntime =
    Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )

  private val scopt = Seq("com.github.scopt" %% "scopt" % "4.1.0")

  private val http4s = Seq(
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-dsl"          % http4sVersion,
    "org.http4s" %% "http4s-circe"        % http4sVersion,
    "org.http4s" %% "http4s-ember-server" % http4sVersion
  )

  private val bitcoinS = Seq(
    "org.bitcoin-s" %% "bitcoin-s-bitcoind-rpc" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-core"         % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-chain"        % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-dlc-oracle"   % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-eclair-rpc"   % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-fee-provider" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-key-manager"  % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-lnd-rpc"      % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-node"         % bitcoinsVersion,
    "org.bitcoin-s"  % "bitcoin-s-secp256k1jni" % bitcoinsVersion,
    "org.bitcoin-s" %% "bitcoin-s-wallet"       % btcVersionZmq,
    "org.bitcoin-s" %% "bitcoin-s-zmq"          % btcVersionZmq
  )

  private val genericCirce = Seq(
    "io.circe" %% "circe-generic" % "0.14.10"
  )

  private val optics = Seq(
    "dev.optics" %% "monocle-core"  % monocleVersion,
    "dev.optics" %% "monocle-macro" % monocleVersion
  )

  private val config = Seq(
    "com.typesafe" % "config" % "1.4.3"
  )

  object plasmaBtcBridge {

    val consensus: Seq[ModuleID] =
      plasma ++
      scopt ++
      cats ++
      log4cats ++
      http4s ++
      optics ++
      bitcoinS ++
      grpcNetty ++
      grpcRuntime ++
      sqlite ++
      pekkoActorTyped

    val publicApi: Seq[ModuleID] =
      scopt ++
      ip4score ++
      cats ++
      log4cats ++
      http4s ++
      optics ++
      grpcNetty ++
      grpcRuntime ++
      config ++
      logback ++
      genericCirce

    val shared: Seq[ModuleID] =
      grpcNetty ++
      log4cats ++
      cats ++
      grpcRuntime ++
      bouncycastle

    val test: Seq[ModuleID] = mUnit.map(_ % Test)
  }

  object plasmaBtcCli {

    val main: Seq[ModuleID] =
      plasma ++
      scopt ++
      cats ++
      log4cats ++
      logback ++
      http4s ++
      bitcoinS

    val test: Seq[ModuleID] = mUnit.map(_ % Test)

  }

  object IntegrationTests {
    val sources: Seq[ModuleID] = plasmaBtcBridge.consensus
    val tests: Seq[ModuleID] = (sources ++ mUnit).map(_ % Test)
  }
}
