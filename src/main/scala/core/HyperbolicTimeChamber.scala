package org.interscity.htc
package core

import org.apache.pekko.actor.Props
import org.apache.pekko.actor.{ Actor, ActorSystem, DeadLetter }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.interscity.htc.core.actor.manager.SimulationManager
import org.interscity.htc.core.metrics.MetricsServer
import org.interscity.htc.core.util.ManagerConstantsUtil.SIMULATION_MANAGER_ACTOR_NAME
import org.interscity.htc.core.util.{ ManagerConstantsUtil, SimulationUtil }

/** Subscribes to the Pekko DeadLetter stream and increments the Prometheus counter.
  * Also logs message type for diagnostics when dead letters are persistent.
  */
private class DeadLetterListener extends Actor {
  import org.slf4j.LoggerFactory
  private val log = LoggerFactory.getLogger(classOf[DeadLetterListener])

  override def receive: Receive = {
    case dl: DeadLetter =>
      MetricsServer.deadLetters.inc()
      log.debug(
        "Dead letter: msg={} from={} to={}",
        dl.message.getClass.getSimpleName,
        dl.sender.path,
        dl.recipient.path
      )
  }
}

object HyperbolicTimeChamber {

  def start(): Unit = {
    // Start Prometheus metrics server before actor system
    val metricsPort = sys.env.get("HTC_METRICS_PORT").flatMap(_.toIntOption).getOrElse(9001)
    core.metrics.MetricsServer.start(metricsPort)

    val system = ActorSystem("hyperbolic-time-chamber")

    // Subscribe to dead letters for Prometheus monitoring
    val deadLetterListener = system.actorOf(Props(new DeadLetterListener), "dead-letter-listener")
    system.eventStream.subscribe(deadLetterListener, classOf[DeadLetter])

    // 🎲 Inicializar RandomSeedManager com configuração da simulação
    try {
      val simulationConfig = SimulationUtil.loadSimulationConfig()
      actor.manager.RandomSeedManager.initialize(simulationConfig)
      system.log.info(
        s"🎲 RandomSeedManager inicializado com seed: ${simulationConfig.randomSeed.getOrElse("timestamp-based")}"
      )
    } catch {
      case e: Exception =>
        system.log.warning(
          s"⚠️ Não foi possível carregar configuração da simulação para RandomSeedManager: ${e.getMessage}"
        )
        system.log.warning("🎲 RandomSeedManager será inicializado sob demanda")
    }

    PekkoManagement(system).start()
    ClusterBootstrap(system).start()

    val cluster = Cluster(system)

    ClusterSharding(system)

    cluster.registerOnMemberUp {
      system.log.info(s"Member is up: ${cluster.selfMember}")
    }

    cluster.registerOnMemberRemoved {
      system.log.info(s"Member is removed: ${cluster.selfMember}")
    }

    SimulationUtil.startShards(system)

    // Start the per-node migration window subscriber.
    // This actor subscribes to the "migration-window" DistributedPubSub topic and:
    //   - Registers the SnapshotManager singleton proxy in MigrationStateStoreRegistry
    //   - Sets/clears the isMigrationActive flag when the LBM opens/closes the window
    // One instance per JVM node (not a singleton).
    actor.manager.loadbalance.migration.MigrationWindowSubscriber.startOnNode(system)

    val simulation = system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(
          SimulationManager()
        ),
        terminationMessage = StopSimulationEvent(),
        settings = ClusterSingletonManagerSettings(system)
      ),
      name = SIMULATION_MANAGER_ACTOR_NAME
    )
  }
}
