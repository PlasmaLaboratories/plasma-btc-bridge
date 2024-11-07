import sbt._

object Dependencies {

  val catsCoreVersion = "2.10.0"

  lazy val http4sVersion = "0.23.23"

  lazy val slf4jVersion = "2.0.12"
  lazy val mUnitTeVersion = "0.7.29"

  val akkaSlf4j: Seq[ModuleID] = Seq(
    "org.apache.pekko" %% "pekko-actor-typed" % "1.0.2"
  )

  val logback: Seq[ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % "1.4.11"
  )

  lazy val slf4j: Seq[ModuleID] = Seq(
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

  lazy val plasmaOrg = "org.plasmalabs"

  lazy val plasmaVersion = "0.1.0"

  val plasmaSdk = plasmaOrg %% "plasma-sdk" % plasmaVersion

  val plasmaCrypto = plasmaOrg %% "crypto" % plasmaVersion

  val plasmaServiceKit = plasmaOrg %% "service-kit" % plasmaVersion

  val plasma: Seq[ModuleID] = Seq(plasmaSdk, plasmaCrypto, plasmaServiceKit)

  lazy val bitcoinsVersion = "1.9.9"

  lazy val btcVersionZmq = "1.9.8"

  lazy val monocleVersion = "3.1.0"

  lazy val munit: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit" % "1.0.0-M10"
  )

  lazy val mUnitTest: Seq[ModuleID] = Seq(
    "org.scalameta" %% "munit"                   % mUnitTeVersion,
    "org.scalameta" %% "munit-scalacheck"        % mUnitTeVersion,
    "org.typelevel" %% "munit-cats-effect-3"     % "1.0.7",
    "org.typelevel" %% "scalacheck-effect-munit" % "1.0.4"
  )

  val sqlite: Seq[ModuleID] = Seq(
    "org.xerial" % "sqlite-jdbc" % "3.45.2.0"
  )

  lazy val ip4score: Seq[ModuleID] = Seq(
    "com.comcast" %% "ip4s-core" % "3.6.0"
  )

  lazy val munitCatsEffects: Seq[ModuleID] = Seq(
    "org.typelevel" %% "munit-cats-effect" % "2.0.0-M4"
  )

  val cats: Seq[ModuleID] = Seq(
    "org.typelevel" %% "cats-core"   % catsCoreVersion,
    "org.typelevel" %% "cats-effect" % "3.5.1"
  )

  val grpcNetty =
    Seq("io.grpc" % "grpc-netty-shaded" % "1.62.2")

  val grpcRuntime =
    Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )

  lazy val scopt: Seq[ModuleID] = Seq("com.github.scopt" %% "scopt" % "4.0.1")

  lazy val http4s: Seq[ModuleID] = Seq(
    "org.http4s" %% "http4s-ember-client" % http4sVersion,
    "org.http4s" %% "http4s-dsl"          % http4sVersion,
    "org.http4s" %% "http4s-circe"        % http4sVersion,
    "org.http4s" %% "http4s-ember-server" % http4sVersion
  )

  lazy val bitcoinS: Seq[ModuleID] = Seq(
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

  lazy val genericCirce: Seq[ModuleID] = Seq(
    "io.circe" %% "circe-generic" % "0.14.9"
  )

  lazy val optics: Seq[ModuleID] = Seq(
    "dev.optics" %% "monocle-core"  % monocleVersion,
    "dev.optics" %% "monocle-macro" % monocleVersion
  )

  lazy val config: Seq[ModuleID] = Seq(
    "com.typesafe" % "config" % "1.4.3"
  )

  object plasmaBtcBridge {

    lazy val consensus: Seq[ModuleID] =
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

    lazy val publicApi: Seq[ModuleID] =
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

    lazy val shared: Seq[ModuleID] =
      grpcNetty ++
      log4cats ++
      cats ++
      grpcRuntime ++
      bouncycastle

    lazy val test: Seq[ModuleID] =
      (
        munit ++ munitCatsEffects
      )
        .map(_ % Test)
  }

  object plasmaBtcCli {

    lazy val main: Seq[ModuleID] =
      plasma ++
      scopt ++
      cats ++
      log4cats ++
      logback ++
      http4s ++
      bitcoinS

    lazy val test: Seq[ModuleID] =
      (
        munit ++ munitCatsEffects
      )
        .map(_ % Test)
  }

  object IntegrationTests {
    lazy val sources: Seq[ModuleID] = plasmaBtcBridge.consensus
    lazy val tests: Seq[ModuleID] = (sources ++ mUnitTest).map(_ % Test)
  }
}
