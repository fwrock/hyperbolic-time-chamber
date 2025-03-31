package org.interscity.htc

import org.apache.pekko.actor.Props
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.interscity.htc.core.actor.manager.SimulationManager
import com.typesafe.config.ConfigFactory

@main
def main(): Unit = {
  val system = ActorSystem("hyperbolic-time-chamber")

  val config = ConfigFactory.load()
  println(config.getString("pekko.actor.provider"))

  val cluster = Cluster(system)

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

  cluster.registerOnMemberUp {
    println(s"Member is up: ${cluster.selfMember}")
  }

  cluster.registerOnMemberRemoved {
    println(s"Member is removed: ${cluster.selfMember}")
  }
}
