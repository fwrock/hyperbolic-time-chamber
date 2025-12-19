package org.interscity.htc
package core.util

import core.util.JsonUtil.{fromJson, readJsonFile}
import core.exception.SimulationEnvConfigFoundException

import com.typesafe.config.ConfigFactory
import org.interscity.htc.core.entity.configuration.Simulation
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.sharding.ShardRegion
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.util.ActorCreatorUtil.createShardRegion

import java.util.UUID
import scala.language.postfixOps

object SimulationUtil {

  def loadSimulationConfig(configuration: String = null): Simulation = {
    if (configuration != null) {
      val content = readJsonFile(configuration)
      fromJson[Simulation](content)
    } else {
      val applicationConfig = ConfigFactory.load().getString("htc.simulation.config-file")
      if (applicationConfig != null && applicationConfig.nonEmpty) {
        val content = readJsonFile(applicationConfig)
        fromJson[Simulation](content)
      } else {
        loadFromEnvironmentVariable("HTC_SIMULATION_CONFIG_FILE")
      }
    }
    }

    private def loadFromEnvironmentVariable(envVar: String): Simulation = {
      sys.env.get(envVar) match {
        case Some(file) => fromJson[Simulation](readJsonFile(file))
        case None       => throw new SimulationEnvConfigFoundException(envVar)
      }
    }
    
    def loadFronApplicationConfig(configFile: String): Simulation = {
      fromJson[Simulation](readJsonFile(configFile))
    }

  def startShards(system: ActorSystem, configuration: String = null): Unit =
    loadSimulationConfig(configuration).actorsDataSources
      .distinctBy(_.id)
      .filter(
        s => s.creationType == null || s.creationType == CreationTypeEnum.LoadBalancedDistributed
      )
      .distinctBy(
        s => s.classType
      )
      .foreach {
        source =>
          // 🎲 Usar UUID determinístico para shard initiator
          val initiatorId =
            try
              s"${core.actor.manager.RandomSeedManager.deterministicUUID()}-shard-initiator"
            catch {
              case _: Exception => s"${UUID.randomUUID().toString}-shard-initiator"
            }
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
