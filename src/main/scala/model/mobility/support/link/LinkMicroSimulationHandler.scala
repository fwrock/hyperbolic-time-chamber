package org.interscity.htc
package model.mobility.support.link

import core.types.Tick
import org.interscity.htc.model.mobility.entity.event.data.{ MicroLeaveLinkData, MicroUpdateData }
import org.interscity.htc.model.mobility.entity.state.LinkState
import org.interscity.htc.model.mobility.micro.strategy.{
  DefaultMicroSimulationStrategy,
  MicroSimulationStrategy,
  MicroVehicleUpdate
}
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.util.SpeedUtil

import scala.collection.mutable

/** Owns microscopic simulation strategies and executes per-global-tick sub-tick simulation.
  */
class LinkMicroSimulationHandler(
  entityIdFn:                  () => String,
  currentTickFn:               () => Tick,
  sendMessageFn: (Long, String, AnyRef, String) => Unit,
  getLinkStateFn:              () => LinkState,
  setLinkStateFn:              LinkState => Unit,
  getVehicleEntryTickFn:       Long => Option[Tick],
  removeVehicleEntryTickFn:    Long => Unit,
  getVehicleWaitingSecondsFn:  Long => Double,
  removeVehicleWaitingSecondsFn: Long => Unit,
  vehicleWaitingSeconds:       mutable.Map[Long, Double],
  isMicroScheduledFn:          () => Boolean,
  setMicroScheduledFn:         Boolean => Unit,
  getSignalAtExitFn:           () => Option[TrafficSignalPhaseStateEnum],
  metricsReporter:             LinkMetricsReporter,
  logDebugFn:                  String => Unit,
  downstreamLeadersFn: Tick => Map[Long, model.mobility.entity.state.model.VehicleInLane] = _ => Map.empty,
  reportFn: (Map[String,Any],String) => Unit = (_,_) => (),
  downstreamLaneLeaderFn: Tick => (Long,Int,Long) => Option[model.mobility.entity.state.model.VehicleInLane] =
    _ => (_,_,_) => None
) {

  private val microSimulationStrategy: MicroSimulationStrategy = DefaultMicroSimulationStrategy()

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
    logDebugFn(s"✓ MICRO mode initialized: id=${entityIdFn()} lanes=${s.lanes} length=${s.length}m timeStep=${s.microTimeStep}s")
  }

  def findVehicleLane(actorId: Long): Option[Int] =
    getLinkStateFn().locateVehicle(actorId).map(_._1)

  def findLeastOccupiedLane(): Int =
    microSimulationStrategy.selectEntryLane(
      vehiclesByLane = mutable.Map.from(getLinkStateFn().vehiclesByLane),
      vehicleId      = 0L,
      vehicleLength  = 4.5
    )

  def handleGlobalTick(tick: Tick): Unit = {
    val startNs = System.nanoTime()
    metricsReporter.ensureSummaryTick(tick)

    val state = getLinkStateFn()
    if (state.isMicroMode && state.totalVehiclesInMicro > 0) {
      val observations = mutable.Map.empty[Long,Double]
      val updates = microSimulationStrategy.executeSubTick(
        vehiclesByLane           = mutable.Map.from(state.vehiclesByLane),
        subTick                  = 0,
        tick                     = tick,
        linkLength               = state.length,
        speedLimit               = state.speedLimit,
        microTimeStep            = state.microTimeStep,
        microTicksPerGlobalTick  = state.microTicksPerGlobalTick,
        vehicleWaitingSeconds    = vehicleWaitingSeconds,
        signalAtExit             = getSignalAtExitFn(),
        downstreamLeaders        = downstreamLeadersFn(tick),
        downstreamLaneLeader     = downstreamLaneLeaderFn(tick),
        stopLineGapM             = state.stopLineGapM,
        retainExits              = state.downstreamSpillback,
        laneChanging = state.laneChanging,
        laneConnections = state.laneConnections,
        laneRestrictions = state.laneConfigurations.map(c => c.laneId -> c.laneType.toString.toLowerCase).toMap,
        movementSignals = state.signalControls.flatMap(s =>
          (if (s.laneIds.isEmpty) List(-1) else s.laneIds).map(l => (s.targetLinkId,l) -> s.at(tick))).toMap,
        laneChangedFn = (vehicle,from,to,reason) => reportFn(Map("vehicle_id" -> vehicle,
          "link_id" -> entityIdFn(),"from_lane" -> from,"to_lane" -> to,"reason" -> reason,"tick" -> tick),"lane_change"),
        observedFn = (before,after,lane,start) => state.detectors.filter(_.laneId == lane).foreach { detector =>
          val end = detector.positionMeters + detector.lengthMeters
          if (after.position >= detector.positionMeters && before.rearPosition <= end) {
            val elapsed = if (after.rearPosition > end && after.position > before.position)
              state.microTimeStep * math.max(0.0,math.min(1.0,(end-before.rearPosition)/(after.position-before.position)))
            else state.microTimeStep
            val until = start + elapsed
            observations.update(detector.id,math.max(until,observations.getOrElse(detector.id,Double.NegativeInfinity)))
          }
        }
      )
      state.detectors.filter(d => observations.contains(d.id)).foreach { detector =>
        sendMessageFn(detector.controllerId,"mobility.actor.TrafficSignal",
          model.mobility.entity.event.data.signal.TrafficDetectionData(detector.id,observations(detector.id)),"TrafficDetection")
      }
      updates.foreach { u =>
        if (u.reachedEnd) sendMicroLeaveLinkToVehicle(u)
        else sendMicroUpdateToVehicle(u)
      }
      if (state.downstreamSpillback) metricsReporter.emitMicroExitWaits(tick)
    }

    val freshState = getLinkStateFn()
    if (freshState.isMicroMode) {
      var count = 0
      var speedSum = 0.0
      freshState.vehiclesByLane.valuesIterator.foreach(_.foreach { v => count += 1; speedSum += v.velocity })
      freshState.currentSpeed = if (count > 0) speedSum / count else 0.0
      freshState.congestionFactor = SpeedUtil.bprCongestionFactor(volume = count.toDouble, capacity = freshState.capacity)
    }
    metricsReporter.maybePublishDynamicCost(tick)

    metricsReporter.tickProcessingDurationMs = (System.nanoTime() - startNs) / 1_000_000L
    metricsReporter.emitSumoSummaryStep(tick)
  }

  def sendMicroLeaveLinkToVehicle(update: MicroVehicleUpdate): Unit = {
    val state = getLinkStateFn()
    val entryPosition = state.locateVehicle(update.vehicleId).map(_._2.entryPosition).getOrElse(0.0)
    val distanceTraveled = update.distanceTraveled.getOrElse(math.max(0.0, update.position - entryPosition))
    if (!state.downstreamSpillback)
      state.vehiclesByLane.foreach { case (_, q) => q.dequeueAll(_.actorId == update.vehicleId) }

    val accWaiting = getVehicleWaitingSecondsFn(update.vehicleId)
    removeVehicleWaitingSecondsFn(update.vehicleId)

    val entryTick    = getVehicleEntryTickFn(update.vehicleId).getOrElse(currentTickFn())
    val elapsedTicks = math.max(1L, currentTickFn() - entryTick)
    val secondsPerGlobalTick = state.microTicksPerGlobalTick * state.microTimeStep
    val avgSpeed =
      if (elapsedTicks > 0 && secondsPerGlobalTick > 0)
        distanceTraveled / (elapsedTicks * secondsPerGlobalTick)
      else update.velocity

    sendMessageFn(
      update.vehicleId,
      update.shardId,
      MicroLeaveLinkData(
        linkId             = entityIdFn().toLong,
        finalPosition      = update.position,
        finalVelocity      = update.velocity,
        travelTime         = elapsedTicks * secondsPerGlobalTick,
        distanceTraveled   = distanceTraveled,
        averageSpeed       = avgSpeed,
        waitingTimeSeconds = accWaiting,
        junctionPosition = state.junctionGeometry.map(_.positionAt(update.position)),
        remainingStepSeconds = if (state.carryMicroRemainder) update.remainingStepSeconds else 0.0,
        retainedInLane = state.downstreamSpillback,
        finalLane = Some(update.currentLane)
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
        safeVelocity   = update.safeVelocity,
        junctionPosition = getLinkStateFn().junctionGeometry.map(_.positionAt(update.position))
      ),
      "MicroUpdate"
    )
}
