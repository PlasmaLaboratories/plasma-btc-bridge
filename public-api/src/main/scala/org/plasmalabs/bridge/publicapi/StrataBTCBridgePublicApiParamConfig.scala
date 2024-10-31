package org.plasmalabs.bridge.publicapi

import java.io.File

case class PlasmaBTCBridgePublicApiParamConfig(
  configurationFile: File = new File("application.conf")
)
