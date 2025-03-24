package org.interscity.htc

import org.apache.pekko.actor.Props
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.interscity.htc.core.actor.manager.SimulationManager
import org.interscity.htc.core.entity.event.control.execution.StopSimulationEvent
import org.interscity.htc.core.util.SimulationUtil
import org.slf4j.LoggerFactory

@main
def main(): Unit = {
  val system = ActorSystem("hyperbolic-time-chamber")

  PekkoManagement(system).start()

  val cluster = Cluster(system)

  cluster.registerOnMemberUp {
    system.log.info(s"Member is up: ${cluster.selfMember}")
  }

  cluster.registerOnMemberRemoved {
    system.log.info(s"Member is removed: ${cluster.selfMember}")
  }

  val configuration =
    "simulations/supermarket-simple/simulation.json"

  SimulationUtil.createShards(system, configuration)

  val simulation = system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = Props(
        SimulationManager(
          simulationPath = configuration
        )
      ),
      terminationMessage = StopSimulationEvent(),
      settings = ClusterSingletonManagerSettings(system)
    ),
    name = "simulation-manager"
  )
}
