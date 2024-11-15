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
    val monocleVersion = "3.1.0"
    val plasmaVersion = "0.2.1"
    val ioGrpcVersion = "1.68.1"
  }

  val akkaSlf4j: Seq[ModuleID] = Seq(
    "org.apache.pekko" %% "pekko-actor-typed" % "1.0.3"
  )

  val logback: Seq[ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % "1.4.14"
  )

  val slf4j: Seq[ModuleID] = Seq(
    "org.slf4j" % "slf4j-api" % slf4jVersion
  )

  val log4cats: Seq[ModuleID] = Seq(
    "org.typelevel" %% "log4cats-core"  % "2.7.0",
    "org.typelevel" %% "log4cats-slf4j" % "2.7.0"
  )

  val bouncycastle: Seq[ModuleID] = Seq(
    "org.bouncycastle" % "bcprov-jdk15on" % "1.68",
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.68"
  )

  val plasma: Seq[ModuleID] = Seq(
    "org.plasmalabs" %% "plasma-sdk"  % plasmaVersion,
    "org.plasmalabs" %% "crypto"      % plasmaVersion,
    "org.plasmalabs" %% "service-kit" % plasmaVersion
  )

  val mUnit: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit"                   % "1.0.2",
    "org.scalameta" %% "munit-scalacheck"        % "1.0.0",
    "org.typelevel" %% "munit-cats-effect"       % "2.0.0",
    "org.typelevel" %% "scalacheck-effect-munit" % "1.0.4"
  )

  val sqlite: Seq[ModuleID] = Seq(
    "org.xerial" % "sqlite-jdbc" % "3.45.3.0"
  )

  val ip4score: Seq[ModuleID] = Seq(
    "com.comcast" %% "ip4s-core" % "3.6.0"
  )

  val cats: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-core"   % catsCoreVersion,
    "org.typelevel" %% "cats-effect" % "3.5.1"
  )

  val grpcNetty: Seq[ModuleID] =
    Seq("io.grpc" % "grpc-netty-shaded" % ioGrpcVersion)

  val grpcRuntime: Seq[ModuleID] =
    Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )

  val scopt: Seq[ModuleID] = Seq("com.github.scopt" %% "scopt" % "4.1.0")

  val http4s: Seq[ModuleID] = Seq(
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-dsl"          % http4sVersion,
    "org.http4s" %% "http4s-circe"        % http4sVersion,
    "org.http4s" %% "http4s-ember-server" % http4sVersion
  )

  val bitcoinS: Seq[ModuleID] = Seq(
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

  val genericCirce: Seq[ModuleID] = Seq(
    "io.circe" %% "circe-generic" % "0.14.10"
  )

  val optics: Seq[ModuleID] = Seq(
    "dev.optics" %% "monocle-core"  % monocleVersion,
    "dev.optics" %% "monocle-macro" % monocleVersion
  )

  val config: Seq[ModuleID] = Seq(
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
      akkaSlf4j

    val publicApi: Seq[ModuleID] =
      scopt ++
      ip4score ++
      cats ++
      log4cats ++
      http4s ++
      optics ++
      grpcNetty ++
      grpcRuntime ++
      slf4j ++
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
