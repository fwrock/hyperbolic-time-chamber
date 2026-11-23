package org.interscity.htc
package model.mobility.support.link

import core.types.Tick
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.{
  EnterLinkData,
  LeaveLinkData,
  MicroEnterLinkData,
  MicroLeaveLinkData
}
import org.interscity.htc.model.mobility.entity.state.LinkState
import org.interscity.htc.model.mobility.entity.state.enumeration.{ EventTypeEnum, SimulationModeEnum, microMaxAcceleration, microMaxDeceleration }
import org.interscity.htc.model.mobility.entity.state.model.{ LinkRegister, PendingEntryReply, VehicleInLane }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.node.LinkAccessData
import org.interscity.htc.model.mobility.entity.state.enumeration.LinkCapacityStateEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green
import org.interscity.htc.model.mobility.util.SpeedUtil

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
  logDebugFn:                     String => Unit,
  reportFn: (Map[String,Any],String) => Unit = (_,_) => (),
  scheduleDelegatedFn: (core.runtime.DelegatedWake, Tick) => Unit = (_, _) => (),
  leaveWithoutReplyFn: () => Boolean = () => model.mobility.util.VehicleSimulationConfig.leaveWithoutReply,
  leavesAtGrantFn: () => Boolean = () => model.mobility.util.VehicleSimulationConfig.linkLeavesAtGrant,
  leaveVehicleFn: Long => Unit = _ => ()
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
    if (state.registered.containsActor(event.actorRefId)) {
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

    val entryTick = currentTickFn()
    val delegatedExit = data.delegatedEntry
      .filter(_ => data.actorType == model.mobility.entity.state.enumeration.ActorTypeEnum.Car && !data.mesoOriginAtEnd)
      .map { entry =>
        val seconds = model.mobility.support.car.MesoLinkTiming.carTravelSeconds(
          state.length, state.capacity, state.registered.size.toLong, state.freeSpeed, state.lanes)
        (entry.wake, model.mobility.support.car.MesoLinkTiming.delegatedExitTick(entryTick, seconds, entry, originAtEnd = false))
      }
      .filter { case (_, exit) => exit > entryTick }

    val ownedExit = data.delegatedEntry
      .filter(e => e.deferReply && !data.mesoOriginAtEnd)
      .flatMap { entry =>
        import model.mobility.support.car.MesoLinkTiming
        import model.mobility.entity.state.enumeration.ActorTypeEnum
        def travel(f: (Double, Double, Long, Double, Int) => Double) =
          f(state.length, state.capacity, state.registered.size.toLong, state.freeSpeed, state.lanes)
        data.actorType match {
          case ActorTypeEnum.Car =>
            Some(MesoLinkTiming.delegatedExitTick(entryTick, travel(MesoLinkTiming.carTravelSeconds), entry, originAtEnd = false))
          case ActorTypeEnum.Bus =>
            Some(MesoLinkTiming.busDelegatedExitTick(entryTick, travel(MesoLinkTiming.busTravelSeconds), entry))
          case _ => None
        }
      }
      .filter(_ > entryTick)

    val reply = LinkInfoData(
      linkLength       = state.length,
      linkCapacity     = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed    = state.freeSpeed,
      linkLanes        = state.lanes,
      requestSeq       = event.seq,
      delegatedExitTick = delegatedExit.fold(-1L)(_._2),
      leaveAckRequired = !(leaveWithoutReplyFn() && LinkVehicleFlowHandler.skipsLeaveReply(data.actorType) &&
        !data.mesoOriginAtEnd),
      entryTick        = if (ownedExit.isDefined) entryTick else -1L
    )
    ownedExit match {
      case Some(exit) => holdReplyUntilExit(PendingEntryReply(exit, event.actorRefId, event.shardRefId, reply, data.accessRequest))
      case None =>
        sendMessageFn(event.actorRefId, event.shardRefId, reply, EventTypeEnum.ReceiveEnterLinkInfo.toString)
        delegatedExit.foreach { case (wake, exit) => scheduleDelegatedFn(wake, exit) }
    }
  }

  /** The list stays ordered by exit tick, and by entry order within a tick. The earliest entry is always the one the
    * Link has scheduled itself for.
    */
  private def holdReplyUntilExit(pending: PendingEntryReply): Unit = {
    // Read the state again: recomputing the MESO dynamics replaces it with a copy.
    val state = getLinkStateFn()
    val earliestBefore = state.pendingMesoExits.headOption.map(_.exitTick)
    val (before, after) = state.pendingMesoExits.span(_.exitTick <= pending.exitTick)
    state.pendingMesoExits = before ::: pending :: after
    if (earliestBefore.forall(pending.exitTick < _)) scheduleEventFn(pending.exitTick)
  }

  /** Answers every vehicle whose exit tick has come and returns the tick of the next pending exit. */
  def answerDueExits(): Option[Tick] = {
    val state = getLinkStateFn()
    val tick = currentTickFn()
    val (due, rest) = state.pendingMesoExits.span(_.exitTick <= tick)
    state.pendingMesoExits = rest
    due.foreach { p =>
      p.accessRequest match {
        case Some(access) =>
          state.accessAwaiting = state.accessAwaiting :+ p
          sendMessageFn(access.nodeId, access.nodeClassType,
            access.request.copy(requesterId = p.actorRefId, requesterShard = p.shardRefId, requesterSeq = p.reply.requestSeq),
            EventTypeEnum.RequestLinkAccess.toString)
        case None =>
          sendMessageFn(p.actorRefId, p.shardRefId, p.reply, EventTypeEnum.ReceiveEnterLinkInfo.toString)
      }
    }
    rest.headOption.map(_.exitTick)
  }

  /** The first answer for a vehicle completes the entry reply it was waiting for; a later one (the capacity-freed
    * grant for a vehicle the Node buffered) goes to the vehicle as the Node sent it.
    */
  def forwardNodeAccess(data: LinkAccessData): Unit = {
    val state = getLinkStateFn()
    state.accessAwaiting.find(_.actorRefId == data.requesterId) match {
      case Some(p) =>
        state.accessAwaiting = state.accessAwaiting.filterNot(_ eq p)
        val leaves = leavesAtGrantFn() && data.phase == Green && data.capacityState == LinkCapacityStateEnum.Available
        if (leaves) leaveVehicleFn(p.actorRefId)
        sendMessageFn(p.actorRefId, p.shardRefId, p.reply.copy(access = Some(data), leaveDone = leaves),
          EventTypeEnum.ReceiveEnterLinkInfo.toString)
      case None =>
        sendMessageFn(data.requesterId, data.requesterShard, data.copy(requesterId = 0L, requesterShard = ""),
          EventTypeEnum.ReceiveLinkAccess.toString)
    }
  }

  def handleEnterLinkMicro(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    val requestedState = getLinkStateFn()
    require(data.maxAcceleration.forall(v => v.isFinite && v > 0.0), "invalid MICRO acceleration")
    require(data.maxDeceleration.forall(v => v.isFinite && v > 0.0), "invalid MICRO deceleration")
    require(data.minGap.forall(v => v.isFinite && v >= 0.0), "invalid MICRO minimum gap")
    require(data.reactionTime.forall(v => v.isFinite && v > 0.0), "invalid MICRO reaction time")
    require(data.maxSpeed.forall(v => v.isFinite && v > 0.0), "invalid MICRO maximum speed")
    require(data.speedFactor.isFinite && data.speedFactor > 0.0, "invalid MICRO speed factor")
    require(data.desiredMaxSpeed.forall(v => v.isFinite && v > 0.0), "invalid MICRO desired maximum speed")
    require(data.sigma.isFinite && data.sigma >= 0 && data.sigma <= 1, "invalid MICRO sigma")
    require(data.lc2013 != null, "missing LC2013 parameters")
    require(!requestedState.carryMicroRemainder || requestedState.length >
      model.mobility.entity.state.model.MicroSpeed.limit(requestedState.speedLimit/3.6,
        data.maxSpeed,data.speedFactor,data.desiredMaxSpeed)*requestedState.microTimeStep,
      "MICRO remainder requires a Link longer than the driver-adjusted speed step")
    require(data.initialPosition.isFinite && data.initialPosition >= 0.0 &&
      data.initialPosition <= requestedState.length, "invalid MICRO departure position")
    require(data.arrivalPosition.forall(v => v.isFinite && v >= data.initialPosition &&
      v <= requestedState.length), "invalid MICRO arrival position")
    require(!getLinkStateFn().downstreamSpillback || data.actorType ==
      model.mobility.entity.state.enumeration.ActorTypeEnum.Car,
      "downstream spillback currently supports Car entry only")
    require(data.initialVelocity.isFinite && data.initialVelocity >= 0.0, "invalid MICRO entry velocity")
    require(data.initialRemainderSeconds.isFinite && data.initialRemainderSeconds >= 0.0,
      "invalid MICRO boundary remainder")
    require(data.initialRemainderSeconds == 0.0 || (getLinkStateFn().carryMicroRemainder &&
      data.initialRemainderSeconds <= getLinkStateFn().microTimeStep + 1e-9),
      "MICRO remainder cannot cross an incompatible physics interval")
    require(data.preferredLane.forall(l => l >= 0 && l < requestedState.lanes), "invalid preferred entry lane")
    findVehicleLaneFn(event.actorRefId) match {
      case Some(existingLane) =>
        sendMicroEnterAck(event.actorRefId,event.shardRefId,existingLane,requestSeq=event.seq)
        if (!isMicroScheduledFn()) { setMicroScheduledFn(true); scheduleEventFn(currentTickFn() + 1) }
        return
      case None =>
    }

    if (requestedState.microEntry.enabled) {
      require(data.actorType == model.mobility.entity.state.enumeration.ActorTypeEnum.Car,
        "queued MICRO entry currently supports Car only")
      val pending = model.mobility.entity.state.model.PendingMicroEntry(event.actorRefId,
        core.util.StringPool.intern(event.shardRefId),event.tick,event.seq,data)
      // The list is kept sorted, so insert at the ordered position instead of re-sorting it.
      def key(p: model.mobility.entity.state.model.PendingMicroEntry) = (p.requestedTick,p.actorId,p.requestSeq)
      val (before,after) = requestedState.pendingMicroEntries.filterNot(_.actorId == pending.actorId)
        .span(p => Ordering[(Long,Long,Long)].lteq(key(p),key(pending)))
      requestedState.pendingMicroEntries = before ::: pending :: after
      drainMicroEntries()
      if (!isMicroScheduledFn()) { setMicroScheduledFn(true); scheduleEventFn(currentTickFn() + 1) }
      return
    }

    admitMicroEntry(event.actorRefId,event.shardRefId,event.tick,event.seq,data)
  }

  def drainMicroEntries(): Unit = {
    val state = getLinkStateFn()
    if (!state.microEntry.enabled || state.pendingMicroEntries.isEmpty) return
    if (state.vehiclesByLane.isEmpty) setLinkStateFn(state.initializeMicroLanes())
    // Try independent lanes too: a held entry must not prevent traffic that can release its gap.
    state.pendingMicroEntries.foreach { pending =>
      val fresh = getLinkStateFn()
      val lane = MicroEntryAdmission.lane(fresh,pending.data)
      val snapshot = fresh.microEntry.initialSnapshotTick.contains(pending.requestedTick)
      require(!snapshot || currentTickFn() == pending.requestedTick,
        "initial MICRO snapshot cannot be restored at a later tick")
      val reason = if (snapshot) MicroEntryAdmission.snapshotReason(fresh,pending.data,lane)
        else MicroEntryAdmission.reason(fresh,pending.data,lane)
      require(!snapshot || reason.isEmpty, s"invalid initial MICRO snapshot: ${reason.getOrElse("")}")
      reason match {
        case None =>
          fresh.pendingMicroEntries = fresh.pendingMicroEntries.filterNot(_.actorId == pending.actorId)
          val delayed = currentTickFn() > pending.requestedTick
          admitMicroEntry(pending.actorId,pending.shardId,pending.requestedTick,pending.requestSeq,
            if (delayed) pending.data.copy(initialVelocity=0.0,initialRemainderSeconds=0.0) else pending.data,
            Some(lane), preserveSnapshotPosition=snapshot)
        case Some(reason) =>
          if (pending.requestedTick == currentTickFn())
            reportFn(Map("vehicle_id" -> pending.actorId,"link_id" -> entityIdFn(),
              "requested_position" -> pending.data.initialPosition,"lane" -> lane,
              "reason" -> reason,"tick" -> currentTickFn()),"micro_entry_queued")
      }
    }
  }

  private def admitMicroEntry(actorId: Long, shardId: String, requestedTick: Tick, requestSeq: Long,
                             data: EnterLinkData, selectedLane: Option[Int] = None,
                             preserveSnapshotPosition: Boolean = false): Unit = {
    val admissionTick = if (getLinkStateFn().microEntry.enabled) currentTickFn() else requestedTick

    metricsReporter.onVehicleLoaded(isMicro = true)
    putVehicleEntryTickFn(actorId, admissionTick)
    getOrUpdateVehicleWaitingFn(actorId)

    val state = getLinkStateFn()
    if (!state.registered.containsActor(actorId)) {
      state.registered.add(LinkRegister(
        actorId           = actorId,
        shardId           = shardId,
        actorType         = data.actorType,
        actorSize         = data.actorSize,
        actorCreationType = data.actorCreationType
      ))
    }

    if (state.vehiclesByLane.isEmpty) setLinkStateFn(state.initializeMicroLanes())

    require(data.preferredLane.forall(l => l >= 0 && l < state.lanes), "invalid preferred entry lane")
    val compatible = state.laneConnections.filter(_.outgoingLinkId == data.nextLinkId).map(_.fromLane).distinct
    val assignedLane = selectedLane.getOrElse(data.preferredLane.getOrElse(if (compatible.nonEmpty)
      compatible.minBy(l => (getLinkStateFn().vehiclesByLane.get(l).fold(0)(_.size),l)) else findLeastOccupiedLaneFn()))
    val entryVelocity = math.min(data.initialVelocity, model.mobility.entity.state.model.MicroSpeed.limit(
      state.speedLimit/3.6,data.maxSpeed,data.speedFactor,data.desiredMaxSpeed))
    val requestedPosition = math.min(data.arrivalPosition.getOrElse(state.length),
      data.initialPosition + entryVelocity * data.initialRemainderSeconds)
    val availablePosition = getLinkStateFn().vehiclesByLane.get(assignedLane).toSeq.flatMap(_.iterator)
      .map(v => v.position - v.vehicleLength - data.minGap.getOrElse(2.0)).minOption.getOrElse(state.length)
    // A measured snapshot may be tighter than the desired gap; its physical overlap is checked by MicroEntryAdmission.snapshotReason.
    require(preserveSnapshotPosition || !state.downstreamSpillback || availablePosition >= -1e-9,
      "MICRO spillback entry has no physical room; origin injection or handoff requires admission")
    val entryPosition = if (preserveSnapshotPosition) requestedPosition
      else math.max(0.0, math.min(requestedPosition, availablePosition))
    val vehicle = VehicleInLane(
      actorId         = actorId,
      shardId         = shardId,
      position        = entryPosition,
      velocity        = if (entryPosition + 1e-9 < requestedPosition) 0.0 else entryVelocity,
      acceleration    = 0.0,
      vehicleLength   = data.actorSize,
      entryTick       = admissionTick,
      maxAcceleration = data.maxAcceleration.getOrElse(data.actorType.microMaxAcceleration),
      maxDeceleration = data.maxDeceleration.getOrElse(data.actorType.microMaxDeceleration),
      exitSpeedLimitMps = state.exitSpeedLimitsMps.get(data.nextLinkId),
      nextLinkId = data.nextLinkId,
      entryPosition = math.min(data.initialPosition, entryPosition),
      arrivalPosition = data.arrivalPosition,
      minGap = data.minGap.getOrElse(2.0),
      reactionTime = data.reactionTime.getOrElse(1.0),
      maxSpeed = data.maxSpeed,
      speedFactor = data.speedFactor,
      desiredMaxSpeed = data.desiredMaxSpeed,
      sigma = data.sigma,
      randomSeed = data.randomSeed,
      lc2013 = data.lc2013
    )

    getLinkStateFn().vehiclesByLane.get(assignedLane).foreach { queue =>
      VehicleInLane.insertFrontToBack(queue, vehicle)
    }

    val waitSeconds = if (state.microEntry.enabled)
      math.max(0L,currentTickFn()-requestedTick).toDouble * state.microTimeStep * state.microTicksPerGlobalTick else 0.0
    sendMicroEnterAck(actorId,shardId,assignedLane,waitSeconds,requestSeq)
    if (!isMicroScheduledFn()) { setMicroScheduledFn(true); scheduleEventFn(currentTickFn() + 1) }
  }

  def sendMicroEnterAck(event: ActorInteractionEvent, lane: Int): Unit =
    sendMicroEnterAck(event.actorRefId,event.shardRefId,lane,requestSeq=event.seq)

  private def sendMicroEnterAck(actorId: Long, shardId: String, lane: Int,
                               waitSeconds: Double = 0.0, requestSeq: Long = 0L): Unit = {
    val state = getLinkStateFn()
    val vehicle = state.vehiclesByLane.get(lane).flatMap(_.find(_.actorId == actorId))
    val initialPosition = vehicle.map(_.position).getOrElse(0.0)
    sendMessageFn(
      actorId, shardId,
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
        initialPosition = initialPosition,
        queuedWaitSeconds = waitSeconds,
        requestSeq = requestSeq
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
    val entryTick = removeVehicleFromLink(event.actorRefId, wasRegistered)
    if (state.isMicroMode) sendLeaveLinkMicro(event, data, entryTick)
    else if (data.replyRequired) sendLeaveLinkDataMeso(event)
  }

  /** The Link takes a vehicle off a plain MESO link itself, as `handleLeaveLink` does for a request without a reply. */
  def leaveByLink(actorId: Long): Unit =
    removeVehicleFromLink(actorId, getLinkStateFn().registered.containsActor(actorId))

  /** Returns the tick the vehicle entered the link, or -1. */
  private def removeVehicleFromLink(actorId: Long, wasRegistered: Boolean): Long = {
    val state = getLinkStateFn()
    state.pendingMicroEntries = state.pendingMicroEntries.filterNot(_.actorId == actorId)
    state.registered.removeActor(actorId)
    recomputeAndPublishMesoDynamics()

    val entryTick = getVehicleEntryTickFn(actorId).getOrElse(-1L)
    removeVehicleEntryTickFn(actorId)
    removeVehicleWaitingFn(actorId)

    if (wasRegistered) {
      val travelTicks = if (entryTick >= 0) (currentTickFn() - entryTick).toDouble else 0.0
      val secondsPerTick = if (state.isMicroMode) state.microTimeStep * state.microTicksPerGlobalTick else 1.0
      metricsReporter.onVehicleArrived(isMicro = state.isMicroMode, travelTime = travelTicks * secondsPerTick)
      // Exact count, never an estimate — the Node uses this to drain any vehicles it has
      // buffered waiting for capacity on this link. See docs/CONGESTION_PROPAGATION_DESIGN.md.
      sendMessageFn(
        state.from,
        "mobility.actor.Node",
        model.mobility.entity.event.data.link.LinkCapacityFreedData(linkId = entityIdFn().toLong, freedCount = 1),
        EventTypeEnum.LinkCapacityFreed.toString
      )
    }
    entryTick
  }

  private def sendLeaveLinkMicro(
    event: ActorInteractionEvent,
    data: LeaveLinkData,
    entryTick: Long
  ): Unit = {
    val state = getLinkStateFn()
    val located = state.locateVehicle(event.actorRefId)
    if (located.exists(_._2.pendingExitTick.nonEmpty)) {
      state.vehiclesByLane.valuesIterator.foreach(_.dequeueAll(_.actorId == event.actorRefId))
      if (state.totalVehiclesInMicro == 0 && isMicroScheduledFn()) setMicroScheduledFn(false)
      return
    }
    if (located.isEmpty) {
      logDebugFn(s"${event.actorRefId}: LeaveLinkData received after proactive MicroLeaveLink — cleanup only.")
      if (state.totalVehiclesInMicro == 0 && isMicroScheduledFn()) setMicroScheduledFn(false)
      return
    }

    val vehicleVelocity = located.map(_._2.velocity).getOrElse(0.0)

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

object LinkVehicleFlowHandler {

  /** Vehicle types whose leave handler needs nothing from the Link beyond the length they stored at entry. */
  def skipsLeaveReply(actorType: model.mobility.entity.state.enumeration.ActorTypeEnum): Boolean = {
    import model.mobility.entity.state.enumeration.ActorTypeEnum
    actorType == ActorTypeEnum.Car || actorType == ActorTypeEnum.Bus || actorType == ActorTypeEnum.Bicycle ||
      actorType == ActorTypeEnum.Motorcycle
  }
}
