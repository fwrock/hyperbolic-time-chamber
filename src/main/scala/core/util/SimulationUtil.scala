package org.interscity.htc
package core.util

import core.util.JsonUtil.{ fromJson, readJsonFile }
import core.exception.SimulationEnvConfigFoundException
import core.api.ApiConfigRegistry

import com.typesafe.config.ConfigFactory
import org.interscity.htc.core.entity.configuration.{ Simulation, SimulationWrapper }
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.sharding.ShardRegion
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.util.ActorCreatorUtil.createShardRegion

import java.util.UUID
import scala.language.postfixOps

object SimulationUtil {

  def loadSimulationConfig(configuration: String = null): Simulation = {
    val path = if (configuration == null || configuration.isEmpty) null else configuration
    if (path != null) {
      val content = readJsonFile(path)
      try
        fromJson[SimulationWrapper](content).simulation
      catch {
        case _: Exception => fromJson[Simulation](content)
      }
    } else {
      ApiConfigRegistry.get match {
        case Some(config) => config
        case None         => loadSimulationConfigFromFileOrEnv()
      }
    }
  }

  /** Loads simulation config from application.conf / env var only, bypassing the API registry.
    * Used by [[core.api.ConfigRoutes]] to report what would be loaded without the API override.
    */
  def loadSimulationConfigFromFileOrEnv(): Simulation = {
    val applicationConfig = ConfigFactory.load().getString("htc.simulation.config-file")
    if (applicationConfig != null && applicationConfig.nonEmpty) {
      val content = readJsonFile(applicationConfig)
      fromJson[Simulation](content)
    } else {
      loadFromEnvironmentVariable("HTC_SIMULATION_CONFIG_FILE")
    }
  }

  private def loadFromEnvironmentVariable(envVar: String): Simulation =
    sys.env.get(envVar) match {
      case Some(file) => fromJson[Simulation](readJsonFile(file))
      case None       => throw new SimulationEnvConfigFoundException(envVar)
    }

  def loadFronApplicationConfig(configFile: String): Simulation =
    fromJson[Simulation](readJsonFile(configFile))

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
