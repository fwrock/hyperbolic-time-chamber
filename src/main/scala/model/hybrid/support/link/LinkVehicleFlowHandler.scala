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
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, SimulationModeEnum }
import org.interscity.htc.model.hybrid.entity.state.model.{ LinkRegister, VehicleInLane }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.hybrid.util.SpeedUtil

import scala.collection.mutable

/** Handles all vehicle entry and exit interactions for a Link actor (both MESO and MICRO modes). */
class LinkVehicleFlowHandler(
  entityIdFn:                     () => String,
  currentTickFn:                  () => Tick,
  sendMessageFn:                  (String, String, AnyRef, String) => Unit,
  scheduleEventFn:                Tick => Unit,
  getLinkStateFn:                 () => LinkState,
  setLinkStateFn:                 LinkState => Unit,
  putVehicleEntryTickFn:          (String, Tick) => Unit,
  getVehicleEntryTickFn:          String => Option[Tick],
  removeVehicleEntryTickFn:       String => Unit,
  getOrUpdateVehicleWaitingFn:    String => Double,
  removeVehicleWaitingFn:         String => Unit,
  getVehicleWaitingSecondsFn:     String => Double,
  isMicroScheduledFn:             () => Boolean,
  setMicroScheduledFn:            Boolean => Unit,
  metricsReporter:                LinkMetricsReporter,
  findVehicleLaneFn:              String => Option[Int],
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
    if (state.registered.exists(_.actorId == data.actorId)) {
      sendMessageFn(
        event.actorRefId, event.shardRefId,
        LinkInfoData(
          linkLength       = state.length,
          linkCapacity     = state.capacity,
          linkNumberOfCars = state.registered.size,
          linkFreeSpeed    = state.freeSpeed,
          linkLanes        = state.lanes
        ),
        EventTypeEnum.ReceiveEnterLinkInfo.toString
      )
      return
    }

    metricsReporter.onVehicleLoaded(isMicro = false)
    putVehicleEntryTickFn(data.actorId, currentTickFn())
    getOrUpdateVehicleWaitingFn(data.actorId)

    state.registered.add(LinkRegister(
      actorId           = data.actorId,
      shardId           = data.shardId,
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
        linkLanes        = state.lanes
      ),
      EventTypeEnum.ReceiveEnterLinkInfo.toString
    )
  }

  def handleEnterLinkMicro(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    findVehicleLaneFn(data.actorId) match {
      case Some(existingLane) =>
        sendMicroEnterAck(event, existingLane)
        if (!isMicroScheduledFn()) { setMicroScheduledFn(true); scheduleEventFn(currentTickFn() + 1) }
        return
      case None =>
    }

    metricsReporter.onVehicleLoaded(isMicro = true)
    putVehicleEntryTickFn(data.actorId, event.tick)
    getOrUpdateVehicleWaitingFn(data.actorId)

    val state = getLinkStateFn()
    if (!state.registered.exists(_.actorId == data.actorId)) {
      state.registered.add(LinkRegister(
        actorId           = data.actorId,
        shardId           = data.shardId,
        actorType         = data.actorType,
        actorSize         = data.actorSize,
        actorCreationType = data.actorCreationType
      ))
    }

    if (state.vehiclesByLane.isEmpty) setLinkStateFn(state.initializeMicroLanes())

    val assignedLane = findLeastOccupiedLaneFn()
    val vehicle = VehicleInLane(
      actorId         = data.actorId,
      shardId         = data.shardId,
      position        = 0.0,
      velocity        = 0.0,
      acceleration    = 0.0,
      vehicleLength   = data.actorSize,
      entryTick       = event.tick,
      maxAcceleration = data.maxAcceleration,
      maxDeceleration = data.maxDeceleration
    )

    getLinkStateFn().vehiclesByLane.get(assignedLane).foreach { queue =>
      val idx = queue.indexWhere(_.position < vehicle.position)
      if (idx >= 0) queue.insert(idx, vehicle) else queue.enqueue(vehicle)
    }

    sendMicroEnterAck(event, assignedLane)
    if (!isMicroScheduledFn()) { setMicroScheduledFn(true); scheduleEventFn(currentTickFn() + 1) }
  }

  def sendMicroEnterAck(event: ActorInteractionEvent, lane: Int): Unit = {
    val state = getLinkStateFn()
    sendMessageFn(
      event.actorRefId, event.shardRefId,
      MicroEnterLinkData(
        linkId             = entityIdFn(),
        mode               = SimulationModeEnum.MICRO,
        assignedLane       = lane,
        linkLength         = state.length,
        speedLimit         = state.speedLimit,
        numberOfLanes      = state.lanes,
        microTimeStep      = state.microTimeStep,
        ticksPerGlobalTick = state.microTicksPerGlobalTick
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
    state.registered.filterInPlace(_.actorId != data.actorId)
    recomputeAndPublishMesoDynamics()

    val entryTick = getVehicleEntryTickFn(data.actorId).getOrElse(-1L)
    removeVehicleEntryTickFn(data.actorId)
    removeVehicleWaitingFn(data.actorId)

    if (wasRegistered) {
      val travelTicks = if (entryTick >= 0) (currentTickFn() - entryTick).toDouble else 0.0
      metricsReporter.onVehicleArrived(isMicro = state.isMicroMode, travelTime = travelTicks)
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
    val stillInLanes = state.vehiclesByLane.values.exists(_.exists(_.actorId == data.actorId))
    if (!stillInLanes) {
      logDebugFn(s"${data.actorId}: LeaveLinkData received after proactive MicroLeaveLink — cleanup only.")
      if (state.totalVehiclesInMicro == 0 && isMicroScheduledFn()) setMicroScheduledFn(false)
      return
    }

    val vehicleVelocity = state.vehiclesByLane.values
      .flatMap(_.find(_.actorId == data.actorId))
      .headOption.map(_.velocity).getOrElse(0.0)

    state.vehiclesByLane.foreach { case (_, q) => q.dequeueAll(_.actorId == data.actorId) }

    val accWaiting = getVehicleWaitingSecondsFn(data.actorId)
    removeVehicleWaitingFn(data.actorId)

    val elapsedTicks = math.max(1L, currentTickFn() - entryTick + 1)
    val avgSpeed =
      if (state.microTimeStep > 0) state.length / (elapsedTicks * state.microTimeStep)
      else vehicleVelocity

    sendMessageFn(
      event.actorRefId, event.shardRefId,
      MicroLeaveLinkData(
        linkId             = entityIdFn(),
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
        linkLanes        = state.lanes
      ),
      EventTypeEnum.ReceiveLeaveLinkInfo.toString
    )
  }
}
