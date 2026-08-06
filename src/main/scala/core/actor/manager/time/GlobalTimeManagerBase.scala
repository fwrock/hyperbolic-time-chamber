package org.interscity.htc
package core.actor.manager.time

import core.entity.event.control.execution.TimeManagerRegisterEvent
import core.types.Tick
import core.util.ManagerConstantsUtil.POOL_TIME_MANAGER_ACTOR_NAME
import core.api.SimulatorSettingsRegistry

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool

/** Base for the cluster-wide singleton time-manager coordinator, holding the plumbing that's
  * identical regardless of synchronization strategy — creating and reaching the pool of local time
  * managers — so [[ConservativeGlobalTimeManager]] (today's `SelectiveBarrier` coordinator) and an
  * eventual `OptimisticGlobalTimeManager` (Time Warp's GVT coordinator, see
  * `docs/TIME_WARP_DESIGN.md` §2/§3) can share it without either inheriting the other's protocol.
  * Extracted out of what used to be a single `GlobalTimeManager` class, no behavior change.
  *
  * Deliberately does *not* also extract migration-pause/progressive-loading coordination: both are
  * currently expressed entirely in terms of the `SelectiveBarrier`'s own per-LTM tick/isProcessed
  * state, and inventing shared hooks for them now — before `OptimisticGlobalTimeManager` exists to
  * demonstrate what it actually needs — would be speculative abstraction. Revisit once that class
  * is built for real.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  */
abstract class GlobalTimeManagerBase(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  actorId: String
) extends TimeManagerBase(
      timeManager = null,
      actorId = actorId
    ) {

  private var selfProxy: ActorRef = null
  protected var timeManagersPool: ActorRef = _

  /** Props for the concrete local-time-manager strategy this coordinator's pool should run.
    * `ConservativeGlobalTimeManager` supplies `LocalDiscreteEventTimeManager.props`; an
    * `OptimisticGlobalTimeManager` would supply its own optimistic LTM props here instead.
    */
  protected def localTimeManagerProps(): Props

  protected def createTimeManagersPool(): Unit = {
    val config = context.system.settings.config
    val totalInstances =
      SimulatorSettingsRegistry.getInt("htc.time-manager.total-instances", config)
    val maxInstancesPerNode =
      SimulatorSettingsRegistry.getInt("htc.time-manager.max-instances-per-node", config)
    logInfo(
      s"Creating LocalTM pool: totalInstances=$totalInstances, " +
        s"maxInstancesPerNode=$maxInstancesPerNode"
    )
    timeManagersPool = context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(0),
        ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(localTimeManagerProps()),
      name = POOL_TIME_MANAGER_ACTOR_NAME
    )
    simulationManager ! TimeManagerRegisterEvent(actorRef = timeManagersPool)
  }

  protected def notifyLocalManagers(event: Any): Unit =
    timeManagersPool ! org.apache.pekko.routing.Broadcast(event)

  protected def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(core.util.ManagerConstantsUtil.GLOBAL_TIME_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }
}
