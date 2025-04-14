package org.interscity.htc
package core.actor.manager

import core.entity.event.{ EntityEnvelopeEvent, FinishEvent, SpontaneousEvent }
import core.types.Tick

import org.apache.pekko.actor.{ ActorRef, Props }
import core.entity.control.{ LocalTimeManagerTickInfo, ScheduledActors }
import core.entity.state.DefaultState

import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, LocalTimeReportEvent, PauseSimulationEvent, RegisterActorEvent, ResumeSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent, UpdateGlobalTimeEvent }
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.util.ManagerConstantsUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.{ GLOBAL_TIME_MANAGER_ACTOR_NAME, LOAD_MANAGER_ACTOR_NAME, POOL_TIME_MANAGER_ACTOR_NAME }

import scala.collection.mutable

class TimeManager(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef]
) extends BaseManager[DefaultState](
      timeManager = null,
      actorId =
        if parentManager.isEmpty then s"$LOAD_MANAGER_ACTOR_NAME-${System.nanoTime()}"
        else GLOBAL_TIME_MANAGER_ACTOR_NAME,
    ) {

  private var selfProxy: ActorRef = null

  private var startTime: Long = 0
  private var localTickOffset: Tick = 0
  private var tickOffset: Tick = 0
  private var initialTick: Tick = 0
  private var isPaused: Boolean = false
  private var isStopped: Boolean = false

  private val registeredActors = mutable.Set[String]()
  private val scheduledActors = mutable.Map[Tick, ScheduledActors]()
  private val runningEvents = mutable.Set[Identify]()
  private var timeManagersPool: ActorRef = _

  private var countScheduled = 0

  private val localTimeManagers: mutable.Map[ActorRef, LocalTimeManagerTickInfo] = mutable.Map()

  override def onStart(): Unit =
    if (parentManager.isEmpty) {
      createTimeManagersPool()
    } else {
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }

  private def createTimeManagersPool(): Unit = {
    val totalInstances = 40
    val maxInstancesPerNode = Math.max(10, totalInstances / 8)
    timeManagersPool = context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(0),
        ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(TimeManager.props(simulationDuration, simulationManager, Some(getSelfProxy))),
      name = POOL_TIME_MANAGER_ACTOR_NAME
    )
    simulationManager ! TimeManagerRegisterEvent(actorRef = timeManagersPool)
  }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case schedule: ScheduleEvent         => scheduleApply(schedule)
    case finish: FinishEvent             => finishEventApply(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) actSpontaneous(spontaneous)
    case PauseSimulationEvent            => if (isRunning) pauseSimulation()
    case ResumeSimulationEvent           => resumeSimulation()
    case StopSimulationEvent             => stopSimulation()
    case e: UpdateGlobalTimeEvent        => syncWithGlobalTime(e.tick)
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerTimeManager(timeManagerRegisterEvent)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(sender(), localTimeReport.tick, localTimeReport.hasScheduled)
  }

  private def registerTimeManager(timeManagerRegisterEvent: TimeManagerRegisterEvent): Unit =
    localTimeManagers.put(
      timeManagerRegisterEvent.actorRef,
      LocalTimeManagerTickInfo(
        tick = localTickOffset
      )
    )

  private def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorId)
    scheduleApply(
      ScheduleEvent(tick = event.startTick, actorRef = event.actorId, identify = event.identify)
    )
  }

  private def startSimulation(start: StartSimulationTimeEvent): Unit = {
    logInfo(s"Started simulation: $start")
    unstashAll()
    start.data match
      case Some(data) =>
        startTime = data.startTime
      case _ =>
        startTime = System.currentTimeMillis()
    initialTick = start.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    if (parentManager.isEmpty) {
      logInfo(s"Global TimeManager started at tick $localTickOffset")
      notifyLocalManagers(start)
    } else {
      logInfo(
        s"Local TimeManager started at tick $localTickOffset with parent ${parentManager.get} and self $self"
      )
      self ! UpdateGlobalTimeEvent(localTickOffset)
    }
  }

  private def pauseSimulation(): Unit = {
    isPaused = true
    if (parentManager.isEmpty) {
      notifyLocalManagers(PauseSimulationEvent)
    }
  }

  private def resumeSimulation(): Unit =
    if (isPaused) {
      isPaused = false
      self ! SpontaneousEvent(tick = localTickOffset, actorRef = self)
      if (parentManager.isEmpty) {
        notifyLocalManagers(ResumeSimulationEvent)
      }
    }

  private def stopSimulation(): Unit = {
    isStopped = true
    printState()
    terminateSimulation()
  }

  private def syncWithGlobalTime(globalTick: Tick): Unit = {
    localTickOffset = globalTick
    tickOffset = globalTick - initialTick
    if (parentManager.nonEmpty) {
      if (isRunning) {
        if (localTickOffset - initialTick >= simulationDuration) {
          terminateSimulation()
        } else {
          processTick(localTickOffset)
        }
      }
    }
  }

  private def handleLocalTimeReport(
    localManager: ActorRef,
    tick: Tick,
    hasScheduled: Boolean
  ): Unit =
    if (parentManager.isEmpty) {
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
    localTimeManagers.keys.foreach {
      timeManager =>
        localTimeManagers.update(
          timeManager,
          LocalTimeManagerTickInfo(
            tick = nextTick
          )
        )
    }
    logInfo(s"Broadcasting next global tick $nextTick to local time managers")
    notifyLocalManagers(UpdateGlobalTimeEvent(localTickOffset))
  }

  private def notifyLocalManagers(event: Any): Unit =
    localTimeManagers.keys.foreach {
      localManager =>
        localManager ! event
    }

  private def scheduleApply(schedule: ScheduleEvent): Unit = {
    if (schedule.tick < localTickOffset) {
      log.warning(s"Schedule event for past tick ${schedule.tick}, event=$schedule ignored")
      return
    }
    countScheduled += 1
    scheduledActors.get(schedule.tick) match
      case Some(scheduled) =>
        schedule.identify.foreach(scheduled.actorsRef.add)
      case None =>
        scheduledActors.put(
          schedule.tick,
          ScheduledActors(tick = schedule.tick, actorsRef = mutable.Set(schedule.identify.get))
        )
  }

  private def getLabel: String =
    if parentManager.isEmpty then "GlobalTimeManager" else "LocalTimeManager"

  private def finishEventApply(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      runningEvents.filterInPlace(_.id != finish.identify.id)
      finishDestruct(finish)
      advanceToNextTick()
      reportGlobalTimeManager(true)
    } else {
//      logInfo("TimeManager finish event forward")
      finish.timeManager ! finish
    }

  private def finishDestruct(finish: FinishEvent): Unit =
    if (finish.destruct) {
      registeredActors.remove(finish.identify.id)
    }

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit =
    if (isRunning) {
      if (localTickOffset - initialTick >= simulationDuration) {
        terminateSimulation()
      } else {
        processTick(spontaneous.tick)
      }
    }

  private def processTick(tick: Tick): Unit =
    if (scheduledActors.nonEmpty) {
      processNextEventTick(tick)
    } else {
      advanceToNextTick()
      reportGlobalTimeManager()
    }

  private def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    if (parentManager.isDefined && runningEvents.isEmpty) {
      parentManager.get ! LocalTimeReportEvent(
        tick = localTickOffset,
        hasScheduled = hasScheduled,
        actorRef = getPath
      )
    }

  private def processNextEventTick(tick: Tick): Unit =
    scheduledActors.get(tick) match
      case Some(scheduled) =>
        scheduled.actorsRef.foreach {
          actor => runningEvents.add(actor)
        }
        sendSpontaneousEvent(tick, scheduled.actorsRef)
        scheduledActors.remove(tick)
      case None =>
        advanceToNextTick()
        reportGlobalTimeManager()

  private def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[Identify]): Unit =
    actorsRef.foreach {
      actor =>
        sendSpontaneousEvent(tick, actor)
    }

  private def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit =
    getShardRef(identity.shardId) ! EntityEnvelopeEvent(
      identity.id,
      SpontaneousEvent(
        tick = tick,
        actorRef = self
      )
    )

  private def advanceToNextTick(): Unit = {
    val newTick = nextTick
    if (localTickOffset < simulationDuration) {
      localTickOffset = newTick
    } else {
      terminateSimulation()
    }
  }

  private def nextTick: Tick = {
    if (runningEvents.isEmpty) {
      return (scheduledActors.nonEmpty, runningEvents.isEmpty) match {
        case (true, true) =>
          scheduledActors.keys.minOption.getOrElse(localTickOffset + 1)
        case _ => localTickOffset + 1
      }
    }
    localTickOffset
  }

  private def terminateSimulation(): Unit = {
    if (runningEvents.isEmpty && scheduledActors.isEmpty) {
      if (parentManager.isEmpty) {
        notifyLocalManagers(StopSimulationEvent)
      }
      context.stop(self)
    }
    printSimulationDuration()
  }

  private def sendDestructEvent(finishEvent: FinishEvent): Unit =
    getShardRef(finishEvent.identify.shardId) ! EntityEnvelopeEvent(
      finishEvent.identify.id,
      DestructEvent(tick = localTickOffset, actorRef = getPath)
    )

  private def printState(): Unit = {
    logInfo(s"runningEvents: ${runningEvents.size}")
    logInfo(s"scheduledActors: ${scheduledActors.size}")
    logInfo(s"localTickOffset: $localTickOffset")
    logInfo(s"tickOffset: $tickOffset")
  }

  private def printSimulationDuration(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    logInfo(s"$getLabel - Simulation endTick: $localTickOffset")
    logInfo(s"$getLabel - Simulation total ticks: ${localTickOffset - initialTick}")
    logInfo(s"$getLabel - Simulation duration: $duration ms")
    logInfo(s"$getLabel - Simulation duration: ${duration / 1000.0} s")
    logInfo(s"$getLabel - Simulation duration: ${duration / 1000.0 / 60.0} min")
    logInfo(s"$getLabel - Simulation duration: ${duration / 1000.0 / 60.0 / 60.0} h")
  }

  private def isRunning: Boolean = !isPaused && !isStopped

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(GLOBAL_TIME_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }
}

object TimeManager {
  def props(
    simulationDuration: Tick,
    simulationManager: ActorRef,
    parentManager: Option[ActorRef]
  ): Props =
    Props(classOf[TimeManager], simulationDuration, simulationManager, parentManager)
}
