package org.interscity.htc
package model.hybrid.support.link

import core.types.Tick
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.hybrid.entity.event.data.{
  EnterLinkData,
  LeaveLinkData,
  MicroEnterLinkData,
  MicroLeaveLinkData
}
import org.interscity.htc.model.hybrid.entity.state.LinkState
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, SimulationModeEnum, microMaxAcceleration, microMaxDeceleration }
import org.interscity.htc.model.hybrid.entity.state.model.{ LinkRegister, VehicleInLane }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.hybrid.util.SpeedUtil

import scala.collection.mutable

/** Handles all vehicle entry and exit interactions for a Link actor (both MESO and MICRO modes). */
class LinkVehicleFlowHandler(
  entityIdFn:                     () => String,
  currentTickFn:                  () => Tick,
  sendMessageFn: (Long, String, AnyRef, String) => Unit,
  scheduleEventFn:                Tick => Unit,
  getLinkStateFn:                 () => LinkState,
  setLinkStateFn:                 LinkState => Unit,
  putVehicleEntryTickFn:          (Long, Tick) => Unit,
  getVehicleEntryTickFn:          Long => Option[Tick],
  removeVehicleEntryTickFn:       Long => Unit,
  getOrUpdateVehicleWaitingFn:    Long => Double,
  removeVehicleWaitingFn:         Long => Unit,
  getVehicleWaitingSecondsFn:     Long => Double,
  isMicroScheduledFn:             () => Boolean,
  setMicroScheduledFn:            Boolean => Unit,
  metricsReporter:                LinkMetricsReporter,
  findVehicleLaneFn:              Long => Option[Int],
  findLeastOccupiedLaneFn:        () => Int,
  logDebugFn:                     String => Unit
) {

  /** Recomputes `currentSpeed`/`congestionFactor` from the link's current registered-vehicle
    * count and publishes the updated routing cost (rate-limited by
    * `LinkMetricsReporter.maybePublishDynamicCost`). MESO-only: MICRO links get their own
    * per-tick recompute in `LinkMicroSimulationHandler.handleGlobalTick`, driven by real
    * per-vehicle velocities rather than an aggregate density estimate. Event-driven (called on
    * enter/leave) rather than on a self-scheduled tick, since MESO links don't otherwise run a
    * spontaneous tick loop at all — this avoids adding one just to keep two numbers current.
    */
  private def recomputeAndPublishMesoDynamics(): Unit = {
    val state = getLinkStateFn()
    if (state.isMesoMode) {
      val volume = state.registered.size
      val newSpeed = SpeedUtil.linkDensitySpeed(
        length       = state.length,
        capacity     = state.capacity,
        numberOfCars = volume.toLong,
        freeSpeed    = state.freeSpeed,
        lanes        = state.lanes
      )
      val newCongestionFactor = SpeedUtil.bprCongestionFactor(volume = volume.toDouble, capacity = state.capacity)
      setLinkStateFn(state.copy(currentSpeed = newSpeed, congestionFactor = newCongestionFactor))
      metricsReporter.maybePublishDynamicCost(currentTickFn())
    }
  }

  def handleEnterLinkMeso(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    val state = getLinkStateFn()
    if (state.registered.exists(_.actorId == event.actorRefId)) {
      sendMessageFn(
        event.actorRefId, event.shardRefId,
        LinkInfoData(
          linkLength       = state.length,
          linkCapacity     = state.capacity,
          linkNumberOfCars = state.registered.size,
          linkFreeSpeed    = state.freeSpeed,
          linkLanes        = state.lanes,
          requestSeq       = event.seq
        ),
        EventTypeEnum.ReceiveEnterLinkInfo.toString
      )
      return
    }

    metricsReporter.onVehicleLoaded(isMicro = false)
    putVehicleEntryTickFn(event.actorRefId, currentTickFn())
    getOrUpdateVehicleWaitingFn(event.actorRefId)

    state.registered.add(LinkRegister(
      actorId           = event.actorRefId,
      shardId           = event.shardRefId,
      actorType         = data.actorType,
      actorSize         = data.actorSize,
      actorCreationType = data.actorCreationType
    ))
    recomputeAndPublishMesoDynamics()

    sendMessageFn(
      event.actorRefId, event.shardRefId,
      LinkInfoData(
        linkLength       = state.length,
        linkCapacity     = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed    = state.freeSpeed,
        linkLanes        = state.lanes,
        requestSeq       = event.seq
      ),
      EventTypeEnum.ReceiveEnterLinkInfo.toString
    )
  }

  def handleEnterLinkMicro(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    require(!getLinkStateFn().downstreamSpillback || data.actorType ==
      model.hybrid.entity.state.enumeration.ActorTypeEnum.Car,
      "downstream spillback currently supports Car entry only")
    require(data.initialVelocity.isFinite && data.initialVelocity >= 0.0, "invalid MICRO entry velocity")
    require(data.initialRemainderSeconds.isFinite && data.initialRemainderSeconds >= 0.0,
      "invalid MICRO boundary remainder")
    require(data.initialRemainderSeconds == 0.0 || (getLinkStateFn().carryMicroRemainder &&
      data.initialRemainderSeconds <= getLinkStateFn().microTimeStep + 1e-9),
      "MICRO remainder cannot cross an incompatible physics interval")
    findVehicleLaneFn(event.actorRefId) match {
      case Some(existingLane) =>
        sendMicroEnterAck(event, existingLane)
        if (!isMicroScheduledFn()) { setMicroScheduledFn(true); scheduleEventFn(currentTickFn() + 1) }
        return
      case None =>
    }

    metricsReporter.onVehicleLoaded(isMicro = true)
    putVehicleEntryTickFn(event.actorRefId, event.tick)
    getOrUpdateVehicleWaitingFn(event.actorRefId)

    val state = getLinkStateFn()
    if (!state.registered.exists(_.actorId == event.actorRefId)) {
      state.registered.add(LinkRegister(
        actorId           = event.actorRefId,
        shardId           = event.shardRefId,
        actorType         = data.actorType,
        actorSize         = data.actorSize,
        actorCreationType = data.actorCreationType
      ))
    }

    if (state.vehiclesByLane.isEmpty) setLinkStateFn(state.initializeMicroLanes())

    val assignedLane = findLeastOccupiedLaneFn()
    val entryVelocity = math.min(data.initialVelocity, state.speedLimit / 3.6)
    val requestedPosition = entryVelocity * data.initialRemainderSeconds
    val availablePosition = getLinkStateFn().vehiclesByLane.get(assignedLane).toSeq.flatMap(_.iterator)
      .map(v => v.position - v.vehicleLength - 2.0).minOption.getOrElse(state.length)
    require(!state.downstreamSpillback || availablePosition >= -1e-9,
      "MICRO spillback entry has no physical room; origin injection or handoff requires admission")
    val entryPosition = math.max(0.0, math.min(requestedPosition, availablePosition))
    val vehicle = VehicleInLane(
      actorId         = event.actorRefId,
      shardId         = event.shardRefId,
      position        = entryPosition,
      velocity        = if (entryPosition + 1e-9 < requestedPosition) 0.0 else entryVelocity,
      acceleration    = 0.0,
      vehicleLength   = data.actorSize,
      entryTick       = event.tick,
      maxAcceleration = data.actorType.microMaxAcceleration,
      maxDeceleration = data.actorType.microMaxDeceleration,
      exitSpeedLimitMps = state.exitSpeedLimitsMps.get(data.nextLinkId),
      nextLinkId = data.nextLinkId
    )

    getLinkStateFn().vehiclesByLane.get(assignedLane).foreach { queue =>
      VehicleInLane.insertFrontToBack(queue, vehicle)
    }

    sendMicroEnterAck(event, assignedLane)
    if (!isMicroScheduledFn()) { setMicroScheduledFn(true); scheduleEventFn(currentTickFn() + 1) }
  }

  def sendMicroEnterAck(event: ActorInteractionEvent, lane: Int): Unit = {
    val state = getLinkStateFn()
    val vehicle = state.vehiclesByLane.get(lane).flatMap(_.find(_.actorId == event.actorRefId))
    val initialPosition = vehicle.map(_.position).getOrElse(0.0)
    sendMessageFn(
      event.actorRefId, event.shardRefId,
      MicroEnterLinkData(
        linkId             = entityIdFn().toLong,
        mode               = SimulationModeEnum.MICRO,
        assignedLane       = lane,
        linkLength         = state.length,
        speedLimit         = state.speedLimit,
        numberOfLanes      = state.lanes,
        microTimeStep      = state.microTimeStep,
        ticksPerGlobalTick = state.microTicksPerGlobalTick,
        initialVelocity = vehicle.map(_.velocity),
        junctionPosition = state.junctionGeometry.map(_.positionAt(initialPosition)),
        initialPosition = initialPosition
      ),
      "MicroEnterLink"
    )
  }

  def handleLeaveLink(
    event: ActorInteractionEvent,
    data: LeaveLinkData,
    wasRegistered: Boolean
  ): Unit = {
    val state = getLinkStateFn()
    state.registered.filterInPlace(_.actorId != event.actorRefId)
    recomputeAndPublishMesoDynamics()

    val entryTick = getVehicleEntryTickFn(event.actorRefId).getOrElse(-1L)
    removeVehicleEntryTickFn(event.actorRefId)
    removeVehicleWaitingFn(event.actorRefId)

    if (wasRegistered) {
      val travelTicks = if (entryTick >= 0) (currentTickFn() - entryTick).toDouble else 0.0
      val secondsPerTick = if (state.isMicroMode) state.microTimeStep * state.microTicksPerGlobalTick else 1.0
      metricsReporter.onVehicleArrived(isMicro = state.isMicroMode, travelTime = travelTicks * secondsPerTick)
      // Exact count, never an estimate — the Node uses this to drain any vehicles it has
      // buffered waiting for capacity on this link. See docs/CONGESTION_PROPAGATION_DESIGN.md.
      sendMessageFn(
        state.from,
        "hybrid.actor.Node",
        model.hybrid.entity.event.data.link.LinkCapacityFreedData(linkId = entityIdFn().toLong, freedCount = 1),
        EventTypeEnum.LinkCapacityFreed.toString
      )
    }

    if (state.isMicroMode) sendLeaveLinkMicro(event, data, entryTick)
    else sendLeaveLinkDataMeso(event)
  }

  private def sendLeaveLinkMicro(
    event: ActorInteractionEvent,
    data: LeaveLinkData,
    entryTick: Long
  ): Unit = {
    val state = getLinkStateFn()
    val pendingExit = state.vehiclesByLane.valuesIterator.flatMap(_.iterator)
      .exists(v => v.actorId == event.actorRefId && v.pendingExitTick.nonEmpty)
    if (pendingExit) {
      state.vehiclesByLane.valuesIterator.foreach(_.dequeueAll(_.actorId == event.actorRefId))
      if (state.totalVehiclesInMicro == 0 && isMicroScheduledFn()) setMicroScheduledFn(false)
      return
    }
    val stillInLanes = state.vehiclesByLane.values.exists(_.exists(_.actorId == event.actorRefId))
    if (!stillInLanes) {
      logDebugFn(s"${event.actorRefId}: LeaveLinkData received after proactive MicroLeaveLink — cleanup only.")
      if (state.totalVehiclesInMicro == 0 && isMicroScheduledFn()) setMicroScheduledFn(false)
      return
    }

    val vehicleVelocity = state.vehiclesByLane.values
      .flatMap(_.find(_.actorId == event.actorRefId))
      .headOption.map(_.velocity).getOrElse(0.0)

    state.vehiclesByLane.foreach { case (_, q) => q.dequeueAll(_.actorId == event.actorRefId) }

    val accWaiting = getVehicleWaitingSecondsFn(event.actorRefId)
    removeVehicleWaitingFn(event.actorRefId)

    val elapsedTicks = math.max(1L, currentTickFn() - entryTick + 1)
    val avgSpeed =
      if (state.microTimeStep > 0) state.length / (elapsedTicks * state.microTimeStep)
      else vehicleVelocity

    sendMessageFn(
      event.actorRefId, event.shardRefId,
      MicroLeaveLinkData(
        linkId             = entityIdFn().toLong,
        finalPosition      = state.length,
        finalVelocity      = vehicleVelocity,
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

  def sendLeaveLinkDataMeso(event: ActorInteractionEvent): Unit = {
    val state = getLinkStateFn()
    sendMessageFn(
      event.actorRefId, event.shardRefId,
      LinkInfoData(
        linkLength       = state.length,
        linkCapacity     = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed    = state.freeSpeed,
        linkLanes        = state.lanes,
        requestSeq       = event.seq
      ),
      EventTypeEnum.ReceiveLeaveLinkInfo.toString
    )
  }
}
