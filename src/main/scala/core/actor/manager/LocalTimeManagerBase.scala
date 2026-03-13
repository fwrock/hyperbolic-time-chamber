package org.interscity.htc
package core.actor.manager

import core.entity.control.ScheduledActors
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.entity.event.EntityEnvelopeEvent
import core.types.Tick

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.cluster.sharding.ShardRegion
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, LocalTimeReportEvent, RegisterActorEvent, StartSimulationTimeEvent, UpdateGlobalTimeEvent }
import org.interscity.htc.core.entity.event.control.execution.TimeManagerRegisterEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.util.{ IdUtil, StringUtil }

import scala.collection.mutable

/** Base abstract class for local time managers.
  * Local time managers handle the actual execution of simulation events
  * and report progress back to the global time manager.
  * 
  * @param simulationDuration The total duration of the simulation in ticks
  * @param simulationManager Reference to the simulation manager
  * @param parentManager Reference to the global time manager
  */
abstract class LocalTimeManagerBase(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef],
  actorId: String
) extends TimeManagerBase(
      timeManager = null,
      actorId = actorId
    ) with MicroAwareTimeManager {

  protected var countScheduled = 0
  private var selfProxy: ActorRef = null
  @volatile private var isTerminated = false
  private val registeredIdentities: mutable.Map[String, Identify] = mutable.Map()

  override def onStart(): Unit = {
    if (parentManager.nonEmpty) {
      // Register this specific instance with the global manager
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }
  }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case finish: FinishEvent             => finishEvent(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) onSpontaneousEvent(spontaneous)
    case e: UpdateGlobalTimeEvent        => syncWithGlobalTime(e.tick)
    case _: org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent =>
      stopSimulation()
      forceDestructActiveActors()
      terminateSimulation()
    case event                           => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Local TimeManager started at tick ${event.startTick}")
    event.data.foreach(data => startTime = data.startTime)
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    self ! UpdateGlobalTimeEvent(localTickOffset)
  }

  protected def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorId)
    event.identify.foreach { identity =>
      registeredIdentities.put(event.actorId, identity)
    }
    scheduleEvent(
      ScheduleEvent(tick = event.startTick, actorRef = event.actorId, identify = event.identify)
    )
  }

  protected def scheduleEvent(event: ScheduleEvent): Unit = {
    countScheduled += 1
    // CRITICAL: If the requested tick is in the past (< localTickOffset), it has already
    // been processed and removed from scheduledActors by processTick. Any entry added at
    // that past tick is orphaned — nextTick filters out ticks < localTickOffset, so they
    // are never dispatched again. This happens when ScheduleEvent is routed via the pool
    // router to a TM that has already advanced past the requested tick (e.g. from MICRO
    // link handleEnterLinkMicro calling scheduleEvent while a "fast" TM receives it).
    // Use <= localTickOffset (not just <) to also guard against same-tick races where
    // processTick(T) already cleared scheduledActors[T] but localTickOffset is still T.
    // Fix: bump past-tick/same-tick requests to localTickOffset + 1, guaranteeing future processing.
    val effectiveTick = if (event.tick <= localTickOffset) {
      logDebug(s"[TM] ScheduleEvent tick=${event.tick} is at/behind localTickOffset=$localTickOffset; bumping to ${localTickOffset + 1}")
      localTickOffset + 1
    } else {
      event.tick
    }
    val actorsSet = scheduledActors.getOrElseUpdate(effectiveTick, mutable.Set[Identify]())
    event.identify.foreach(actorsSet.add)
  }

  protected def finishEvent(finish: FinishEvent): Unit = {
    if (finish.timeManager == self) {
      finish.scheduleTick.map(_.toLong).foreach(scheduledTicksOnFinish.add)
      // Track whether this actor was actually processing a spontaneous event.
      // Only spontaneous-event completions should trigger tick advancement.
      // Calling onFinishSpontaneous from actInteractWith (e.g. handleMicroEnterLink) must
      // NOT falsely report hasScheduled=false to the global TM and terminate the simulation.
      val wasProcessingSpontaneousEvent = runningEvents.exists(_.id == finish.identify.id)
      runningEvents.filterInPlace(_.id != finish.identify.id)
      
      // If no scheduleTick provided (None), remove actor from ALL future scheduled ticks
      if (finish.scheduleTick.isEmpty) {
        val actorId = finish.identify.id
        val actorClass = finish.identify.classType
        var removedFromTicks = 0
        scheduledActors.foreach { case (tick, actors) =>
          val sizeBefore = actors.size
          actors.filterInPlace(_.id != actorId)
          if (actors.size < sizeBefore) removedFromTicks += 1
        }
        // Clean up empty tick entries
        scheduledActors.filterInPlace { case (_, actors) => actors.nonEmpty }
        if (removedFromTicks > 0) {
          logInfo(s"Unregistered ${actorClass} (${actorId}) from $removedFromTicks future ticks")
        }
      }
      
      finishDestruct(finish)
      // Only advance to the next tick if this actor was actually running a spontaneous event.
      // When onFinishSpontaneous is called from actInteractWith (not actSpontaneous), the actor
      // is NOT in runningEvents; advancing here would trigger a spurious hasScheduled=false
      // report to the global TM, causing premature simulation termination.
      if (wasProcessingSpontaneousEvent) {
        advanceToNextTick()
      }
    } else {
      finish.timeManager ! finish
    }
  }

  private def finishDestruct(finish: FinishEvent): Unit = {
    if (finish.destruct) {
      registeredActors.remove(finish.identify.id)
      registeredIdentities.remove(finish.identify.id)
      sendDestructEvent(finish)
    }
  }

  override protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit = {
    if (isRunning && !isTerminated) {
      processTick(spontaneous.tick)
    }
  }

  private def syncWithGlobalTime(globalTick: Tick): Unit = {
    if (globalTick % 10000 == 0) {
      logInfo(s"[LocalTM] Syncing with global tick $globalTick (previous localTick=$localTickOffset)")
    }
    localTickOffset = globalTick
    tickOffset = globalTick - initialTick
    if (isRunning && !isTerminated) {
      // Trigger micro links before processing regular events
      triggerMicroLinks(globalTick)
      processTick(localTickOffset)
    }
  }

  /** Processes a simulation tick. Subclasses implement specific time management strategies.
    * @param tick The tick to process
    */
  protected def processTick(tick: Tick): Unit

  /** Advances to the next simulation tick. */
  protected def advanceToNextTick(): Unit = {
    if (runningEvents.isEmpty) {
      nextTick match {
        case Some(tick) => 
          // Report to global and wait for sync instead of advancing locally
          reportGlobalTimeManager(hasScheduled = true)
        case None => 
          // No more events scheduled locally, report and wait
          reportGlobalTimeManager(hasScheduled = false)
      }
    }
  }

  protected def nextTick: Option[Tick] = {
    val scheduled = scheduledActors.keys.filter(_ >= localTickOffset)
    val scheduledOnFinish = scheduledTicksOnFinish.filter(_ >= localTickOffset)
    val allTicks = scheduled ++ scheduledOnFinish

    if (allTicks.nonEmpty) {
      Some(allTicks.min)
    } else {
      None
    }
  }

  /** Process next scheduled event at given tick.
    * NOTE: This is a utility method not directly used by current implementations.
    * See LocalDiscreteEventTimeManager and LocalTimeSteppedTimeManager for actual processTick logic.
    */
  protected def processNextEventTick(tick: Tick): Unit = {
    localTickOffset = tick
    scheduledActors.get(tick).foreach { actorsSet =>
      logInfo(s"[Legacy] processNextEventTick: tick $tick with ${actorsSet.size} scheduled actors")
      sendSpontaneousEvent(tick, actorsSet)
    }
    scheduledActors.remove(tick)
    scheduledTicksOnFinish.remove(tick)
  }

  protected def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[Identify]): Unit = {
    actorsRef.foreach { identity =>
      runningEvents.add(identity)
      sendSpontaneousEvent(tick, identity)
    }
  }

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    if (identity.actorType.isEmpty) {
      logWarn(s"Actor identity has empty actorType: $identity")
      return
    }
    
    CreationTypeEnum.valueOf(identity.actorType) match {
      case CreationTypeEnum.LoadBalancedDistributed =>
        sendSpontaneousEventShard(tick, identity)
      case CreationTypeEnum.PoolDistributed =>
        sendSpontaneousEventPool(tick, identity)
      case _ =>
        logWarn(s"Unknown creation type: ${identity.actorType} for actor ${identity.id}")
    }
  }

  private def sendSpontaneousEventShard(tick: Tick, identity: Identify): Unit = {
    val actorRef = getShardRef(StringUtil.getModelClassName(identity.classType))
    actorRef ! core.entity.event.EntityEnvelopeEvent(
      IdUtil.format(identity.id),
      SpontaneousEvent(tick = tick, actorRef = self)
    )
  }

  private def sendSpontaneousEventPool(tick: Tick, identity: Identify): Unit = {
    val actorRef = context.system.actorSelection(identity.actorRef)
    actorRef ! SpontaneousEvent(tick = tick, actorRef = self)
  }

  private def terminateSimulation(): Unit = synchronized {
    if (!isTerminated) {
      isTerminated = true
      printSimulationDuration()
      logInfo("Local simulation terminated")
      reportGlobalTimeManager(hasScheduled = false)
    }
  }

  private def forceDestructActiveActors(): Unit = {
    val identitiesToDestruct = mutable.Map[String, Identify]()

    scheduledActors.values.foreach { identities =>
      identities.foreach { identity =>
        identitiesToDestruct.put(identity.id, identity)
      }
    }

    runningEvents.foreach { identity =>
      identitiesToDestruct.put(identity.id, identity)
    }

    if (identitiesToDestruct.nonEmpty) {
      logInfo(s"Force-destructing ${identitiesToDestruct.size} active/scheduled actors on simulation stop")
    }

    identitiesToDestruct.values.foreach(sendDestructEvent)
  }

  protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit = {
    if (parentManager.nonEmpty) {
      // Report the NEXT tick we want to process (not current tick)
      val reportTick = if (hasScheduled) nextTick.getOrElse(localTickOffset) else localTickOffset
      parentManager.get ! LocalTimeReportEvent(
        tick = reportTick,
        hasScheduled = hasScheduled,
        actorRef = self.path.toString
      )
    }
  }

  private def sendDestructEvent(finishEvent: FinishEvent): Unit = {
    val actorRef = getActorRef(finishEvent.identify.actorRef)
    if (actorRef != null) {
      actorRef ! DestructEvent(actorRef = self.path.toString)
    }
  }

  private def sendDestructEvent(identity: Identify): Unit = {
    if (identity.actorType.isEmpty) {
      logWarn(s"Cannot destruct actor with empty actorType: ${identity.id}")
      return
    }

    CreationTypeEnum.valueOf(identity.actorType) match {
      case CreationTypeEnum.LoadBalancedDistributed =>
        val actorRef = getShardRef(StringUtil.getModelClassName(identity.classType))
        actorRef ! EntityEnvelopeEvent(
          IdUtil.format(identity.id),
          DestructEvent(actorRef = self.path.toString)
        )
      case CreationTypeEnum.PoolDistributed =>
        val actorRef = getActorRef(identity.actorRef)
        if (actorRef != null) {
          actorRef ! DestructEvent(actorRef = self.path.toString)
        }
      case _ =>
        logWarn(s"Unknown creation type on force-destruct: ${identity.actorType} for actor ${identity.id}")
    }
  }

  protected def getSelfProxy: ActorRef = {
    if (selfProxy == null) {
      selfProxy = self
      selfProxy
    } else {
      selfProxy
    }
  }
}
