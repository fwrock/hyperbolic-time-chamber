package org.interscity.htc

import org.apache.pekko.actor.Props
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import org.interscity.htc.core.actor.manager.SimulationManager
import org.interscity.htc.core.entity.event.control.execution.{PrepareSimulationEvent, StopSimulationEvent}
import org.interscity.htc.core.util.DistributedUtil

@main
def main(): Unit = {
  val system = ActorSystem("hyperbolic-time-chamber")

  val cluster = Cluster(system)

  cluster.registerOnMemberUp {
    system.log.info(s"Member is up: ${cluster.selfMember}")
  }

  cluster.registerOnMemberRemoved {
    system.log.info(s"Member is removed: ${cluster.selfMember}")
  }

  val configuration =
    "/home/dean/PhD/simulator/hyperbolic-time-chamber/src/main/resources/simulations/supermarket-simple/simulation.json"

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
  val clusterName = System.getenv("CLUSTER_NAME")
  if (clusterName == "node2") {
    DistributedUtil.createSingletonProxy(system, "simulation-manager") ! PrepareSimulationEvent(configuration)
  }
}
