package org.interscity.htc
package core.util

import core.util.JsonUtil.{ fromJson, readJsonFile }
import core.exception.SimulationEnvConfigFoundException

import org.interscity.htc.core.entity.configuration.Simulation

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.sharding.ShardRegion
import org.interscity.htc.core.util.ActorCreatorUtil.createShardRegion

object SimulationUtil {

  def loadSimulationConfig(configuration: String = null): Simulation =
    if (configuration != null) {
      val content = readJsonFile(configuration)
//      println(s"Configuration loaded:\n$content")
      fromJson[Simulation](content)
    } else {
      val envConfig = "HTC_SIMULATION_CONFIG_FILE"
      sys.env.get(envConfig) match {
        case Some(file) => fromJson[Simulation](readJsonFile(file))
        case None       => throw new SimulationEnvConfigFoundException(envConfig)
      }
    }

  def createShards(system: ActorSystem, configuration: String = null): Unit =
    loadSimulationConfig(configuration).actorsDataSources.foreach {
      source =>
        system.log.info(s"Creating shard region for ${source.classType}")
        val shardRegion = createShardRegion(
          system,
          source.classType,
          s"${source.classType}-shard-initiator",
          null,
          null
        )
        shardRegion ! ShardRegion.StartEntity(s"${source.classType}-shard-initiator")
    }
}
