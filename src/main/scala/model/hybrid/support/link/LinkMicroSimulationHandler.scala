package org.interscity.htc
package model.hybrid.support.link

import core.types.Tick
import org.interscity.htc.model.hybrid.entity.event.data.{ MicroLeaveLinkData, MicroUpdateData }
import org.interscity.htc.model.hybrid.entity.state.LinkState
import org.interscity.htc.model.hybrid.micro.strategy.{
  DefaultMicroSimulationStrategy,
  LaneChangeStrategy,
  MicroSimulationStrategy,
  MicroVehicleUpdate,
  NoLaneChangeStrategy
}
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.hybrid.util.SpeedUtil

import scala.collection.mutable

/** Owns microscopic simulation strategies and executes per-global-tick sub-tick simulation.
  */
class LinkMicroSimulationHandler(
  entityIdFn:                  () => String,
  currentTickFn:               () => Tick,
  sendMessageFn:               (String, String, AnyRef, String) => Unit,
  getLinkStateFn:              () => LinkState,
  setLinkStateFn:              LinkState => Unit,
  getVehicleEntryTickFn:       String => Option[Tick],
  removeVehicleEntryTickFn:    String => Unit,
  getVehicleWaitingSecondsFn:  String => Double,
  removeVehicleWaitingSecondsFn: String => Unit,
  vehicleWaitingSeconds:       mutable.Map[String, Double],
  isMicroScheduledFn:          () => Boolean,
  setMicroScheduledFn:         Boolean => Unit,
  getSignalAtExitFn:           () => Option[TrafficSignalPhaseStateEnum],
  metricsReporter:             LinkMetricsReporter,
  logDebugFn:                  String => Unit
) {

  private val microSimulationStrategy: MicroSimulationStrategy = DefaultMicroSimulationStrategy()
  private val laneChangeStrategy: LaneChangeStrategy           = NoLaneChangeStrategy()

  def initializeMicroMode(): Unit = {
    logDebugFn(s"Initializing MICRO mode for link ${getLinkStateFn().from} -> ${getLinkStateFn().to}")
    val state = getLinkStateFn()
    if (state.vehiclesByLane.isEmpty) setLinkStateFn(state.initializeMicroLanes())

    val s = getLinkStateFn()
    microSimulationStrategy.initialize(
      linkLength    = s.length,
      speedLimit    = s.speedLimit,
      lanes         = s.lanes,
      microTimeStep = s.microTimeStep
    )
    laneChangeStrategy.initialize(
      linkLength = s.length,
      speedLimit = s.speedLimit,
      lanes      = s.lanes
    )
    logDebugFn(s"✓ MICRO mode initialized: id=${entityIdFn()} lanes=${s.lanes} length=${s.length}m timeStep=${s.microTimeStep}s")
  }

  def findVehicleLane(actorId: String): Option[Int] =
    getLinkStateFn().vehiclesByLane.collectFirst {
      case (laneId, queue) if queue.exists(_.actorId == actorId) => laneId
    }

  def findLeastOccupiedLane(): Int =
    microSimulationStrategy.selectEntryLane(
      vehiclesByLane = mutable.Map.from(getLinkStateFn().vehiclesByLane),
      vehicleId      = "",
      vehicleLength  = 4.5
    )

  def handleGlobalTick(tick: Tick): Unit = {
    val startNs = System.nanoTime()
    metricsReporter.ensureSummaryTick(tick)

    val state = getLinkStateFn()
    if (state.isMicroMode && state.totalVehiclesInMicro > 0) {
      val updates = microSimulationStrategy.executeSubTick(
        vehiclesByLane           = mutable.Map.from(state.vehiclesByLane),
        subTick                  = 0,
        tick                     = tick,
        linkLength               = state.length,
        speedLimit               = state.speedLimit,
        microTimeStep            = state.microTimeStep,
        microTicksPerGlobalTick  = state.microTicksPerGlobalTick,
        vehicleWaitingSeconds    = vehicleWaitingSeconds,
        signalAtExit             = getSignalAtExitFn()
      )
      updates.foreach { u =>
        if (u.reachedEnd) sendMicroLeaveLinkToVehicle(u)
        else sendMicroUpdateToVehicle(u)
      }
    }

    val freshState = getLinkStateFn()
    if (freshState.isMicroMode) {
      val vehicles  = freshState.vehiclesByLane.valuesIterator.flatMap(_.iterator).toVector
      val meanSpeed = if (vehicles.nonEmpty) vehicles.map(_.velocity).sum / vehicles.size else 0.0
      val congestionFactor = SpeedUtil.bprCongestionFactor(volume = vehicles.size.toDouble, capacity = freshState.capacity)
      setLinkStateFn(freshState.copy(currentSpeed = meanSpeed, congestionFactor = congestionFactor))
    }
    metricsReporter.maybePublishDynamicCost(tick)

    metricsReporter.tickProcessingDurationMs = (System.nanoTime() - startNs) / 1_000_000L
    metricsReporter.emitSumoSummaryStep(tick)
  }

  def sendMicroLeaveLinkToVehicle(update: MicroVehicleUpdate): Unit = {
    val state = getLinkStateFn()
    state.vehiclesByLane.foreach { case (_, q) => q.dequeueAll(_.actorId == update.vehicleId) }

    val accWaiting = getVehicleWaitingSecondsFn(update.vehicleId)
    removeVehicleWaitingSecondsFn(update.vehicleId)

    val entryTick    = getVehicleEntryTickFn(update.vehicleId).getOrElse(currentTickFn())
    val elapsedTicks = math.max(1L, currentTickFn() - entryTick + 1)
    val secondsPerGlobalTick = state.microTicksPerGlobalTick * state.microTimeStep
    val avgSpeed =
      if (elapsedTicks > 0 && secondsPerGlobalTick > 0)
        state.length / (elapsedTicks * secondsPerGlobalTick)
      else update.velocity

    sendMessageFn(
      update.vehicleId,
      update.shardId,
      MicroLeaveLinkData(
        linkId             = entityIdFn(),
        finalPosition      = update.position,
        finalVelocity      = update.velocity,
        travelTime         = elapsedTicks,
        distanceTraveled   = state.length,
        averageSpeed       = avgSpeed,
        waitingTimeSeconds = accWaiting
      ),
      "MicroLeaveLink"
    )

    if (getLinkStateFn().totalVehiclesInMicro == 0 && isMicroScheduledFn())
      setMicroScheduledFn(false)
  }

  def sendMicroUpdateToVehicle(update: MicroVehicleUpdate): Unit =
    sendMessageFn(
      update.vehicleId,
      update.shardId,
      MicroUpdateData(
        subTick        = update.subTick,
        position       = update.position,
        velocity       = update.velocity,
        acceleration   = update.acceleration,
        currentLane    = update.currentLane,
        leaderVehicle  = update.leaderVehicle,
        gapToLeader    = update.gapToLeader,
        leaderVelocity = update.leaderVelocity,
        safeVelocity   = update.safeVelocity
      ),
      "MicroUpdate"
    )
}
