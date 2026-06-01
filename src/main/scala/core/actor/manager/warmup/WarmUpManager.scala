package org.interscity.htc
package core.actor.manager.warmup

import core.actor.manager.BaseManager
import core.api.SimulatorSettingsRegistry
import core.entity.event.control.warmup.{
  StartWarmUpWorkersEvent,
  WarmUpAllDoneEvent,
  WarmUpWorkerDoneEvent
}
import core.entity.state.DefaultState
import core.util.ManagerConstantsUtil.WARM_UP_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.{ Broadcast, RoundRobinPool }
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent

/** Cluster-singleton manager that orchestrates distributed JVM warm-up before simulation ticks.
  *
  * Protocol:
  *   1. SimulationManager creates WarmUpManager (via createSingletonManager) and sends
  *      [[StartWarmUpWorkersEvent]] with the list of targets.
  *   2. WarmUpManager broadcasts [[StartWarmUpWorkersEvent]] to all [[WarmUpWorker]] routees
  *      (one per cluster node, running on io-dispatcher).
  *   3. Each worker invokes its targets locally (blocking the io-dispatcher thread).
  *   4. Each worker replies with [[WarmUpWorkerDoneEvent]].
  *   5. When all expected workers have replied, WarmUpManager sends [[WarmUpAllDoneEvent]]
  *      to simulationManager so simulation ticks can start.
  */
class WarmUpManager(
  val simulationManager: ActorRef,
  val expectedWorkers: Int
) extends BaseManager[DefaultState](
      actorId = WARM_UP_MANAGER_ACTOR_NAME,
      timeManager = null
    ) {

  private var workersPool: ActorRef = _
  private var pendingWorkers: Int   = 0
  private var targets: List[String] = Nil

  override def onStart(): Unit = {
    val config             = context.system.settings.config
    val maxInstancesPerNode = SimulatorSettingsRegistry
      .getInt("htc.warmup.max-workers-per-node", config)

    workersPool = context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(0),
        ClusterRouterPoolSettings(
          totalInstances      = expectedWorkers,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees   = true
        )
      ).props(WarmUpWorker.props),
      name = "warm-up-worker-pool"
    )
  }

  override def handleEvent: Receive = {
    case StartWarmUpWorkersEvent(t) =>
      targets        = t
      pendingWorkers = expectedWorkers
      logInfo(
        s"[WarmUpManager] Broadcasting warm-up to $pendingWorkers worker(s), targets=$targets"
      )
      workersPool ! Broadcast(StartWarmUpWorkersEvent(targets))

    case WarmUpWorkerDoneEvent(address) =>
      pendingWorkers -= 1
      logInfo(
        s"[WarmUpManager] Worker done: $address ($pendingWorkers remaining)"
      )
      if (pendingWorkers <= 0) {
        logInfo("[WarmUpManager] All workers complete — notifying SimulationManager")
        simulationManager ! WarmUpAllDoneEvent()
      }

    case _: StopSimulationEvent => selfDestruct()
  }
}

object WarmUpManager {
  def props(simulationManager: ActorRef, expectedWorkers: Int): Props =
    Props(new WarmUpManager(simulationManager, expectedWorkers))
}
