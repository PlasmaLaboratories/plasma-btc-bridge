package org.plasmalabs.tbcli

// import org.plasmalabs.shared.BitcoinNetworkIdentifiers
// import scopt.OParser

trait TBCLIParamsDescriptor {
  // import org.plasmalabs.bridge.ParamParser._

  // val builder = OParser.builder[PlasmaBTCCLIParamConfig]

  // val parser = {
  //   import builder._
  //   import monocle.Optional
  //   val someCommand = Optional[PlasmaBTCCLIParamConfig, PlasmaBTCCLICommand] {
  //     _.command
  //   } { command =>
  //     _.copy(command = Some(command))
  //   }
  //   import monocle.macros.GenPrism
  //   val initSession = GenPrism[PlasmaBTCCLICommand, InitSession]
  //   import monocle.macros.GenLens
  //   val initSessionLens = someCommand.andThen(initSession)
  //   OParser.sequence(
  //     programName("tbcli"),
  //     head("tbcli", "0.1"),
  //     // opt[BitcoinNetworkIdentifiers]('n', "network")
  //     //   .action((x, c) => c.copy(btcNetwork = x))
  //     //   .text(
  //     //     "Network name: Possible values: mainnet, testnet, regtest. (mandatory)"
  //     //   ),
  //     cmd("init-session")
  //       .action((_, c) => someCommand.replace(InitSession())(c))
  //       .text("Initialize a new session")
  //       .children(
  //         opt[String]("btc-wallet-seed-file")
  //           .action((x, c) => {
  //             val seedFile = GenLens[InitSession](_.seedFile)
  //             initSessionLens
  //               .andThen(seedFile)
  //               .replace(x.trim())(c)
  //           })
  //           .validate(x =>
  //             if (!x.trim().isEmpty())
  //               if (new java.io.File(x.trim()).exists())
  //                 failure("Seed file already exists")
  //               else
  //                 success
  //             else failure("Seed file cannot be empty")
  //           )
  //           .text("Path to the seed file")
  //           .required(),
  //         opt[String]("secret")
  //           .action((x, c) => {
  //             val secret = GenLens[InitSession](_.secret)
  //             initSessionLens
  //               .andThen(secret)
  //               .replace(x.trim())(c)
  //           })
  //           .validate(x =>
  //             if (x.trim().isEmpty()) failure("Secret cannot be empty")
  //             else success
  //           )
  //           .text("Secret to initialize the session with"),
  //         opt[String]("password")
  //           .action((x, c) => {
  //             val password = GenLens[InitSession](_.password)
  //             initSessionLens
  //               .andThen(password)
  //               .replace(x.trim())(c)
  //           })
  //           .validate(x =>
  //             if (x.trim().isEmpty()) failure("Password cannot be empty")
  //             else success
  //           )
  //           .text("Secret to initialize the session with")
  //           .required()
  //       )
  //   )
  // }
}
