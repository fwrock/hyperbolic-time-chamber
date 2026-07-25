package org.interscity.htc
package core.actor.manager.time

import core.entity.event.control.execution.TimeManagerRegisterEvent
import core.entity.event.control.execution.QueryNextTickEvent
import core.entity.event.{ EntityEnvelopeEvent, FinishEvent, SpontaneousEvent }
import core.enumeration.CreationTypeEnum
import core.types.Tick
import core.util.{ IdUtil, StringUtil }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.*
import org.interscity.htc.core.metrics.core.{ActorMetrics, TimeManagerMetrics}

import scala.collection.mutable
import scala.concurrent.duration.*
import java.nio.file.{ Files, Paths, StandardOpenOption }
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Base abstract class for local time managers. Local time managers handle the actual execution of
  * simulation events and report progress back to the global time manager.
  *
  * @param simulationDuration
  *   The total duration of the simulation in ticks
  * @param simulationManager
  *   Reference to the simulation manager
  * @param parentManager
  *   Reference to the global time manager
  */
abstract class LocalTimeManagerBase(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef],
  actorId: String
) extends TimeManagerBase(
      timeManager = null,
      actorId = actorId
    )
    with MicroAwareTimeManager {

  protected var countScheduled = 0
  private var selfProxy: ActorRef = null
  @volatile private var isTerminated = false
  private val registeredIdentities: mutable.Map[String, Identify] = mutable.Map()
  // Per-actor monotonically increasing dispatch counter. Incremented every time this TM actually
  // sends an actor a SpontaneousEvent; echoed back by the actor on its FinishEvent so finishEvent
  // can recognize and drop a FinishEvent belonging to a dispatch cycle already superseded by a
  // newer one (e.g. a redundant/late "poll again next tick" reschedule racing against the real
  // reply that resolves the same wait) instead of "rescuing" it into a phantom extra schedule.
  // See docs/KNOWN_GAPS.md's non-reproducibility investigation.
  private val dispatchGeneration: mutable.Map[String, Long] = mutable.Map().withDefaultValue(0L)

  override def onStart(): Unit =
    if (parentManager.nonEmpty) {
      // Register this specific instance with the global manager
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case finish: FinishEvent             => finishEvent(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) onSpontaneousEvent(spontaneous)
    case e: UpdateGlobalTimeEvent        => syncWithGlobalTime(e.tick)
    case QueryNextTickEvent              => reportGlobalTimeManager(hasScheduled = nextTick.isDefined)
    case _: org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent =>
      stopSimulation()
      forceDestructActiveActors()
      terminateSimulation()
    case event => super.handleEvent(event)
  }

  protected def startSimulation(event: StartSimulationTimeEvent): Unit = {
    logInfo(s"Local TimeManager started at tick ${event.startTick}")
    startTime = System.currentTimeMillis()
    initialTick = event.startTick
    localTickOffset = initialTick
    isPaused = false
    isStopped = false
    isTerminated = false
    self ! UpdateGlobalTimeEvent(localTickOffset)
  }

  protected def registerActor(event: RegisterActorEvent): Unit = {
    registeredActors.add(event.actorId)
    event.identify.foreach {
      identity =>
        registeredIdentities.put(event.actorId, identity)
        // Prometheus: track actor registration by type
        val actorType = identity.classType.split('.').lastOption.getOrElse(identity.classType)
        ActorMetrics.actorsRegistered.labels(actorType).inc()
        ActorMetrics.activeActors.labels(actorType).inc()
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
      logDebug(
        s"ScheduleEvent tick=${event.tick} is at/behind localTickOffset=$localTickOffset; bumping to ${localTickOffset + 1}"
      )
      localTickOffset + 1
    } else {
      event.tick
    }
    // RACE CONDITION FIX: Track whether this TM was previously idle (no scheduled actors
    // and no running events) before adding the new actor. When Person sends StartTrip to
    // Car and calls onFinishSpontaneous(None), the TM reports hasScheduled=false to GlobalTM.
    // Car then asynchronously calls scheduleEvent. Without re-notification, GlobalTM may decide
    // "no more events" and terminate before Car's spontaneous event is ever dispatched.
    val wasIdle = scheduledActors.isEmpty && runningEvents.isEmpty
    // Capture the LTM's current minimum scheduled tick before we add the new actor.
    // We need this to detect whether the new actor has an EARLIER tick than whatever
    // GTM already knows about for this LTM.
    val prevNextTick = nextTick
    val actorsSet = scheduledActors.getOrElseUpdate(effectiveTick, mutable.Set[Identify]())
    event.identify.foreach(actorsSet.add)
    // Re-notify GTM if:
    //   1. TM was idle before this actor arrived (existing wasIdle logic), OR
    //   2. The new actor's tick is EARLIER than the previously reported next-tick.
    //      Without this, if Car(tick=56) registers first (wasIdle → GTM entry tick=56)
    //      and SubwayStation(tick=1) registers next (LTM not idle → no report), GTM
    //      keeps tick=56 for this LTM and never wakes it at tick 1, causing a rewind.
    val isEarlierTick = !wasIdle && prevNextTick.exists(effectiveTick < _)
    if ((wasIdle || isEarlierTick) && runningEvents.isEmpty) {
      logDebug(
        s"scheduleEvent: re-notifying GTM (wasIdle=$wasIdle, isEarlierTick=$isEarlierTick, tick=$effectiveTick, actor=${event.identify.map(_.id).getOrElse("?")})"
      )
      reportGlobalTimeManager(hasScheduled = true)
    }
  }

  protected def finishEvent(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      ActorMetrics.eventsProcessed.labels("finish").inc()

      val currentGen = dispatchGeneration(finish.identify.id)
      if (!finish.destruct && finish.generation < currentGen) {
        // Stale FinishEvent: this actor has already been dispatched again under a newer
        // generation since this resolution was decided — e.g. a redundant/late "poll again next
        // tick" reschedule (sent alongside another call resolving the very same wait) that only
        // reaches the TM after the actor's own next dispatch already went out. Left unguarded,
        // such a straggler would fall into the "arrived too late for its tick" bump below and
        // create a genuinely extra, unintended schedule entry — the root cause of one class of
        // non-reproducible duplicate dispatch documented in docs/KNOWN_GAPS.md. Drop it here
        // instead: don't touch scheduledActors/runningEvents/advanceToNextTick, since those
        // reflect the actor's current, newer dispatch, which is still legitimately pending.
        logDebug(
          s"Dropping stale FinishEvent for ${finish.identify.id}: generation=${finish.generation} < current=$currentGen"
        )
        return
      }

      finish.scheduleTick.map(_.toLong).foreach(scheduledTicksOnFinish.add)
      
      finish.scheduleTick.foreach {
        tickStr =>
          val tick = tickStr.toLong
          val effectiveTick = if (tick <= localTickOffset) localTickOffset + 1 else tick
          val actorsSet = scheduledActors.getOrElseUpdate(effectiveTick, mutable.Set[Identify]())
          actorsSet.add(finish.identify)
      }

      val wasProcessingSpontaneousEvent = runningEvents.exists(_.id == finish.identify.id)
      runningEvents.filterInPlace(_.id != finish.identify.id)
      TimeManagerMetrics.tmRunningEvents.set(runningEvents.size.toDouble)

      if (finish.scheduleTick.isEmpty) {
        val actorId = finish.identify.id
        val actorClass = finish.identify.classType
        var removedFromTicks = 0
        scheduledActors.foreach {
          case (tick, actors) =>
            val sizeBefore = actors.size
            actors.filterInPlace(_.id != actorId)
            if (actors.size < sizeBefore) removedFromTicks += 1
        }
        scheduledActors.filterInPlace {
          case (_, actors) => actors.nonEmpty
        }
        if (removedFromTicks > 0) {
//          logDebug(s"Unregistered ${actorClass} (${actorId}) from $removedFromTicks future ticks")
        }
      }

      finishDestruct(finish)
      if (wasProcessingSpontaneousEvent) {
        advanceToNextTick()
      }
    } else {
      finish.timeManager ! finish
    }

  private def finishDestruct(finish: FinishEvent): Unit =
    if (finish.destruct) {
      ActorMetrics.eventsProcessed.labels("destruct").inc()
      registeredActors.remove(finish.identify.id)
      dispatchGeneration.remove(finish.identify.id)
      val removedIdentity = registeredIdentities.remove(finish.identify.id)
      // Prometheus: decrement active actors gauge
      removedIdentity.foreach {
        identity =>
          val actorType = identity.classType.split('.').lastOption.getOrElse(identity.classType)
          ActorMetrics.activeActors.labels(actorType).dec()
      }
      sendDestructEvent(finish)
    }

  override protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit =
    if (isRunning && !isTerminated) {
      processTick(spontaneous.tick)
    }

  private def syncWithGlobalTime(globalTick: Tick): Unit = {
    if (globalTick % 1000 == 0) {
      logInfo(
        s"[LocalTM] Syncing with global tick $globalTick (previous localTick=$localTickOffset)"
      )
    }
    // If startSimulation was never called (e.g. LTM joined the pool after the
    // StartSimulationTimeEvent broadcast), initialise startTime and initialTick lazily
    // so printSimulationDuration() reports a meaningful wall-clock value instead of
    // ~Unix-epoch-ms (currentTime - 0).
    if (startTime == 0L) {
      startTime = System.currentTimeMillis()
      initialTick = globalTick
      logDebug(s"[LocalTM] startTime lazily initialised at globalTick=$globalTick (StartSimulationTimeEvent may have been missed)")
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
    * @param tick
    *   The tick to process
    */
  protected def processTick(tick: Tick): Unit

  /** Advances to the next simulation tick. */
  protected def advanceToNextTick(): Unit =
    if (runningEvents.isEmpty) {
      nextTick match {
        case Some(tick) =>
          reportGlobalTimeManager(hasScheduled = true)
        case None =>
          reportGlobalTimeManager(hasScheduled = false)
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

  /** Process next scheduled event at given tick. NOTE: This is a utility method not directly used
    * by current implementations. See LocalDiscreteEventTimeManager and LocalTimeSteppedTimeManager
    * for actual processTick logic.
    */
  protected def processNextEventTick(tick: Tick): Unit = {
    localTickOffset = tick
    scheduledActors.get(tick).foreach {
      actorsSet =>
        logInfo(
          s"[Legacy] processNextEventTick: tick $tick with ${actorsSet.size} scheduled actors"
        )
        sendSpontaneousEvent(tick, actorsSet)
    }
    scheduledActors.remove(tick)
    scheduledTicksOnFinish.remove(tick)
  }

  protected def sendSpontaneousEvent(tick: Tick, actorsRef: mutable.Set[Identify]): Unit = {
    TimeManagerMetrics.tmScheduledActors.set(actorsRef.size.toDouble)
    ActorMetrics.eventsProcessed.labels("spontaneous").inc(actorsRef.size.toDouble)
    actorsRef.foreach {
      identity =>
        runningEvents.add(identity)
        sendSpontaneousEvent(tick, identity)
    }
  }

  protected def sendSpontaneousEvent(tick: Tick, identity: Identify): Unit = {
    if (identity.actorType.isEmpty) {
      logWarn(s"Actor identity has empty actorType, removing from runningEvents to prevent stall: $identity")
      runningEvents.filterInPlace(_.id != identity.id)
      return
    }

    // Bump this actor's dispatch generation before sending — see dispatchGeneration's doc.
    val generation = dispatchGeneration(identity.id) + 1
    dispatchGeneration(identity.id) = generation

    CreationTypeEnum.valueOf(identity.actorType) match {
      case CreationTypeEnum.LoadBalancedDistributed =>
        sendSpontaneousEventShard(tick, identity, generation)
      case CreationTypeEnum.PoolDistributed =>
        sendSpontaneousEventPool(tick, identity, generation)
      case _ =>
        logWarn(s"Unknown creation type '${identity.actorType}' for actor ${identity.id}, removing from runningEvents to prevent stall")
        runningEvents.filterInPlace(_.id != identity.id)
    }
  }

  private def sendSpontaneousEventShard(tick: Tick, identity: Identify, generation: Long): Unit = {
    val actorRef = getShardRef(StringUtil.getModelClassName(identity.classType))
    actorRef ! core.entity.event.EntityEnvelopeEvent(
      IdUtil.format(identity.id),
      SpontaneousEvent(tick = tick, actorRef = self, generation = generation)
    )
  }

  private def sendSpontaneousEventPool(tick: Tick, identity: Identify, generation: Long): Unit = {
    val actorRef = context.system.actorSelection(identity.actorRef)
    actorRef ! SpontaneousEvent(tick = tick, actorRef = self, generation = generation)
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

    scheduledActors.foreach {
      case (tick, identities) =>
        identities.foreach {
          identity =>
            identitiesToDestruct.put(identity.id, identity)
        }
    }

    runningEvents.foreach {
      identity =>
        identitiesToDestruct.put(identity.id, identity)
    }

    if (identitiesToDestruct.nonEmpty) {
      val typeSummary = identitiesToDestruct.values
        .groupBy(id => id.classType.split('.').lastOption.getOrElse(id.classType))
        .view.mapValues(_.size).toMap
        .map { case (t, n) => s"$t=$n" }.mkString(", ")

      logInfo(
        s"Force-destructing ${identitiesToDestruct.size} active/scheduled actors on simulation stop" +
        s" (localTickOffset=$localTickOffset). Types: [$typeSummary]"
      )

      try {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = s"/tmp/htc-force-destruct-$timestamp.log"
        val tickDetail = scheduledActors.toSeq.sortBy(_._1).map { case (tick, ids) =>
          val byType = ids.groupBy(id => id.classType.split('.').lastOption.getOrElse(id.classType)).view.mapValues(_.size).toMap
          s"tick=$tick(${byType.map { case (t, n) => s"$t=$n" }.mkString(",")})"
        }.mkString("\n")
        val content = s"localTickOffset=$localTickOffset\ntypes=[$typeSummary]\n\nScheduled ticks:\n$tickDetail\n"
        Files.writeString(Paths.get(fileName), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        logInfo(s"Force-destruct details written to $fileName")
      } catch {
        case e: Exception => logWarn(s"Could not write force-destruct details to file: ${e.getMessage}")
      }
    }

    identitiesToDestruct.values.foreach(sendDestructEvent)
  }

  protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit =
    if (parentManager.nonEmpty) {
      // Report the NEXT tick we want to process (not current tick)
      val reportTick = if (hasScheduled) nextTick.getOrElse(localTickOffset) else localTickOffset
      parentManager.get ! LocalTimeReportEvent(
        tick = reportTick,
        hasScheduled = hasScheduled,
        actorRef = self.path.toString
      )
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
        logWarn(
          s"Unknown creation type on force-destruct: ${identity.actorType} for actor ${identity.id}"
        )
    }
  }

  protected def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = self
      selfProxy
    } else {
      selfProxy
    }
}
