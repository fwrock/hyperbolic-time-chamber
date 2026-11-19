package org.interscity.htc
package core

import org.apache.pekko.actor.Props
import org.apache.pekko.actor.{ Actor, ActorSystem, DeadLetter }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.interscity.htc.core.actor.manager.SimulationManager
import org.interscity.htc.core.api.ConfigApiServer
import org.interscity.htc.core.metrics.MetricsServer
import org.interscity.htc.core.metrics.system.InfrastructureMetrics
import org.interscity.htc.core.util.ManagerConstantsUtil.SIMULATION_MANAGER_ACTOR_NAME
import org.interscity.htc.core.util.{ ManagerConstantsUtil, SimulationUtil }

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** Subscribes to the Pekko DeadLetter stream and increments the Prometheus counter. Also logs
  * message type for diagnostics when dead letters are persistent.
  */
private class DeadLetterListener extends Actor {
  import org.slf4j.LoggerFactory
  private val log = LoggerFactory.getLogger(classOf[DeadLetterListener])

  override def receive: Receive = {
    case dl: DeadLetter =>
      val path = dl.recipient.path
      val recipientType = path.elements.toList match {
        case _ :: "sharding" :: shardRegion :: _ => shardRegion
        case elems                               => elems.lastOption.getOrElse("unknown")
      }
      val messageType = dl.message.getClass.getSimpleName
      InfrastructureMetrics.deadLetters.labels(recipientType, messageType).inc()
      log.debug(
        "Dead letter: msg={} from={} to={}",
        messageType,
        dl.sender.path,
        path
      )
  }
}

object HyperbolicTimeChamber {

  def start(): Unit = {
    // Install file-owned settings before any process-wide resource or ActorSystem is created.
    val bootstrap = core.api.SimulatorSettingsRegistry.effective
    if (bootstrap.hasPath("htc.simulation.config-file") &&
        bootstrap.getString("htc.simulation.config-file").nonEmpty)
      SimulationUtil.installLocalScenarioSettings(SimulationUtil.loadSimulationConfig())
    val metricsPort = core.api.SimulatorSettingsRegistry.effective.getInt("htc.metrics.port")
    core.metrics.MetricsServer.start(metricsPort)

    val system = ActorSystem("hyperbolic-time-chamber")

    ConfigApiServer.start(system)

    val deadLetterListener = system.actorOf(Props(new DeadLetterListener), "dead-letter-listener")
    system.eventStream.subscribe(deadLetterListener, classOf[DeadLetter])

    try {
      val simulationConfig = SimulationUtil.loadSimulationConfig()
      actor.manager.RandomSeedManager.initialize(simulationConfig)
      system.log.info(
        s"RandomSeedManager initialized using seed: ${simulationConfig.randomSeed.getOrElse("timestamp-based")}"
      )
    } catch {
      case e: Exception =>
        system.log.warning(
          s"It was not possible load random seed configuration for RandomSeedManager: ${e.getMessage}"
        )
        system.log.warning("RandomSeedManager will be initialized with timestamp-based seed")
    }

    PekkoManagement(system).start()
    ClusterBootstrap(system).start()

    val cluster = Cluster(system)

    // Who am I in this distributed run (rank + label); stamped on every outgoing message, see NodeIdentity.
    core.cluster.NodeIdentity.start(system)
    core.metrics.ExecutionTimingCollector(system)

    ClusterSharding(system)

    cluster.registerOnMemberUp {
      system.log.info(s"Member is up: ${cluster.selfMember}")
    }

    cluster.registerOnMemberRemoved {
      system.log.info(s"Member is removed: ${cluster.selfMember}")
    }

    try SimulationUtil.startShardsAtBoot(system, core.api.SimulationController.isApiWaitEnabled)
    catch {
      case scala.util.control.NonFatal(error) =>
        system.log.error(error, "[SIMULATION-FAILED] Shard startup rejected: {}", error.getMessage)
        MetricsServer.stop()
        system.terminate()
        throw error
    }

    actor.manager.loadbalance.migration.MigrationWindowSubscriber.startOnNode(system)

    val simulation = util.ActorCreatorUtil.createSingletonManager(
      system = system,
      manager = Props(SimulationManager()),
      name = SIMULATION_MANAGER_ACTOR_NAME,
      terminateMessage = StopSimulationEvent()
    )

    // Everything above is async actor-system setup — start() otherwise returns immediately,
    // and main()'s thread finishing would exit the JVM right after startup under `sbt run`/
    // `runMain` (confirmed directly: a single-node cluster formed, then the JVM exited before
    // the simulation ever ran). The assembled-jar/`java -jar` path has stayed alive incidentally
    // via Pekko's non-daemon dispatcher threads; blocking here makes that explicit instead of
    // relying on it.
    Await.ready(system.whenTerminated, Duration.Inf)
  }
}
