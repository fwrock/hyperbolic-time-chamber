package org.interscity.htc
package core.util

import core.util.JsonUtil.{fromJson, readJsonFile}
import core.exception.SimulationEnvConfigFoundException

import org.htc.protobuf.core.entity.configuration.Simulation

import scalapb.json4s.JsonFormat

object SimulationUtil {

  def loadSimulationConfig(configuration: String = null): Simulation =
    if (configuration != null) {
      val content = readJsonFile(configuration)
      println(s"Configuration loaded:\n$content")
      JsonFormat.fromJsonString[Simulation](content)
    } else {
      val envConfig = "HTC_SIMULATION_CONFIG_FILE"
      sys.env.get(envConfig) match {
        case Some(file) => JsonFormat.fromJsonString[Simulation](readJsonFile(file))
        case None       => throw new SimulationEnvConfigFoundException(envConfig)
      }
    }
}
