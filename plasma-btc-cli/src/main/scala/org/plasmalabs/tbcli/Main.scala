package org.plasmalabs.tbcli

import cats.effect.{ExitCode, IO, IOApp}

object Main extends IOApp with TBCLIParamsDescriptor {

  override def run(args: List[String]): IO[ExitCode] =
    // OParser.parse(parser, args, StrataBTCCLIParamConfig()) match {
    //   case Some(config) =>
    //     config.command match {
    //       case Some(command) =>
    //         processCommand(config.btcNetwork, command)
    //       case None =>
    //         IO.consoleForIO.errorln("No command specified") *>
    //           IO(ExitCode.Error)
    //     }
    //   case None =>
    IO.consoleForIO.errorln("Invalid arguments") *>
    IO(ExitCode.Error)
  // }

  // def processCommand(
  //     network: BitcoinNetworkIdentifiers,
  //     command: StrataBTCCLICommand
  // ): IO[ExitCode] = command match {
  //   case e: InitSession =>
  //     for {
  //       res <- processInitSession[IO](network, e)
  //       _ <- displayInitSession[IO](res)
  //     } yield ExitCode.Success
  // }

  // def displayInitSession[F[_]: std.Console](
  //     startSessionRequest: StartPeginSessionRequest
  // ): F[Unit] = {
  //   import cats.implicits._
  //   import org.plasmalabs.tbcli.view.OutputView._
  //   std.Console[F].println(startSessionRequest.show)
  // }

  // def processInitSession[F[_]: Sync](
  //     btcNetwork: BitcoinNetworkIdentifiers,
  //     initSession: InitSession
  // ): F[StartPeginSessionRequest] = {
  //   import cats.implicits._
  //   for {
  //     secretSha256 <- Sync[F].delay(
  //       MessageDigest
  //         .getInstance("SHA-256")
  //         .digest(initSession.secret.getBytes("UTF-8"))
  //         .map("%02x".format(_))
  //         .mkString
  //     )

  //     km <- KeyGenerationUtils
  //       .createKeyManager[F](
  //         btcNetwork,
  //         initSession.seedFile,
  //         initSession.password
  //       )
  //     pKey <- KeyGenerationUtils.generateKey[F](km, 1)
  //   } yield StartPeginSessionRequest(pKey.hex, secretSha256)
  // }
}
