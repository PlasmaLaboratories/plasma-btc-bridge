package co.topl.bridge.publicapi

import java.io.File

case class StrataBTCBridgePublicApiParamConfig(
    configurationFile: File = new File("application.conf")
)
