package org.interscity.htc
package core.actor.manager

import core.entity.control.{ LocalTimeManagerTickInfo, ScheduledActors }
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.entity.state.DefaultState
import core.types.Tick

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ LocalTimeReportEvent, RegisterActorEvent, StartSimulationTimeEvent, UpdateGlobalTimeEvent }
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.util.ManagerConstantsUtil.{ GLOBAL_TIME_MANAGER_ACTOR_NAME, POOL_TIME_MANAGER_ACTOR_NAME }

import scala.collection.mutable

/** Global Time Manager that coordinates all local time managers.
  * This manager acts as a central coordinator, synchronizing time across
  * distributed local time managers and ensuring consistent simulation progress.
  * 
  * @param simulationDuration The total duration of the simulation in ticks
  * @param simulationManager Reference to the simulation manager
  */
class GlobalTimeManager(
  val simulationDuration: Tick,
  val simulationManager: ActorRef
) extends TimeManagerBase(
      timeManager = null,
      actorId = GLOBAL_TIME_MANAGER_ACTOR_NAME
    ) {

  private var selfProxy: ActorRef = null
  private var timeManagersPool: ActorRef = _
  private val localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerTickInfo] = mutable.Map()

  override def onStart(): Unit = {
    createTimeManagersPool()
  }

  private def createTimeManagersPool(): Unit = {
    val totalInstances = 64
    val maxInstancesPerNode = Math.max(8, totalInstances / 8)
    timeManagersPool = context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(0),
        ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(
        LocalDiscreteEventTimeManager.props(
          simulationDuration,
          simulationManager,
          Some(getSelfProxy)
        )
      ),
      name = POOL_TIME_MANAGER_ACTOR_NAME
    )
    simulationManager ! TimeManagerRegisterEvent(actorRef = timeManagersPool)
  }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerTimeManager(timeManagerRegisterEvent)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(sender(), localTimeReport.tick, localTimeReport.hasScheduled)
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Global TimeManager started at tick ${event.startTick}")
    event.data.foreach(data => startTime = data.startTime)
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    notifyLocalManagers(event)
  }

  protected def registerActor(event: RegisterActorEvent): Unit = {
    // Global time manager doesn't register actors directly
    // Actors are registered with local time managers
  }

  protected def scheduleEvent(event: ScheduleEvent): Unit = {
    // Forward schedule requests to the appropriate local time manager
    timeManagersPool ! event
  }

  protected def finishEvent(event: FinishEvent): Unit = {
    // Finish events are handled by local time managers
  }

  override protected def pauseSimulation(): Unit = {
    super.pauseSimulation()
    notifyLocalManagers(org.htc.protobuf.core.entity.event.control.execution.PauseSimulationEvent())
  }

  override protected def resumeSimulation(): Unit = {
    if (isPaused) {
      isPaused = false
      self ! SpontaneousEvent(tick = localTickOffset, actorRef = self)
      notifyLocalManagers(org.htc.protobuf.core.entity.event.control.execution.ResumeSimulationEvent())
    }
  }

  override protected def stopSimulation(): Unit = {
    super.stopSimulation()
    terminateSimulation()
  }

  private def registerTimeManager(event: TimeManagerRegisterEvent): Unit = {
    localTimeManagers.put(
      event.actorRef,
      LocalTimeManagerTickInfo(tick = localTickOffset)
    )
  }

  private def handleLocalTimeReport(
    localManager: ActorRef,
    tick: Tick,
    hasScheduled: Boolean
  ): Unit = {
    localTimeManagers.update(
      localManager,
      LocalTimeManagerTickInfo(
        tick = tick,
        hasSchedule = hasScheduled,
        isProcessed = true
      )
    )
    if (localTimeManagers.values.forall(_.isProcessed)) {
      calculateAndBroadcastNextGlobalTick()
    }
  }

  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    val scheduled = localTimeManagers.values.filter(_.hasSchedule)
    val nextTick = if (scheduled.nonEmpty) {
      scheduled.map(_.tick).min
    } else {
      localTimeManagers.values.filter(_.isProcessed).map(_.tick).min
    }
    
    localTickOffset = nextTick
    tickOffset = nextTick - initialTick
    
    localTimeManagers.keys.foreach { timeManager =>
      localTimeManagers.update(
        timeManager,
        LocalTimeManagerTickInfo(tick = nextTick)
      )
    }
    
    notifyLocalManagers(UpdateGlobalTimeEvent(localTickOffset))
  }

  private def notifyLocalManagers(event: Any): Unit = {
    localTimeManagers.keys.foreach { localManager =>
      localManager ! event
    }
  }

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    // Handled by local time managers
  }

  protected def advanceToNextTick(): Unit = {
    // Coordination happens through local time manager reports
  }

  protected def nextTick: Option[Tick] = {
    if (localTickOffset - initialTick >= simulationDuration) {
      None
    } else {
      Some(localTickOffset + 1)
    }
  }

  private def terminateSimulation(): Unit = {
    printSimulationDuration()
    logInfo("Global simulation terminated")
    notifyLocalManagers(org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent())
  }

  private def getSelfProxy: ActorRef = {
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }
  }
}

object GlobalTimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef
  ): Props =
    Props(classOf[GlobalTimeManager], simulationDuration, simulationManager)
}
