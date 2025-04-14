package org.interscity.htc
package core.util

import core.util.JsonUtil.{fromJson, readJsonFile}
import core.exception.SimulationEnvConfigFoundException

import org.interscity.htc.core.entity.configuration.Simulation
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.sharding.ShardRegion
import org.interscity.htc.core.util.ActorCreatorUtil.createShardRegion

import java.util.UUID
import scala.language.postfixOps

object SimulationUtil {

  def loadSimulationConfig(configuration: String = null): Simulation =
    if (configuration != null) {
      val content = readJsonFile(configuration)
      fromJson[Simulation](content)
    } else {
      val envConfig = "HTC_SIMULATION_CONFIG_FILE"
      sys.env.get(envConfig) match {
        case Some(file) => fromJson[Simulation](readJsonFile(file))
        case None       => throw new SimulationEnvConfigFoundException(envConfig)
      }
    }

  def startShards(system: ActorSystem, configuration: String = null): Unit =
    loadSimulationConfig(configuration).actorsDataSources.distinctBy(_.id).foreach {
      source =>
        val initiatorId = s"${UUID.randomUUID().toString}-shard-initiator"
        val shardRegion = createShardRegion(
          system,
          source.classType,
          initiatorId,
          IdUtil.format(source.id),
          null,
          null
        )
        shardRegion ! ShardRegion.StartEntity(initiatorId)
    }
}
