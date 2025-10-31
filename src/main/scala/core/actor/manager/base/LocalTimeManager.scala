package org.interscity.htc
package core.actor.manager.base

import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.types.Tick
import core.entity.control.ScheduledActors

import org.apache.pekko.actor.{ ActorRef, Props }
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution._
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.enumeration.{ CreationTypeEnum, LocalTimeManagerTypeEnum }

import scala.collection.mutable

/** Abstract base class for all Local Time Manager implementations.
  *
  * LocalTimeManager extends BaseTimeManager to provide specialized functionality for managing time
  * coordination at the local level within different simulation paradigms. Local Time Managers are
  * responsible for coordinating actors within their assigned simulation strategy (DES,
  * Time-Stepped, Optimistic) and communicating with the Global Time Manager for distributed
  * coordination.
  *
  * This class implements common Local Time Manager functionality including:
  *   - Registration with Global Time Manager
  *   - Actor coordination and event scheduling
  *   - Time synchronization with global simulation clock
  *   - Event processing delegation to specific implementations
  *
  * @param simulationDuration
  *   Maximum duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the main simulation manager
  * @param parentManager
  *   Reference to the Global Time Manager for coordination
  * @param timeManagerType
  *   The type of time management strategy (DES, TimeStepped, etc.)
  *
  * @author
  *   Hyperbolic Time Chamber Team
  * @version 1.4.0
  * @since 1.0.0
  */
abstract class LocalTimeManager(
  simulationDuration: Tick,
  simulationManager: ActorRef,
  protected val parentManager: ActorRef,
  protected val timeManagerType: LocalTimeManagerTypeEnum
) extends BaseTimeManager(
      simulationDuration = simulationDuration,
      simulationManager = simulationManager,
      actorId = s"${timeManagerType.toString}-LTM-${System.nanoTime()}"
    ) {

  /** Map of scheduled actors organized by simulation tick. Each tick contains a ScheduledActors
    * object with actors to be executed at that time.
    */
  protected val scheduledActors = mutable.Map[Tick, ScheduledActors]()

  /** Set of ticks that have been marked for finish event processing. Used to track which simulation
    * ticks have completed execution.
    */
  protected val scheduledTicksOnFinish = mutable.Set[Tick]()

  /** Set of currently running events identified by their Identify objects. Used to track active
    * events and prevent duplicate processing.
    */
  protected val runningEvents = mutable.Set[Identify]()

  /** Counter for the total number of events that have been scheduled. Used for statistics and
    * performance monitoring.
    */
  protected var countScheduled = 0

  /** Called when the Local Time Manager starts up.
    *
    * Performs parent class initialization and registers this LTM with the Global Time Manager for
    * coordination.
    */
  override def onStart(): Unit = {
    super.onStart()
    registerWithParent()
  }

  /** Registers this Local Time Manager with the Global Time Manager.
    *
    * Sends a registration event to the parent Global Time Manager to establish the coordination
    * relationship and enable distributed time management.
    */
  private def registerWithParent(): Unit = {
    parentManager ! TimeManagerRegisterEvent(
      actorRef = self,
      localTimeManagerType = timeManagerType
    )
    logInfo(s"LTM ${timeManagerType} registered to Global Time Manager")
  }

  /** Specific event receiver for Local Time Manager implementations.
    *
    * Handles events common to all LTM types including actor registration, event scheduling, finish
    * events, global time synchronization, and delegates specific events to implementation handlers.
    *
    * @return
    *   Partial function for handling LTM-specific events
    */
  override protected def specificReceive: Receive = {
    case register: RegisterActorEvent => registerActor(register)
    case schedule: ScheduleEvent      => scheduleEvent(schedule)
    case finish: FinishEvent          => finishEvent(finish)
    case e: UpdateGlobalTimeEvent     => syncWithGlobalTime(e.tick)
    case localTimeReport: LocalTimeReportEvent =>
      handleLocalTimeReport(sender(), localTimeReport.tick, localTimeReport.hasScheduled)
    case event => handleSpecificEvent(event)
  }

  // ==================== COMMON LTM METHODS ====================

  /** Registers an actor with this Local Time Manager.
    *
    * Adds the actor to the registered actors collection and logs the registration. The actor will
    * be managed by this LTM for time coordination.
    *
    * @param event
    *   The registration event containing actor details
    */
  protected def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorId)

    if (event.identify.isDefined) {
      scheduleEvent(
        ScheduleEvent(
          tick = event.startTick,
          actorRef = event.actorId,
          identify = event.identify
        )
      )
    }

    onActorRegistered(event)
  }

  /** Schedules an event for future execution (primarily used by DES).
    *
    * This method handles event scheduling by organizing events by their target tick and maintaining
    * collections of actors to be executed at specific simulation times. Only works if the LTM
    * implementation accepts scheduling (acceptsScheduling returns true).
    *
    * @param schedule
    *   The scheduling event containing tick, actor reference, and identification
    */
  protected def scheduleEvent(schedule: ScheduleEvent): Unit =
    if (acceptsScheduling) {
      countScheduled += 1
      scheduledActors.get(schedule.tick) match {
        case Some(scheduled) =>
          schedule.identify.foreach(scheduled.actorsRef.add)
        case None =>
          scheduledActors.put(
            schedule.tick,
            ScheduledActors(
              tick = schedule.tick,
              actorsRef = mutable.Set(schedule.identify.get)
            )
          )
      }
      logDebug(s"Event scheduled for tick: ${schedule.tick}")
      onEventScheduled(schedule)
    } else {
      logDebug(s"${timeManagerType} this time manager not accept scheduling - event ignored")
    }

  /** Processes the completion of an event.
    *
    * This method handles event finalization by removing the event from the running events
    * collection and optionally scheduling the next event if specified. It ensures proper cleanup
    * and continuation of the simulation flow.
    *
    * @param finish
    *   The finish event containing completion details and optional next scheduling
    */
  protected def finishEvent(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      logDebug(s"Finalizando evento do ator ${finish.identify.id}")

      runningEvents.filterInPlace(_.id != finish.identify.id)

      finish.scheduleTick.map(_.toLong).foreach {
        nextTick =>
          scheduledTicksOnFinish.add(nextTick)
          scheduleEvent(
            ScheduleEvent(
              tick = nextTick,
              actorRef = finish.identify.id,
              identify = Some(finish.identify)
            )
          )
      }

      if (finish.destruct) {
        registeredActors.remove(finish.identify.id)
        sendDestructEvent(finish)
      }

      onEventFinished(finish)
      reportToGlobalTimeManager()
    } else {
      finish.timeManager ! finish
    }

  /** Synchronizes with the global time manager.
    *
    * Updates the local time offset to match the global simulation time and calls the
    * implementation-specific synchronization handler.
    *
    * @param globalTick
    *   The current global simulation tick to synchronize with
    */
  protected def syncWithGlobalTime(globalTick: Tick): Unit = {
    localTickOffset = globalTick
    tickOffset = globalTick - initialTick

    if (isRunning) {
      if (localTickOffset - initialTick >= simulationDuration) {
        terminateSimulation()
      } else {
        advanceToNextTick()
      }
    }
  }

  /** Avança para o próximo tick
    */
  protected def advanceToNextTick(): Unit = {
    val newTick = getNextTick
    if (localTickOffset < simulationDuration) {
      scheduledTicksOnFinish.clear()
      processTick(newTick)
    } else {
      terminateSimulation()
    }
  }

  /** Processa um tick específico
    */
  protected def processTick(tick: Tick): Unit =
    if (scheduledActors.nonEmpty) {
      processScheduledEvents(tick)
    } else {
      reportToGlobalTimeManager()
    }

  /** Processa eventos agendados em um tick
    */
  protected def processScheduledEvents(tick: Tick): Unit =
    scheduledActors.get(tick) match {
      case Some(scheduled) =>
        logDebug(s"Processando ${scheduled.actorsRef.size} eventos no tick $tick")

        // Mover para execução
        scheduled.actorsRef.foreach(runningEvents.add)

        // Enviar eventos
        sendEventsToActors(tick, scheduled.actorsRef)

        // Remover da agenda
        scheduledActors.remove(tick)

      case None =>
        logDebug(s"Nenhum evento agendado para tick $tick")
        reportToGlobalTimeManager()
    }

  /** Envia eventos para os atores
    */
  protected def sendEventsToActors(tick: Tick, actors: mutable.Set[Identify]): Unit =
    actors.foreach {
      actor =>
        sendEventToActor(tick, actor)
    }

  /** Envia evento para um ator específico
    */
  protected def sendEventToActor(tick: Tick, identity: Identify): Unit = {
    val event = createEventForActor(tick, identity)

    if (identity.actorType == CreationTypeEnum.PoolDistributed.toString) {
      sendToPool(identity.id, event)
    } else {
      sendToShard(identity.classType, identity.id, event)
    }
  }

  /** Reporta estado para o Global Time Manager
    */
  protected def reportToGlobalTimeManager(): Unit =
    if (runningEvents.isEmpty) {
      parentManager ! LocalTimeReportEvent(
        tick = localTickOffset,
        hasScheduled = hasScheduledEvents
      )
    }

  /** Envia evento de destruição
    */
  protected def sendDestructEvent(finishEvent: FinishEvent): Unit =
    sendToShard(
      finishEvent.identify.classType,
      finishEvent.identify.id,
      DestructEvent(tick = localTickOffset, actorRef = getPath)
    )

  /** Verifica se há eventos agendados
    */
  protected def hasScheduledEvents: Boolean =
    scheduledActors.nonEmpty || runningEvents.nonEmpty

  /** Obtém próximo tick
    */
  protected def getNextTick: Tick =
    if (runningEvents.isEmpty) {
      if (scheduledActors.nonEmpty) {
        scheduledActors.keys.min
      } else {
        localTickOffset + 1
      }
    } else {
      localTickOffset
    }

  /** Handle para relatório de tempo local
    */
  protected def handleLocalTimeReport(sender: ActorRef, tick: Tick, hasScheduled: Boolean): Unit =
    // Local Time Managers normalmente não recebem este evento
    logWarn(s"LocalTimeReport recebido por LTM: $tick, hasScheduled: $hasScheduled")

  override protected def getLabel: String = s"LocalTimeManager-${timeManagerType}"

  override def actSpontaneous(spontaneous: SpontaneousEvent): Unit =
    if (isRunning) {
      logDebug(s"Evento espontâneo recebido no tick ${spontaneous.tick}")
      onSpontaneousEvent(spontaneous)
    }

  // ==================== ABSTRACT METHODS (IMPLEMENTATION-SPECIFIC) ====================

  /** Determines if this Local Time Manager accepts event scheduling.
    *
    * Different time management strategies have different scheduling capabilities:
    *   - DES accepts scheduling (event-driven)
    *   - Time-Stepped typically does not (step-driven)
    *   - Optimistic may accept speculative scheduling
    *
    * @return
    *   true if this LTM supports event scheduling, false otherwise
    */
  protected def acceptsScheduling: Boolean

  /** Creates an event to be sent to a specific actor.
    *
    * Each time management strategy may need to create different types of events for actor
    * coordination. This method allows implementations to customize the event creation based on
    * their specific requirements.
    *
    * @param tick
    *   The simulation tick for the event
    * @param identity
    *   The identity information of the target actor
    * @return
    *   The event object to be sent to the actor
    */
  protected def createEventForActor(tick: Tick, identity: Identify): Any

  /** Handles events specific to the LTM implementation type.
    *
    * This method processes events that are unique to each time management strategy (e.g.,
    * AdvanceToTick for Time-Stepped, OptimisticEvent for Optimistic).
    *
    * @param event
    *   The specific event to be handled by this LTM type
    */
  protected def handleSpecificEvent(event: Any): Unit

  // ==================== OPTIONAL LIFECYCLE HOOKS ====================

  /** Called when an actor is registered with this LTM.
    *
    * Optional hook for implementations to perform additional setup when a new actor joins this time
    * manager's coordination.
    *
    * @param event
    *   The registration event containing actor details
    */
  protected def onActorRegistered(event: RegisterActorEvent): Unit = {}

  /** Called when an event is scheduled.
    *
    * Optional hook for implementations to track or react to event scheduling activities.
    *
    * @param schedule
    *   The scheduling event details
    */
  protected def onEventScheduled(schedule: ScheduleEvent): Unit = {}

  /** Called when an event finishes execution.
    *
    * Optional hook for implementations to perform cleanup or additional processing when events
    * complete.
    *
    * @param finish
    *   The finish event details
    */
  protected def onEventFinished(finish: FinishEvent): Unit = {}

  /** Called when a spontaneous event is received.
    *
    * Optional hook for implementations to handle spontaneous simulation events according to their
    * strategy.
    *
    * @param spontaneous
    *   The spontaneous event details
    */
  protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit = {}
}
