package org.interscity.htc
package core.util

import core.entity.configuration.Simulation
import core.util.JsonUtil.{ fromJson, readJsonFile }
import core.exception.SimulationEnvConfigFoundException

object SimulationUtil {

  def loadSimulationConfig(configuration: String = null): Simulation = {
    print("Loading simulation configuration... ")
    print(configuration)
    if (configuration != null) {
      fromJson[Simulation](readJsonFile(configuration))
    } else {
      val envConfig = "HTC_SIMULATION_CONFIG_FILE"
      sys.env.get(envConfig) match {
        case Some(file) => fromJson[Simulation](readJsonFile(file))
        case None       => throw new SimulationEnvConfigFoundException(envConfig)
      }
    }
  }
}
