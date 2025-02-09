package org.interscity.htc
package core.actor.manager

import core.actor.BaseActor
import core.entity.event.{ FinishEvent, ScheduleEvent, SpontaneousEvent }
import core.types.CoreTypes.Tick

import org.apache.pekko.actor.ActorRef
import core.entity.control.ScheduledActors
import core.entity.event.control.execution.{ AcknowledgeTickEvent, DestructEvent, LocalTimeReportEvent, PauseSimulationEvent, RegisterActorEvent, ResumeSimulationEvent, StartSimulationEvent, StopSimulationEvent, UpdateGlobalTimeEvent }

import core.entity.state.DefaultState

import scala.collection.mutable

class TimeManager(
  val simulationDuration: Tick,
  val parentManager: Option[ActorRef]
) extends BaseActor[DefaultState](
      timeManager = null,
      actorId = "time-manager",
      data = null,
      dependencies = mutable.Map.empty
    ) {

  private var startTime: Long = 0
  private var localTickOffset: Tick = 0
  private var tickOffset: Tick = 0
  private var initialTick: Tick = 0
  private var isPaused: Boolean = false
  private var isStopped: Boolean = false

  private var tickAcknowledge: Long = 0
  private val registeredActors = mutable.Set[ActorRef]()
  private val scheduledActors = mutable.Map[Tick, ScheduledActors]()
  private val runningEvents = mutable.Set[ActorRef]()

  private val localTimeManagers: mutable.Map[ActorRef, Tick] = mutable.Map()

  override def handleEvent: Receive = {
    case start: StartSimulationEvent       => startSimulation(start)
    case register: RegisterActorEvent      => registerActor(register)
    case schedule: ScheduleEvent           => if (isRunning) scheduleApply(schedule)
    case finish: FinishEvent               => if (isRunning) finishEventApply(finish)
    case spontaneous: SpontaneousEvent     => if (isRunning) actSpontaneous(spontaneous)
    case PauseSimulationEvent              => if (isRunning) pauseSimulation()
    case ResumeSimulationEvent             => resumeSimulation()
    case StopSimulationEvent               => stopSimulation()
    case UpdateGlobalTimeEvent(tick)       => syncWithGlobalTime(tick)
    case acknowledge: AcknowledgeTickEvent => handleAcknowledgeTick(acknowledge)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(sender(), localTimeReport.tick)
  }

  private def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorRef)
    scheduleApply(ScheduleEvent(tick = event.startTick, actorRef = event.actorRef))
  }

  private def startSimulation(start: StartSimulationEvent): Unit = {
    start.logEvent(context, self)
    startTime = start.data.startTime
    initialTick = start.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    self ! SpontaneousEvent(tick = localTickOffset, actorRef = self)
    if (parentManager.isEmpty) {
      notifyLocalManagers(SpontaneousEvent(tick = localTickOffset, actorRef = self))
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
  }

  private def handleLocalTimeReport(localManager: ActorRef, tick: Tick): Unit =
    if (parentManager.isEmpty) {
      localTimeManagers.update(localManager, tick)
      if (localTimeManagers.size == localTimeManagers.keys.size) {
        calculateAndBroadcastNextGlobalTick()
      }
    }

  private def calculateAndBroadcastNextGlobalTick(): Unit = {
    val nextTick = localTimeManagers.values.min
    localTickOffset = nextTick
    tickOffset = nextTick - initialTick
    notifyLocalManagers(UpdateGlobalTimeEvent(localTickOffset))
  }

  private def notifyLocalManagers(event: Any): Unit =
    localTimeManagers.keys.foreach {
      localManager =>
        localManager ! event
    }

  private def scheduleApply(schedule: ScheduleEvent): Unit = {
    if (schedule.tick < localTickOffset) {
      log.warning(s"Schedule event for past tick ${schedule.tick}")
      return
    }
    schedule.logEvent(context, schedule.actorRef)
    scheduledActors.get(schedule.tick) match
      case Some(scheduled) =>
        scheduled.actorsRef.add(schedule.actorRef)
      case None =>
        scheduledActors.put(
          schedule.tick,
          ScheduledActors(tick = schedule.tick, actorsRef = mutable.Set(schedule.actorRef))
        )
  }

  private def finishEventApply(finish: FinishEvent): Unit = {
    finish.logEvent(context, self)
    runningEvents.remove(finish.actorRef)
    finish.scheduleEvent.foreach(scheduleApply)
  }

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit =
    if (isRunning) {
      if (localTickOffset - initialTick >= simulationDuration) {
        terminateSimulation()
      } else {
        processNextEvent(spontaneous)
      }
    }

  private def processNextEvent(spontaneous: SpontaneousEvent): Unit =
    if (scheduledActors.nonEmpty) {
      processNextEventTick(spontaneous.tick)
      localTickOffset = nextTick
      notifyManagers()
      self ! SpontaneousEvent(tick = localTickOffset, actorRef = self)
    } else {
      advanceToNextTick()
    }

  private def notifyManagers(): Unit =
    if (parentManager.isDefined) {
      parentManager.get ! UpdateGlobalTimeEvent(tick = localTickOffset)
    } else {
      notifyLocalManagers(LocalTimeReportEvent(tick = localTickOffset, actorRef = self))
    }

  private def processNextEventTick(tick: Tick): Unit =
    scheduledActors.get(tick) match
      case Some(scheduled) =>
        scheduled.actorsRef.foreach {
          actor =>
            runningEvents.add(actor)
        }
        logEvent(
          s"Sending spontaneous event to ${scheduled.actorsRef.size} scheduled actors at tick $tick"
        )
        sendSpontaneousEvent(tick, scheduled.actorsRef ++ runningEvents)
        scheduledActors.remove(tick)
      case None =>
        sendSpontaneousEvent(tick, runningEvents)
        logEvent(s"No scheduled actors for tick $tick")

  private def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[ActorRef]): Unit =
    actorsRef.foreach {
      actor =>
        actor ! SpontaneousEvent(tick = tick, actorRef = self)
    }

  private def handleAcknowledgeTick(acknowledge: AcknowledgeTickEvent): Unit =
    tickAcknowledge = tickAcknowledge + 1

  private def advanceToNextTick(): Unit = {
    val newTick = nextTick
    if (localTickOffset < simulationDuration) {
      localTickOffset = newTick
      self ! SpontaneousEvent(tick = newTick, actorRef = self)
    } else {
      terminateSimulation()
    }
  }

  private def nextTick: Tick = {
    if (runningEvents.isEmpty || tickAcknowledge >= runningEvents.size) {
      tickAcknowledge = 0
      return (scheduledActors.nonEmpty, runningEvents.nonEmpty) match {
        case (true, false)
            if !scheduledActors.contains(
              localTickOffset
            ) => // Verifica se o tick atual já foi processado
          scheduledActors.keys.minOption.getOrElse(localTickOffset + 1)
        case _ => localTickOffset + 1
      }
    }
    localTickOffset
  }

  private def terminateSimulation(): Unit = {
    if (tickAcknowledge >= runningEvents.size && scheduledActors.isEmpty) { // Verifica se scheduledActors está vazio
      terminateAllActors()
      if (parentManager.isEmpty) {
        notifyLocalManagers(StopSimulationEvent)
      }
      context.stop(self)
    }
    printSimulationDuration()
  }

  private def terminateAllActors(): Unit =
    registeredActors.foreach {
      actor =>
        actor ! DestructEvent(tick = localTickOffset, actorRef = self)
    }

  private def printState(): Unit = {
    log.info(s"runningEvents: ${runningEvents.size}")
    log.info(s"scheduledActors: ${scheduledActors.size}")
    log.info(s"tickAcknowledge: $tickAcknowledge")
    log.info(s"localTickOffset: $localTickOffset")
    log.info(s"tickOffset: $tickOffset")
  }

  private def printSimulationDuration(): Unit = {
    val duration = System.currentTimeMillis() - startTime
    log.info(s"Simulation endTick: $localTickOffset")
    log.info(s"Simulation total ticks: ${localTickOffset - initialTick}")
    log.info(s"Simulation duration: $duration ms")
    log.info(s"Simulation duration: ${duration / 1000} s")
    log.info(s"Simulation duration: ${duration / 1000 / 60} min")
    log.info(s"Simulation duration: ${duration / 1000 / 60 / 60} h")
  }

  private def isRunning: Boolean = !isPaused && !isStopped
}
