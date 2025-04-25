package org.interscity.htc
package core

import org.apache.pekko.actor.Props
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.apache.pekko.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.interscity.htc.core.HyperbolicTimeChamber
import org.interscity.htc.core.actor.manager.SimulationManager
import org.interscity.htc.core.util.ManagerConstantsUtil.SIMULATION_MANAGER_ACTOR_NAME
import org.interscity.htc.core.util.{ManagerConstantsUtil, SimulationUtil}

object HyperbolicTimeChamber {

  def start(): Unit = {
    val system = ActorSystem("hyperbolic-time-chamber")

    PekkoManagement(system).start()

    val cluster = Cluster(system)

    ClusterSharding(system)

    cluster.registerOnMemberUp {
      system.log.info(s"Member is up: ${cluster.selfMember}")
    }

    cluster.registerOnMemberRemoved {
      system.log.info(s"Member is removed: ${cluster.selfMember}")
    }

    SimulationUtil.startShards(system)

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
