package org.interscity.htc
package core.actor.manager

import core.entity.control.ScheduledActors
import core.entity.event.{ FinishEvent, SpontaneousEvent }
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
    ) {

  protected var countScheduled = 0
  private var selfProxy: ActorRef = null
  @volatile private var isTerminated = false

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
    scheduleEvent(
      ScheduleEvent(tick = event.startTick, actorRef = event.actorId, identify = event.identify)
    )
  }

  protected def scheduleEvent(event: ScheduleEvent): Unit = {
    countScheduled += 1
    val actorsSet = scheduledActors.getOrElseUpdate(event.tick, mutable.Set[Identify]())
    event.identify.foreach(actorsSet.add)
  }

  protected def finishEvent(finish: FinishEvent): Unit = {
    if (finish.timeManager == self) {
      finish.scheduleTick.map(_.toLong).foreach(scheduledTicksOnFinish.add)
      runningEvents.filterInPlace(_.id != finish.identify.id)
      finishDestruct(finish)
      // Report to global and wait for next tick (don't advance locally)
      advanceToNextTick()
    } else {
      finish.timeManager ! finish
    }
  }

  private def finishDestruct(finish: FinishEvent): Unit = {
    if (finish.destruct) {
      registeredActors.remove(finish.identify.id)
      sendDestructEvent(finish)
    }
  }

  override protected def onSpontaneousEvent(spontaneous: SpontaneousEvent): Unit = {
    if (isRunning && !isTerminated) {
      processTick(spontaneous.tick)
    }
  }

  private def syncWithGlobalTime(globalTick: Tick): Unit = {
    localTickOffset = globalTick
    tickOffset = globalTick - initialTick
    if (isRunning && !isTerminated) {
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

  protected def processNextEventTick(tick: Tick): Unit = {
    localTickOffset = tick
    scheduledActors.get(tick).foreach { actorsSet =>
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

  protected def getSelfProxy: ActorRef = {
    if (selfProxy == null) {
      selfProxy = self
      selfProxy
    } else {
      selfProxy
    }
  }
}
