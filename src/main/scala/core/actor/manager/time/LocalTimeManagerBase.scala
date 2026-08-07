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

/** Base abstract class for local time managers, holding only what every synchronization strategy
  * needs regardless of whether it's conservative (barrier-gated, see [[ConservativeLocalTimeManager]])
  * or optimistic (Time Warp, see `docs/TIME_WARP_DESIGN.md`): actor registration/dispatch
  * bookkeeping and the mechanics of actually sending a `SpontaneousEvent`/`DestructEvent`. It does
  * *not* implement `advanceToNextTick`/`reportGlobalTimeManager` — those encode the barrier
  * protocol itself and are strategy-specific, left abstract here for the strategy base class to
  * provide.
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
  @volatile protected var isTerminated = false
  private val registeredIdentities: mutable.Map[String, Identify] = mutable.Map()
  private val dispatchGeneration: mutable.Map[String, Long] = mutable.Map().withDefaultValue(0L)

  private val highestProcessedTick: mutable.Map[String, Tick] = mutable.Map().withDefaultValue(-1L)

  override def onStart(): Unit =
    if (parentManager.nonEmpty) {
      parentManager.get ! TimeManagerRegisterEvent(actorRef = self)
    }

  override def handleEvent: Receive = {
    case start: StartSimulationTimeEvent => startSimulation(start)
    case register: RegisterActorEvent    => registerActor(register)
    case schedule: ScheduleEvent         => scheduleEvent(schedule)
    case finish: FinishEvent             => finishEvent(finish)
    case spontaneous: SpontaneousEvent   => if (isRunning) onSpontaneousEvent(spontaneous)
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
    val actorId = event.identify.map(_.id).getOrElse(event.actorRef)
    val actorWatermark = highestProcessedTick(actorId)
    val effectiveTick = if (event.tick <= actorWatermark) {
      logDebug(
        s"ScheduleEvent tick=${event.tick} for actor=$actorId is at/behind its own highestProcessedTick=$actorWatermark; bumping to ${actorWatermark + 1}"
      )
      actorWatermark + 1
    } else {
      event.tick
    }
    val wasIdle = scheduledActors.isEmpty && runningEvents.isEmpty
    val prevNextTick = nextTick
    val actorsSet = scheduledActors.getOrElseUpdate(effectiveTick, mutable.Set[Identify]())
    event.identify.foreach(actorsSet.add)
    val isEarlierTick = !wasIdle && prevNextTick.exists(effectiveTick < _)
    onActorRescheduled(effectiveTick, wasIdle, isEarlierTick)
  }

  /** Called after `scheduleEvent` records a new/updated schedule entry, so a strategy that needs
    * to re-notify its coordinator of newly-available work (the conservative barrier's re-notify;
    * see [[ConservativeLocalTimeManager]]) can do so. No-op by default — an optimistic strategy
    * dispatching without a barrier has nothing to re-notify here.
    */
  protected def onActorRescheduled(effectiveTick: Tick, wasIdle: Boolean, isEarlierTick: Boolean): Unit = ()

  protected def finishEvent(finish: FinishEvent): Unit =
    if (finish.timeManager == self) {
      ActorMetrics.eventsProcessed.labels("finish").inc()

      val currentGen = dispatchGeneration(finish.identify.id)
      if (!finish.destruct && finish.generation < currentGen) {
        logDebug(
          s"Dropping stale FinishEvent for ${finish.identify.id}: generation=${finish.generation} < current=$currentGen"
        )
        return
      }

      finish.scheduleTick.map(_.toLong).foreach(scheduledTicksOnFinish.add)

      finish.scheduleTick.foreach {
        tickStr =>
          val tick = tickStr.toLong
          val actorWatermark = highestProcessedTick(finish.identify.id)
          val effectiveTick = if (tick <= actorWatermark) {
            logDebug(
              s"FinishEvent scheduleTick=$tick for actor=${finish.identify.id} is at/behind its own highestProcessedTick=$actorWatermark; bumping to ${actorWatermark + 1}"
            )
            actorWatermark + 1
          } else {
            tick
          }
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
      highestProcessedTick.remove(finish.identify.id)
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

  /** Processes a simulation tick. Subclasses implement specific time management strategies.
    * @param tick
    *   The tick to process
    */
  protected def processTick(tick: Tick): Unit

  protected def nextTick: Option[Tick] = {
    val allTicks = scheduledActors.keys ++ scheduledTicksOnFinish

    if (allTicks.nonEmpty) {
      Some(allTicks.min)
    } else {
      None
    }
  }

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

    val generation = dispatchGeneration(identity.id) + 1
    dispatchGeneration(identity.id) = generation
    if (tick > highestProcessedTick(identity.id)) {
      highestProcessedTick(identity.id) = tick
    }

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

  /** Reports this LTM's progress to its coordinator. What "reports" means is strategy-specific —
    * the conservative barrier's blocking `LocalTimeReportEvent` handshake
    * (see [[ConservativeLocalTimeManager]]) vs. an eventual optimistic strategy's non-blocking LVT
    * report to a GVT coordinator (`docs/TIME_WARP_DESIGN.md` §3) — so it's left abstract here.
    */
  protected def reportGlobalTimeManager(hasScheduled: Boolean = false): Unit

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
