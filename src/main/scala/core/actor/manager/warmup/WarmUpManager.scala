package org.interscity.htc
package core.actor.manager.warmup

import core.actor.manager.BaseManager
import core.api.SimulatorSettingsRegistry
import core.entity.event.control.warmup.{StartWarmUpWorkersEvent, WarmUpAllDoneEvent, WarmUpWorkerDoneEvent, WarmUpWorkerReadyEvent}
import core.entity.state.DefaultState
import core.runtime.accounting.RuntimeFailure
import core.util.ManagerConstantsUtil.WARM_UP_MANAGER_ACTOR_NAME
import org.apache.pekko.actor.{ActorRef, Props, Terminated}
import org.apache.pekko.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import scala.collection.mutable
import scala.util.control.NonFatal

/** Pool creation is asynchronous: target dispatch follows each worker's ready handshake. */
class WarmUpManager(val simulationManager: ActorRef, val expectedWorkers: Int)
  extends BaseManager[DefaultState](actorId = WARM_UP_MANAGER_ACTOR_NAME, timeManager = null) {
  private val workers = mutable.Map.empty[ActorRef, String]
  private val issued = mutable.Set.empty[ActorRef]
  private val done = mutable.Set.empty[ActorRef]
  private var targets: Option[List[String]] = None
  private var completed = false
  private var failed = false

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    simulationManager ! RuntimeFailure(s"warm-up manager restarted: ${reason.getMessage}")
    super.preRestart(reason, message)
  }

  override def onStart(): Unit = {
    require(expectedWorkers > 0, "warm-up needs at least one worker")
    val perNode = SimulatorSettingsRegistry.getInt("htc.warmup.max-workers-per-node", context.system.settings.config)
    context.actorOf(ClusterRouterPool(RoundRobinPool(0), ClusterRouterPoolSettings(
      totalInstances = expectedWorkers, maxInstancesPerNode = perNode, allowLocalRoutees = true
    )).props(WarmUpWorker.props(self)), name = "warm-up-worker-pool")
  }

  private def fail(reason: String): Unit = if (!failed && !completed) {
    failed = true
    simulationManager ! RuntimeFailure(s"warm-up failed: $reason")
  }
  private def protect(body: => Unit): Unit = try body catch { case NonFatal(e) => fail(e.getMessage) }
  private def dispatch(worker: ActorRef): Unit = targets.foreach { values =>
    if (issued.add(worker)) worker ! StartWarmUpWorkersEvent(values)
  }

  override def handleEvent: Receive = {
    case _: StopSimulationEvent => selfDestruct()
    case _ if failed || completed => ()
    case StartWarmUpWorkersEvent(values) => protect {
      require(targets.forall(_ == values), "warm-up targets changed during startup")
      targets = Some(values)
      workers.keys.foreach(dispatch)
    }
    case WarmUpWorkerReadyEvent(address) => protect {
      require(address.nonEmpty, "worker has no node address")
      workers.get(sender()) match {
        case Some(existing) => require(existing == address, "worker changed node address")
        case None =>
          require(workers.size < expectedWorkers && !workers.valuesIterator.contains(address),
            "duplicate or unexpected warm-up node")
          workers(sender()) = address
          context.watch(sender())
          logInfo(s"[WarmUpManager] Worker ready: $address (${workers.size}/$expectedWorkers)")
      }
      dispatch(sender())
    }
    case WarmUpWorkerDoneEvent(address) => protect {
      require(workers.get(sender()).contains(address) && issued.contains(sender()), "unannounced warm-up completion")
      if (done.add(sender())) {
        logInfo(s"[WarmUpManager] Worker done: $address (${expectedWorkers - done.size} remaining)")
        if (done.size == expectedWorkers) {
          completed = true
          workers.keys.foreach(context.unwatch)
          simulationManager ! WarmUpAllDoneEvent()
        }
      }
    }
    case Terminated(ref) if workers.contains(ref) => fail(s"worker lost: $ref")
    case RuntimeFailure(reason) if workers.contains(sender()) => fail(reason)
  }
}

object WarmUpManager {
  def props(simulationManager: ActorRef, expectedWorkers: Int): Props =
    Props(classOf[WarmUpManager], simulationManager, Int.box(expectedWorkers))
}
