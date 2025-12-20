package org.interscity.htc
package core.actor.manager

import core.entity.event.{EntityEnvelopeEvent, FinishEvent, SpontaneousEvent}
import core.types.Tick

import org.apache.pekko.actor.{ActorRef, Props}
import core.entity.control.{LocalTimeManagerTickInfo, ScheduledActors}
import core.entity.state.TimeManagerState

import org.apache.pekko.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, LocalTimeReportEvent, PauseSimulationEvent, RegisterActorEvent, ResumeSimulationEvent, StartSimulationTimeEvent, StopSimulationEvent, UpdateGlobalTimeEvent}
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.util.{ManagerConstantsUtil, StringUtil}
import org.interscity.htc.core.util.ManagerConstantsUtil.{GLOBAL_TIME_MANAGER_ACTOR_NAME, LOAD_MANAGER_ACTOR_NAME, POOL_TIME_MANAGER_ACTOR_NAME}

import scala.collection.mutable

class TimeManager(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef]
) extends BaseManager[TimeManagerState](
      timeManager = null,
      actorId =
        if parentManager.isEmpty then s"$LOAD_MANAGER_ACTOR_NAME-${System.nanoTime()}"
        else GLOBAL_TIME_MANAGER_ACTOR_NAME,
    ) {

  state = TimeManagerState()

  private var selfProxy: ActorRef = null

  override def onStart(): Unit =
    if (parentManager.isEmpty) {
      createTimeManagersPool()
    } else {
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }

  private def createTimeManagersPool(): Unit = {
    val totalInstances = config.getInt("htc.time-manager.total-instances")
    val maxInstancesPerNode = config.getInt("htc.time-manager.max-instances-per-node")

    logInfo(s"Creating TimeManager pool: totalInstances=$totalInstances, maxInstancesPerNode=$maxInstancesPerNode")

    state.timeManagersPool = context.actorOf(
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
    simulationManager ! TimeManagerRegisterEvent(actorRef = state.timeManagersPool)
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
    case e: UpdateGlobalTimeEvent        => syncWithGlobalTime(e.tick, e.totalEventsAmount)
    case timeManagerRegisterEvent: TimeManagerRegisterEvent =>
      registerTimeManager(timeManagerRegisterEvent)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(sender(), localTimeReport.tick, localTimeReport.hasScheduled, localTimeReport.eventsAmount)
  }

  private def registerTimeManager(timeManagerRegisterEvent: TimeManagerRegisterEvent): Unit =
    state.localTimeManagers.put(
      timeManagerRegisterEvent.actorRef,
      LocalTimeManagerTickInfo(
        tick = state.localTickOffset
      )
    )

  private def registerActor(event: RegisterActorEvent): Unit = {
    state.registeredActors.add(event.actorId)
    scheduleApply(
      ScheduleEvent(tick = event.startTick, actorRef = event.actorId, identify = event.identify)
    )
  }

  private def startSimulation(start: StartSimulationTimeEvent): Unit = {
    logInfo(s"Started simulation: ${start.startTick}")
    unstashAll()
    start.data match
      case Some(data) =>
        state.startTime = data.startTime
      case _ =>
        state. startTime = System.currentTimeMillis()
    state.initialTick = start.startTick
    state.localTickOffset = state.initialTick
    state.isPaused = false
    state.isStopped = false
    if (parentManager.isEmpty) {
      logInfo(s"Global TimeManager started at tick ${state.localTickOffset}")
      notifyLocalManagers(start)
    } else {
      logInfo(
        s"Local TimeManager started at tick ${state.localTickOffset}"
      )
      self ! UpdateGlobalTimeEvent(state.localTickOffset, totalEventsAmount = state.totalEventsAmount)
    }
  }

  private def pauseSimulation(): Unit = {
    state.isPaused = true
    if (parentManager.isEmpty) {
      notifyLocalManagers(PauseSimulationEvent)
    }
  }

  private def resumeSimulation(): Unit =
    if (state.isPaused) {
      state.isPaused = false
      self ! SpontaneousEvent(tick = state.localTickOffset, actorRef = self)
      if (parentManager.isEmpty) {
        notifyLocalManagers(ResumeSimulationEvent)
      }
    }

  private def stopSimulation(): Unit = {
    state.isStopped = true
    terminateSimulation()
  }

  private def syncWithGlobalTime(globalTick: Tick, totalEventsAmount: Long): Unit = {
    state.localTickOffset = globalTick
    state.tickOffset = globalTick - state.initialTick
    state.totalEventsAmount = totalEventsAmount
    if (parentManager.nonEmpty) {
      if (isRunning) {
        if (state.localTickOffset - state.initialTick >= simulationDuration) {
          terminateSimulation()
        } else {
          processTick(state.localTickOffset)
        }
      }
    }
  }

  private def handleLocalTimeReport(
    localManager: ActorRef,
    tick: Tick,
    hasScheduled: Boolean,
    eventsAmount: Long
  ): Unit =
    if (parentManager.isEmpty) {
      state.totalEventsAmount += eventsAmount
      state.localTimeManagers.update(
        localManager,
        LocalTimeManagerTickInfo(
          tick = tick,
          hasSchedule = hasScheduled,
          isProcessed = true
        )
      )
      if (state.localTimeManagers.values.forall(_.isProcessed)) {
        calculateAndBroadcastNextGlobalTick()
      }
    }

  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    val scheduled = state.localTimeManagers.values.filter(_.hasSchedule)
    val nextTick = if (scheduled.nonEmpty) {
      scheduled.map(_.tick).min
    } else {
      state.localTimeManagers.values.filter(_.isProcessed).map(_.tick).min
    }
    state.localTickOffset = nextTick
    state.tickOffset = nextTick - state.initialTick
    state.localTimeManagers.keys.foreach {
      timeManager =>
        state.localTimeManagers.update(
          timeManager,
          LocalTimeManagerTickInfo(
            tick = nextTick
          )
        )
    }
    notifyLocalManagers(UpdateGlobalTimeEvent(state.localTickOffset, totalEventsAmount = state.totalEventsAmount))
  }

  private def notifyLocalManagers(event: Any): Unit =
    state.localTimeManagers.keys.foreach {
      localManager =>
        localManager ! event
    }

  private def scheduleApply(schedule: ScheduleEvent): Unit = {
    state.countScheduled += 1
    state.scheduledActors.get(schedule.tick) match
      case Some(scheduled) =>
        schedule.identify.foreach(scheduled.actorsRef.add)
      case None =>
        state.scheduledActors.put(
          schedule.tick,
          ScheduledActors(tick = schedule.tick, actorsRef = mutable.Set(schedule.identify.get))
        )
  }

  private def getLabel: String =
    if parentManager.isEmpty then "GlobalTimeManager" else "LocalTimeManager"

  private def finishEventApply(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      finish.scheduleTick.map(_.toLong).foreach(state.scheduledTicksOnFinish.add)
      state.runningEvents.filterInPlace(_.id != finish.identify.id)
      finishDestruct(finish)
      advanceToNextTick()
      reportGlobalTimeManager(true)
      state.eventsAmount += finish.eventsAmount
    } else {
      finish.timeManager ! finish
    }

  private def finishDestruct(finish: FinishEvent): Unit =
    if (finish.destruct) {
      state.registeredActors.remove(finish.identify.id)
      sendDestructEvent(finish)
    }

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit =
    if (isRunning) {
      if (state.localTickOffset - state.initialTick >= simulationDuration) {
        terminateSimulation()
      } else {
        processTick(spontaneous.tick)
      }
    }

  private def processTick(tick: Tick): Unit =
    if (state.scheduledActors.nonEmpty) {
      processNextEventTick(tick)
    } else {
      advanceToNextTick()
      reportGlobalTimeManager()
    }
  
  private def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    if (parentManager.isDefined && state.runningEvents.isEmpty) {
      state.scheduledTicksOnFinish.clear()
      parentManager.get ! LocalTimeReportEvent(
        tick = state.localTickOffset,
        hasScheduled = hasScheduled,
        eventsAmount = state.eventsAmount
      )
      state.eventsAmount = 0
    }

  private def processNextEventTick(tick: Tick): Unit =
    state.scheduledActors.get(tick) match
      case Some(scheduled) =>
        scheduled.actorsRef.foreach {
          actor => state.runningEvents.add(actor)
        }
        sendSpontaneousEvent(tick, scheduled.actorsRef)
        state.scheduledActors.remove(tick)
      case None =>
        advanceToNextTick()
        reportGlobalTimeManager()

  private def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[Identify]): Unit = {
    if (tick % 500 == 0) {
      logInfo(s"Send spontaneous at tick $tick to ${actorsRef.size} actors")
    }
    actorsRef.foreach {
      actor =>
        sendSpontaneousEvent(tick, actor)
    }
  }

  private def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    state.eventsAmount += 1
    if (identity.actorType == CreationTypeEnum.PoolDistributed.toString) {
      sendSpontaneousEventPool(tick, identity)
    } else {
      sendSpontaneousEventShard(tick, identity)
    }
  }

  private def sendSpontaneousEventShard(tick: Tick, identity: Identify): Unit =
    getShardRef(StringUtil.getModelClassName(identity.classType)) ! EntityEnvelopeEvent(
      identity.id,
      SpontaneousEvent(
        tick = tick,
        actorRef = self
      )
    )

  private def sendSpontaneousEventPool(tick: Tick, identity: Identify): Unit =
    getActorPoolRef(identity.id) ! SpontaneousEvent(
      tick = tick,
      actorRef = self
    )

  private def advanceToNextTick(): Unit = {
    val newTick = nextTick
    if (state.localTickOffset < simulationDuration) {
      state.localTickOffset = newTick
      state.scheduledTicksOnFinish.clear()
    } else {
      terminateSimulation()
    }
  }

  private def nextTick: Tick = {
    if (state.runningEvents.isEmpty) {
      return (state.scheduledActors.nonEmpty, state.runningEvents.isEmpty) match {
        case (true, true) =>
          val newScheduled = state.scheduledTicksOnFinish.minOption.getOrElse(state.localTickOffset + 1)
          val currentScheduled = state.scheduledActors.keys.minOption.getOrElse(state.localTickOffset + 1)
          if (newScheduled < currentScheduled) {
            newScheduled
          } else {
            currentScheduled
          }
        case _ => state.scheduledTicksOnFinish.minOption.getOrElse(state.localTickOffset + 1)
      }
    }
    state.localTickOffset
  }

  private def terminateSimulation(): Unit = {
    if (state.runningEvents.isEmpty && state.scheduledActors.isEmpty) {
      if (parentManager.isEmpty) {
        notifyLocalManagers(StopSimulationEvent)
      }
      context.stop(self)
    }
    printSimulationDuration()
  }

  private def sendDestructEvent(finishEvent: FinishEvent): Unit =
    getShardRef(finishEvent.identify.classType) ! EntityEnvelopeEvent(
      finishEvent.identify.id,
      DestructEvent(tick = state.localTickOffset, actorRef = getPath)
    )

  private def printSimulationDuration(): Unit = {
    val duration = System.currentTimeMillis() - state.startTime
    logInfo(s"$getLabel - Simulation endTick: ${state.localTickOffset}")
    logInfo(s"$getLabel - Simulation total ticks: ${state.localTickOffset - state.initialTick}")
    logInfo(s"$getLabel - Simulation duration: $duration ms")
    logInfo(s"$getLabel - Simulation duration: ${duration / 1000.0} s")
    logInfo(s"$getLabel - Simulation duration: ${duration / 1000.0 / 60.0} min")
    logInfo(s"$getLabel - Simulation duration: ${duration / 1000.0 / 60.0 / 60.0} h")
    
    if (parentManager.isEmpty && duration > 0) {
      val seconds = duration / 1000.0
      val throughput = ((state.localTickOffset - state.initialTick) * 1000.0 / duration).toInt
      logInfo(s"$getLabel - Average throughput: $throughput ticks/s")
      logInfo(s"$getLabel - Total events: ${state.totalEventsAmount}")
      logInfo(s"$getLabel - Events per second: ${(state.totalEventsAmount / seconds).toLong}")
      
      // Print GPS cache statistics
      logInfo(s"$getLabel - GPS Route Cache Statistics:")
      model.mobility.util.GPSUtilWithCache.printCacheStats()
      
      // Print speed calculation statistics
      logInfo(s"$getLabel - Link Speed Calculation Statistics:")
      model.mobility.util.SpeedUtil.printSpeedCalculationStats()
      
      // Print link message statistics
      logInfo(s"$getLabel - Link Message Statistics:")
      model.mobility.util.LinkMessageStats.printStats()
    }
  }

  private def isRunning: Boolean = !state.isPaused && !state.isStopped

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
