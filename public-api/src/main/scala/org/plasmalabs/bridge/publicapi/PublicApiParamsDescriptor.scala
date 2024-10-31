package org.plasmalabs.bridge.publicapi

import scopt.OParser

import java.io.File

trait PublicApiParamsDescriptor {

  val builder = OParser.builder[StrataBTCBridgePublicApiParamConfig]

  val parser = {
    import builder._

    OParser.sequence(
      programName("plasma-btc-bridge-public-api"),
      head("plasma-btc-bridge-public-api", "0.1"),
      opt[File]("config-file")
        .action((x, c) => c.copy(configurationFile = x))
        .validate(x =>
          if (x.exists) success
          else failure(s"Configuration file does not exist: ${x.getAbsolutePath}")
        )
        .text(
          "Configuration file for the plasma-btc-bridge-public-api service"
        )
    )
  }

}
