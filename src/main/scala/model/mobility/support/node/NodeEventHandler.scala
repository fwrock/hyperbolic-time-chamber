package org.interscity.htc
package model.mobility.support.node

import org.interscity.htc.model.mobility.entity.state.NodeState
import org.interscity.htc.model.mobility.entity.state.model.{ MicroBoundaryHistory, MicroBoundarySnapshot, PendingLinkAccessRequest, SignalState }
import org.interscity.htc.model.mobility.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.mobility.entity.event.data.link.{ LinkCapacityFreedData, LinkSignalStateData, PermitReturnData, PermitRevokeData, RegisterLinkCapacityData, SubscribeMicroBoundaryData }
import org.interscity.htc.model.mobility.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.mobility.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.{ CancelLinkAccessRequestData, RequestLinkAccessData }
import org.interscity.htc.model.mobility.entity.event.node.LinkAccessData
import org.interscity.htc.model.mobility.entity.state.enumeration.{ EventTypeEnum, LinkCapacityStateEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
import org.interscity.htc.core.entity.actor.ShardActorId
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.util.StringPool
import scala.collection.mutable

class NodeEventHandler(
  getStateFn:          () => NodeState,
  entityIdFn:          () => String,
  currentTickFn:       () => Tick,
  pendingSignals:      mutable.Map[Long, SignalState],
  reportFn:            (Map[String, Any], String) => Unit,
  sendMessageFn: (Long, String, AnyRef, String) => Unit,
  getLinkDependencyFn: Long => Option[ShardActorId],
  logWarnFn:           String => Unit,
  logDebugFn:          String => Unit,
  scheduleFn:          Tick => Unit = _ => (),
  permitsEnabledFn:    () => Boolean = () => model.mobility.util.VehicleSimulationConfig.linkPermits
) {

  private def state: NodeState = getStateFn()

  /** True only while the Node runs its batched decision for the current tick. */
  private var deciding = false

  private def batched(c: Long): Boolean = state.batchAllRequests || state.junction.conflictsOf(c).exists(_.decisionBatching)

  /** Ask for a decision at the next tick; at most one wake-up per tick (checkpointed, so a restored Node does not repeat it). */
  private def requestDecision(): Unit = {
    val t = currentTickFn() + 1
    if (state.decisionScheduledFor < t) {
      state.decisionScheduledFor = t
      scheduleFn(t)
    }
  }

  private def signalFor(approach: Long,target: Long,lane: Option[Int]): Option[SignalState] = {
    val bindings = state.junction.bindingsFor(approach,target).filter(m =>
      (lane.isEmpty || m.laneIds.isEmpty || lane.exists(m.laneIds.contains)))
    if (bindings.nonEmpty) {
      val signals = bindings.map(m => state.signals.getOrElse(m.origin,
        SignalState(Red,1,currentTickFn()+1)))
      signals.find(s => s.state == Red || s.state == TrafficSignalPhaseStateEnum.Yellow).orElse(signals.headOption)
    } else state.connections.get(target).flatMap(i => state.signals.get(i.id))
  }

  def handleRegisterBusStop(event: ActorInteractionEvent, data: RegisterBusStopData): Unit =
    if (state != null) {
      state.busStops.put(StringPool.intern(data.label), event.toIdentity)
    } else {
      logWarnFn(
        s"Node ${entityIdFn()}: RegisterBusStopData arrived before initialization for ${data.label}. " +
          s"This should not happen — PostLoadRegistrationCoordinator guarantees Node is initialized first."
      )
    }

  def handleRegisterSubwayStation(event: ActorInteractionEvent, data: RegisterSubwayStationData): Unit =
    if (state != null) {
      data.lines.foreach { line =>
        state.subwayStations.put(line, event.toIdentity)
      }
    } else {
      logWarnFn(
        s"Node ${entityIdFn()}: RegisterSubwayStationData arrived before initialization for lines: ${data.lines.mkString(", ")}. " +
          s"This should not happen — PostLoadRegistrationCoordinator guarantees Node is initialized first."
      )
    }

  /** One-time registration (Link → Node at Link init) seeding this Node's authoritative
    * available-capacity counter for that link. See docs/CONGESTION_PROPAGATION_DESIGN.md.
    */
  def handleRegisterLinkCapacity(data: RegisterLinkCapacityData): Unit =
    if (state != null) {
      state.availableCapacity(data.linkId) = data.capacity
      state.linkCapacity(data.linkId) = data.capacity
    } else {
      logWarnFn(
        s"Node ${entityIdFn()}: RegisterLinkCapacityData arrived before initialization for link=${data.linkId}. " +
          s"This should not happen — PostLoadRegistrationCoordinator guarantees Node is initialized first."
      )
    }

  /** Sent by a vehicle destroyed while buffered (`WaitingCapacity`, never yet granted) so its
    * stale `PendingLinkAccessRequest` doesn't sit in `capacityWaitQueue` forever — left
    * unhandled, a later freed-slot drain would eventually grant a dead actor (a dead-letter),
    * silently losing that capacity slot for the rest of the simulation. No-op if the entry was
    * already dequeued (e.g. a race with a grant in flight) or never existed. See
    * docs/CONGESTION_PROPAGATION_DESIGN.md.
    */
  def handleCancelLinkAccessRequest(event: ActorInteractionEvent, data: CancelLinkAccessRequestData): Unit =
    if (state != null) {
      state.capacityWaitQueue.keys.toList.foreach { key =>
        state.capacityWaitQueue.get(key).foreach { queue =>
          queue.dequeueAll(p => p.orderId == event.actorRefId &&
            (key == data.targetLinkId || p.targetLinkId == data.targetLinkId))
          if (queue.isEmpty) state.capacityWaitQueue.remove(key)
        }
      }
    }

  def handleRequestLinkAccess(event: ActorInteractionEvent, data: RequestLinkAccessData): Unit =
    if (state != null) {
      if (data.signalAlreadyCleared) {
        // MICRO's Link already authorized the physical crossing within the preceding interval.
        replyGreenOrBufferForCapacity(event, data)
        return
      }
      val nativeSignal = Option.when(state.junction.bindingsFor(data.approachLinkId,data.targetLinkId).nonEmpty)(signalFor(data.approachLinkId,data.targetLinkId,data.approachLane)).flatten
      nativeSignal match {
        case Some(sig) if sig.state == Red || sig.state == TrafficSignalPhaseStateEnum.Yellow =>
          replyToRequester(event, data, LinkAccessData(Red,math.max(currentTickFn()+1,sig.nextTick),requestSeq=event.seq))
        case Some(_) if state.junction.bindingsFor(data.approachLinkId,data.targetLinkId).nonEmpty =>
          replyGreenOrBufferForCapacity(event,data)
        case _ => state.connections.get(data.targetLinkId) match {
        case Some(identify) =>
          state.signals.get(identify.id) match {
            case Some(sig) if sig.state == Red =>
              val queuePos =
                state.signalWaitingCounts.updateWith(data.targetLinkId) {
                  case Some(n) => Some(n + 1)
                  case None    => Some(1)
                }.getOrElse(1) - 1
              reportFn(
                Map(
                  "event_type"     -> "signal_state_requested",
                  "node_id"        -> entityIdFn(),
                  "link_id"        -> data.targetLinkId,
                  "signal_id"      -> identify.id,
                  "phase_state"    -> sig.state.toString,
                  "remaining_time" -> sig.remainingTime,
                  "queue_position" -> queuePos,
                  "vehicle_id"     -> requesterOf(event, data),
                  "tick"           -> currentTickFn()
                ),
                "node_signal_requested"
              )
              replyToRequester(event, data,
                LinkAccessData(phase = sig.state, nextTick = sig.nextTick, queuePosition = queuePos, requestSeq = event.seq))
            case Some(_) =>
              state.signalWaitingCounts.remove(data.targetLinkId)
              replyGreenOrBufferForCapacity(event, data)
            case None =>
              logDebugFn(
                s"Node ${entityIdFn()}: no signal entry for id=${identify.id} (link=${data.targetLinkId}); assuming Green"
              )
              replyGreenOrBufferForCapacity(event, data)
          }
        case None =>
          logDebugFn(
            s"Node ${entityIdFn()}: no connection entry for link=${data.targetLinkId}; assuming Green"
          )
          replyGreenOrBufferForCapacity(event, data)
      }
      }
    } else {
      logWarnFn(
        s"Node ${entityIdFn()} state not initialized, deferring link access request for link: ${data.targetLinkId}"
      )
      replyToRequester(event, data, LinkAccessData(phase = Green, nextTick = currentTickFn(), requestSeq = event.seq))
    }

  private def requesterOf(event: ActorInteractionEvent, data: RequestLinkAccessData): Long =
    if (data.requesterId != 0L) data.requesterId else event.actorRefId

  private def seqOf(event: ActorInteractionEvent, data: RequestLinkAccessData): Long =
    if (data.requesterId != 0L) data.requesterSeq else event.seq

  /** The answer goes to the sender; it names the vehicle when the sender asked on its behalf. */
  private def replyToRequester(event: ActorInteractionEvent, data: RequestLinkAccessData, access: LinkAccessData): Unit =
    sendMessageFn(event.actorRefId, event.shardRefId,
      access.copy(requesterId = data.requesterId, requesterShard = data.requesterShard, requestSeq = seqOf(event, data)),
      EventTypeEnum.ReceiveLinkAccess.toString)

  /** Shared by every "signal says go" outcome (Green, no signal registered, uncontrolled
    * intersection) — capacity backpressure is orthogonal to signal control, so all three funnel
    * through the same check. Grants immediately if capacity is available (or unknown — see
    * `availableCapacity`'s doc on `NodeState`), decrementing Node's own counter; otherwise
    * buffers the requester FIFO and replies with `capacityState = Full`.
    */
  private def replyGreenOrBufferForCapacity(event: ActorInteractionEvent, data: RequestLinkAccessData): Unit = {
    val candidates = state.junction.movementsFor(data.approachLinkId,data.targetLinkId).filter(m =>
        (m.incomingLane.isEmpty || data.approachLane.isEmpty || m.incomingLane == data.approachLane))
    val movement = if (!state.routeAwareJunctionLanes)
      candidates.sortBy(m => if (m.incomingLane == data.approachLane) 0 else 1).headOption
    else candidates.minByOption { m =>
      val goals = m.continuationLanes.filter(_.outgoingLinkId == data.followingLinkId).map(_.fromLane)
      val changes = m.outgoingLane.flatMap(l => goals.map(g => math.abs(l-g)).minOption).getOrElse(0)
      val unavailable = if (state.availableCapacity.get(m.connectorLinkId).exists(_ <= 0)) 1 else 0
      val alignment = (for (a <- m.incomingLane; b <- m.outgoingLane) yield math.abs(a-b)).getOrElse(0)
      (if (m.incomingLane == data.approachLane) 0 else 1, changes, unavailable, alignment,
        m.outgoingLane.getOrElse(-1), m.connectorLinkId)
    }
    val junction = movement
      .map(m => model.mobility.entity.state.model.PrecomputedRouteItem(m.connectorLinkId, entityIdFn().toLong))
    val admissionLinkId = junction.map(_.linkId).getOrElse(data.targetLinkId)
    val capacityKnown = state.availableCapacity.contains(admissionLinkId)
    val hasCapacity = !capacityKnown || state.availableCapacity(admissionLinkId) > 0
    val key = (currentTickFn(), requesterOf(event, data), seqOf(event, data))
    junction.foreach(j => ensureObserved(j.linkId))
    val batchedNow = batched(junction.map(_.linkId).getOrElse(data.targetLinkId)) && (junction.nonEmpty || state.batchAllRequests)
    if (hasCapacity && !batchedNow && !conflictBlocked(junction.map(_.linkId), key)) {
      if (capacityKnown) state.availableCapacity(admissionLinkId) -= 1
      val permit = if (junction.isEmpty && !batchedNow) issuePermit(event, data, admissionLinkId, capacityKnown) else 0
      sendGrant(event.actorRefId, event.shardRefId, seqOf(event, data), data.targetLinkId, junction,movement.flatMap(_.outgoingLane),
        data.requesterId, data.requesterShard, permit)
      if (capacityKnown) revokePermitsIfScarce(admissionLinkId)
    } else {
      state.capacityWaitQueue.getOrElseUpdate(admissionLinkId, mutable.Queue.empty)
        .enqueue(PendingLinkAccessRequest(actorRefId = event.actorRefId, shardRefId = event.shardRefId,
          requestSeq = seqOf(event, data), signalAlreadyCleared = data.signalAlreadyCleared,
          targetLinkId = if (junction.nonEmpty) data.targetLinkId else 0L, junctionLink = junction,
          outgoingLane=movement.flatMap(_.outgoingLane),approachLinkId=data.approachLinkId,approachLane=data.approachLane,
          requestedTick=currentTickFn(), requesterId = data.requesterId, requesterShard = data.requesterShard))
      if (batchedNow) requestDecision()
      revokePermitsIfScarce(admissionLinkId)
      replyToRequester(event, data, LinkAccessData(phase = Green, nextTick = currentTickFn(),
        capacityState = LinkCapacityStateEnum.Full, requestSeq = event.seq))
    }
  }

  private def sendGrant(vehicleId: Long, shardId: String, requestSeq: Long, targetLinkId: Long,
                        junction: Option[model.mobility.entity.state.model.PrecomputedRouteItem], outgoingLane: Option[Int] = None,
                        requesterId: Long = 0L, requesterShard: String = "", permit: Int = 0): Unit = {
    junction.foreach { path =>
      if (state.conflicts.nonEmpty) {
        state.occupiedConnectors(path.linkId) = state.occupiedConnectors.getOrElse(path.linkId, 0) + 1
        refreshPermissions()
      }
      reportFn(Map("node_id" -> entityIdFn(), "vehicle_id" -> (if (requesterId != 0L) requesterId else vehicleId),
        "connector_link_id" -> path.linkId, "target_link_id" -> targetLinkId,
        "tick" -> currentTickFn()), "junction_connector_admitted")
    }
    sendMessageFn(vehicleId, shardId,
      LinkAccessData(phase = Green, nextTick = currentTickFn(), requestSeq = requestSeq, junctionLink = junction,outgoingLane=outgoingLane,
        requesterId = requesterId, requesterShard = requesterShard, permit = permit),
      EventTypeEnum.ReceiveLinkAccess.toString)
  }

  private def permitReserve(destination: Long): Int =
    math.max(2, math.ceil(state.linkCapacity.getOrElse(destination, 0) * model.mobility.util.VehicleSimulationConfig.linkPermitReserveFraction).toInt)

  /** Quota of `destination`'s spare slots for the approach Link that is asking on a vehicle's behalf, taken from the counter
    * now. Spare is what the counter holds beyond the reserve kept for requests the Node serves itself, shared among the
    * approach Links seen so far. Zero while ample room is missing, so contended links keep the exact request/reply path.
    */
  private def issuePermit(event: ActorInteractionEvent, data: RequestLinkAccessData, destination: Long, capacityKnown: Boolean): Int =
    if (!permitsEnabledFn() || data.requesterId == 0L || data.signalAlreadyCleared ||
        !capacityKnown || state.permitRevoking.contains(destination)) 0
    else {
      val approaches = state.permitApproaches.getOrElseUpdate(destination, mutable.Set.empty)
      approaches += event.actorRefId
      val spare = state.availableCapacity(destination) - permitReserve(destination)
      val budget = math.min(model.mobility.util.VehicleSimulationConfig.linkPermitMaxBudget, spare / (approaches.size + 1))
      if (budget < 2) 0
      else {
        state.availableCapacity(destination) -= budget
        val held = state.permitOutstanding.getOrElseUpdate(destination, mutable.Map.empty)
        held(event.actorRefId) = held.getOrElse(event.actorRefId, 0) + budget
        budget
      }
    }

  /** Once the counter nears the reserve, the quotas stop being safe to leave out: ask every holder to give back what is left. */
  private def revokePermitsIfScarce(destination: Long): Unit =
    if (permitsEnabledFn() && !state.permitRevoking.contains(destination) &&
        state.availableCapacity.getOrElse(destination, Int.MaxValue) <= permitReserve(destination)) {
      val holders = state.permitOutstanding.get(destination).map(_.filter(_._2 > 0).keys.toList).getOrElse(Nil)
      if (holders.nonEmpty) {
        state.permitRevoking += destination
        holders.foreach(link => sendMessageFn(link, "mobility.actor.Link", PermitRevokeData(destination), EventTypeEnum.PermitRevoke.toString))
      }
    }

  /** The unspent part of a quota comes back to the counter, which may let buffered requests through. */
  def handlePermitReturn(event: ActorInteractionEvent, data: PermitReturnData): Unit =
    if (state != null) {
      state.availableCapacity(data.targetLinkId) = state.availableCapacity.getOrElse(data.targetLinkId, 0) + data.remaining
      state.permitOutstanding.get(data.targetLinkId).foreach { held =>
        held.remove(event.actorRefId)
        if (held.values.forall(_ == 0)) {
          state.permitOutstanding.remove(data.targetLinkId)
          state.permitRevoking -= data.targetLinkId
        }
      }
      if (state.junction.conflictsOf(data.targetLinkId).nonEmpty) drainConflictGroup(data.targetLinkId)
      else tryDrainCapacityQueue(data.targetLinkId)
    }

  /** A Link reports exactly how many slots freed (never an estimate) every time a vehicle leaves
    * it. Increments this Node's authoritative counter and attempts to drain any vehicles buffered
    * for that link.
    */
  def handleLinkCapacityFreed(data: LinkCapacityFreedData): Unit =
    if (state != null) {
      state.availableCapacity(data.linkId) = state.availableCapacity.getOrElse(data.linkId, 0) + data.freedCount
      if (state.junction.conflictsOf(data.linkId).nonEmpty) {
        state.occupiedConnectors(data.linkId) = math.max(0, state.occupiedConnectors.getOrElse(data.linkId, 0) - data.freedCount)
        drainConflictGroup(data.linkId)
        refreshPermissions()
      } else tryDrainCapacityQueue(data.linkId)
    } else {
      logWarnFn(
        s"Node ${entityIdFn()}: LinkCapacityFreedData arrived before initialization for link=${data.linkId}"
      )
    }

  /** True when the connector may not be granted now: a conflicting connector is occupied, or a
    * conflicting request that beats this one (rule priority, else older) is still waiting.
    * Connectors without a conflict rule are never blocked here.
    */
  private def conflictBlocked(connector: Option[Long], mine: (Long, Long, Long)): Boolean =
    connector.exists { c =>
      state.junction.conflictsOf(c).exists { rule =>
        val other = rule.other(c)
        val otherWins = rule.priorityConnector.contains(other)
        state.occupiedConnectors.getOrElse(other, 0) > 0 ||
          state.capacityWaitQueue.get(other).exists(_.exists { p =>
            (!deciding || p.requestedTick < currentTickFn()) && {
            val theirs = (p.requestedTick, p.orderId, p.requestSeq)
            otherWins || (rule.priorityConnector.isEmpty && Ordering[(Long, Long, Long)].lt(theirs, mine)) }
          }) ||
          (otherWins && rule.gapAcceptance.exists(gap => !gapAccepted(other, gap, state.occupiedConnectors.getOrElse(c, 0))))
      }
    }

  /** The priority approach link of a connector's movement, when known. */
  private def priorityMovement(connector: Long) = state.junction.movementOf(connector)

  /** Request an observation stream from the priority approach link, once; checkpointed so a restored
    * Node neither repeats nor forgets it. The initial publication answers the subscription.
    */
  private def ensureObserved(connector: Long): Unit =
    state.junction.conflictsOf(connector).foreach { rule =>
      val other = rule.other(connector)
      if (rule.priorityConnector.contains(other) && rule.gapAcceptance.nonEmpty) {
        priorityMovement(other).map(_.incomingLinkId).filterNot(state.observationsRequested.contains).foreach { link =>
          state.observationsRequested = (link :: state.observationsRequested).sorted
          sendMessageFn(link, "mobility.actor.Link", SubscribeMicroBoundaryData(),
            EventTypeEnum.SubscribeMicroBoundary.toString)
        }
      }
    }

  /** Gap acceptance: every vehicle observed at the front of a priority-approach lane must need at
    * least the critical gap to reach the conflict point. No observation yet means unknown, which never
    * releases a crossing. The observation is strictly earlier than the current tick, so the elapsed time
    * is credited to the approaching vehicle. A vehicle already waiting at the stop line is not a gap
    * threat: its request is ordered by the queue and excluded by occupancy.
    */
  private def gapAccepted(priority: Long, gap: model.mobility.entity.state.model.GapAcceptance, alreadyAdmitted: Int = 0): Boolean =
    priorityMovement(priority).exists { movement =>
      // A priority movement that currently shows Red cannot send a vehicle into the conflict: its approach is no gap threat.
      // Yellow still counts (a vehicle may cross); an unknown aspect is treated as a threat.
      val redNow = signalFor(movement.incomingLinkId, movement.outgoingLinkId, movement.incomingLane)
        .exists(_.state == Red)
      redNow || state.boundaryObservations.get(movement.incomingLinkId).flatMap(_.before(currentTickFn())).exists { snapshot =>
        val lanes = if (snapshot.laneBoundaries.isEmpty) List((None: Option[Int], snapshot.vehicle))
          else snapshot.laneBoundaries.map(l => (Some(l.laneId), l.frontmost))
        val elapsed = (currentTickFn() - snapshot.tick) * snapshot.stepSeconds
        lanes.filter { case (lane, _) => movement.incomingLane.forall(l => lane.forall(_ == l)) }
          .flatMap(_._2).filterNot(_.pendingExit).forall { v =>
            val distance = math.max(0.0, snapshot.length - v.position) + gap.conflictOffsetM
            val a = gap.assumedAccelerationMps2
            val arrival = (-v.velocity + math.sqrt(v.velocity * v.velocity + 2 * a * distance)) / a
            arrival - elapsed >= gap.criticalGapSeconds + gap.followUpSeconds * alreadyAdmitted
          }
      }
    }

  /** Would a vehicle of the yielding connector be granted right now, ignoring the order of waiting requests? */
  private def permissionNow(c: Long): Boolean =
    state.junction.conflictsOf(c).forall { rule =>
      val other = rule.other(c)
      state.occupiedConnectors.getOrElse(other, 0) == 0 &&
        (!rule.priorityConnector.contains(other) ||
          rule.gapAcceptance.forall(g => gapAccepted(other, g, state.occupiedConnectors.getOrElse(c, 0))))
    }

  private def publishesPermission(c: Long): Boolean =
    state.junction.conflictsOf(c).exists(r => r.priorityConnector.contains(r.other(c)) && r.gapAcceptance.exists(_.publishPermission))

  /** The aspect the approach Link must obey for a movement: its own signal ANDed with the published conflict permission. */
  private def effectiveAspect(incoming: Long, outgoing: Long, raw: TrafficSignalPhaseStateEnum): TrafficSignalPhaseStateEnum = {
    val denied = state.junction.movementsFor(incoming, outgoing).headOption
      .exists(m => state.publishedPermission.get(m.connectorLinkId).contains(false))
    if (denied && (raw == Green || raw == TrafficSignalPhaseStateEnum.Yellow || raw == TrafficSignalPhaseStateEnum.Off)) Red else raw
  }

  /** Tell the approach Link, only when it changes, whether the yielding movement may be entered now (anticipatory braking). */
  private def refreshPermission(c: Long): Unit =
    if (publishesPermission(c)) {
      // Subscribe before judging: a yielding approach held at Red never sends the request that would otherwise subscribe, so an unknown
      // observation would keep it at Red forever.
      ensureObserved(c)
      val allowed = permissionNow(c)
      if (!state.publishedPermission.get(c).contains(allowed)) {
        state.publishedPermission(c) = allowed
        state.junction.movementOf(c).foreach { m =>
          val binding = state.junction.bindingsFor(m.incomingLinkId, m.outgoingLinkId).headOption
          val raw = signalFor(m.incomingLinkId, m.outgoingLinkId, m.incomingLane).map(_.state).getOrElse(Green)
          getLinkDependencyFn(m.incomingLinkId).foreach { dep =>
            sendMessageFn(dep.entityId, dep.classType,
              LinkSignalStateData(effectiveAspect(m.incomingLinkId, m.outgoingLinkId, raw), currentTickFn(), Some(m.outgoingLinkId),
                binding.map(_.laneIds).getOrElse(m.incomingLane.toList)),
              EventTypeEnum.LinkSignalState.toString)
          }
        }
      }
    }

  private def refreshPermissions(): Unit = state.junction.conflictConnectors.foreach(refreshPermission)

  /** Batched decision (opt-in): grants over the requests received before this tick, in deterministic order, then refreshes the published permissions. */
  def onDecisionTick(): Unit =
    if (state != null) {
      deciding = true
      try {
        val ids = (if (state.batchAllRequests) state.capacityWaitQueue.keys.toList
                   else state.conflicts.filter(_.decisionBatching).flatMap(r => List(r.connectorA, r.connectorB))).distinct.sorted
        ids.foreach { c =>
          if (state.capacityWaitQueue.get(c).exists(_.nonEmpty))
            if (state.junction.conflictsOf(c).nonEmpty) drainConflictGroup(c) else tryDrainCapacityQueue(c)
        }
      } finally deciding = false
      refreshPermissions()
    }

  /** A priority movement turning Red (or Green) changes what its yielding movements may do: re-evaluate their waiters. */
  private def drainYieldersOfOrigin(origin: Long): Unit = {
    refreshPermissions()
    state.junction.bindingsOfOrigin(origin).flatMap { binding =>
      state.junction.movementsFor(binding.incomingLinkId, binding.outgoingLinkId)
    }.map(_.connectorLinkId).distinct.foreach { priority =>
      state.junction.conflictsWithPriority(priority).map(_.other(priority)).distinct.sorted.foreach { yielding =>
        if (state.capacityWaitQueue.get(yielding).exists(_.nonEmpty)) drainConflictGroup(yielding)
      }
    }
  }

  /** A published observation can only improve or worsen a gap; re-evaluate every waiter it concerns. */
  def handleBoundaryUpdate(source: Long, snapshot: MicroBoundarySnapshot): Unit =
    if (state != null && state.observationsRequested.contains(source)) {
      val history = state.boundaryObservations.getOrElse(source, MicroBoundaryHistory())
      history.accept(snapshot)
      state.boundaryObservations(source) = history
      val concerned = state.conflicts.filter(_.gapAcceptance.nonEmpty).flatMap { rule =>
        val yielding = rule.other(rule.priorityConnector.get)
        Option.when(priorityMovement(rule.priorityConnector.get).exists(_.incomingLinkId == source))(yielding)
      }.distinct.sorted
      concerned.foreach(c => if (state.capacityWaitQueue.get(c).exists(_.nonEmpty)) drainConflictGroup(c))
      refreshPermissions()
    }

  /** Grants waiting requests across a connector and the connectors it conflicts with, one at a
    * time in deterministic (tick, vehicle, sequence) order; each grant changes occupancy, which is
    * re-evaluated before the next. Signal state and capacity are checked exactly as in the
    * single-queue drain.
    */
  private def drainConflictGroup(connector: Long): Unit = {
    if (!deciding && batched(connector)) { requestDecision(); return }
    val group = (connector :: state.junction.conflictsOf(connector).map(_.other(connector))).distinct
    var progressed = true
    while (progressed) {
      progressed = false
      val candidates = group.flatMap { c =>
        state.capacityWaitQueue.get(c).flatMap(_.headOption).map(p => (c, p))
      }.filter { case (c, p) =>
        val target = if (p.targetLinkId == 0L) c else p.targetLinkId
        (!deciding || p.requestedTick < currentTickFn()) &&
        state.availableCapacity.getOrElse(c, 0) > 0 &&
          (p.signalAlreadyCleared || signalFor(p.approachLinkId, target, p.approachLane)
            .forall(s => s.state == Green || s.state == TrafficSignalPhaseStateEnum.Off)) &&
          !conflictBlocked(Some(c), (p.requestedTick, p.orderId, p.requestSeq))
      }.sortBy { case (_, p) => (p.requestedTick, p.orderId, p.requestSeq) }
      candidates.headOption.foreach { case (c, _) =>
        val queue = state.capacityWaitQueue(c)
        val pending = queue.dequeue()
        state.availableCapacity(c) = state.availableCapacity.getOrElse(c, 0) - 1
        sendGrant(pending.actorRefId, pending.shardRefId, pending.requestSeq,
          if (pending.targetLinkId == 0L) c else pending.targetLinkId, pending.junctionLink, pending.outgoingLane,
          pending.requesterId, pending.requesterShard)
        if (queue.isEmpty) state.capacityWaitQueue.remove(c)
        progressed = true
      }
    }
  }

  /** Capacity waiting cannot retroactively invalidate a MICRO crossing already authorized by
    * its approach Link. Other requests still need the current Green phase. Retaining FIFO and
    * the original reply identity lets a freed-slot or signal transition drain the same wait
    * obligation without another model round-trip. See docs/CONGESTION_PROPAGATION_DESIGN.md.
    */
  private def tryDrainCapacityQueue(linkId: Long): Unit = {
    if (state.junction.conflictsOf(linkId).nonEmpty) { drainConflictGroup(linkId); return }
    def mayProceed(pending: PendingLinkAccessRequest): Boolean = {
      val target = if (pending.targetLinkId == 0L) linkId else pending.targetLinkId
      pending.signalAlreadyCleared || signalFor(pending.approachLinkId,target,pending.approachLane)
        .forall(s => s.state == Green || s.state == TrafficSignalPhaseStateEnum.Off)
    }
    if (!deciding && batched(linkId)) { requestDecision(); return }
    if (deciding && batched(linkId)) {
      // Batched decision: requests received before this tick, in a total order that does not depend on arrival order; head-of-line blocking as in the FIFO drain.
      state.capacityWaitQueue.get(linkId).foreach { queue =>
        var available = state.availableCapacity.getOrElse(linkId, 0)
        var stopped = false
        queue.toList.filter(_.requestedTick < currentTickFn()).sortBy(p => (p.requestedTick, p.orderId, p.requestSeq)).foreach { pending =>
          if (!stopped && available > 0 && mayProceed(pending)) {
            queue.dequeueAll(_ eq pending)
            available -= 1
            sendGrant(pending.actorRefId, pending.shardRefId, pending.requestSeq,
              if (pending.targetLinkId == 0L) linkId else pending.targetLinkId, pending.junctionLink, pending.outgoingLane,
              pending.requesterId, pending.requesterShard)
          } else stopped = true
        }
        state.availableCapacity(linkId) = available
        if (queue.isEmpty) state.capacityWaitQueue.remove(linkId)
      }
      return
    }
    state.capacityWaitQueue.get(linkId).foreach { queue =>
      var available = state.availableCapacity.getOrElse(linkId, 0)
      while (available > 0 && queue.nonEmpty && mayProceed(queue.front)) {
        val pending = queue.dequeue()
        available -= 1
        sendGrant(pending.actorRefId, pending.shardRefId, pending.requestSeq,
          if (pending.targetLinkId == 0L) linkId else pending.targetLinkId, pending.junctionLink,pending.outgoingLane,
          pending.requesterId, pending.requesterShard)
      }
      state.availableCapacity(linkId) = available
      if (queue.isEmpty) state.capacityWaitQueue.remove(linkId)
    }
  }

  def handleReceiveSignalChangeStatus(event: ActorInteractionEvent, data: TrafficSignalChangeStatusData): Unit = {
    if (data.updates.nonEmpty) {
      val changes = model.mobility.entity.event.data.signal.SignalAspectUpdate(data.phaseOrigin,data.signalState,data.nextTick) :: data.updates
      if (state == null) changes.foreach(change => pendingSignals(change.phaseOrigin)=change.signalState)
      else if (changes.forall(change => state.junction.bindingsOfOrigin(change.phaseOrigin).nonEmpty)) {
        changes.foreach(change => state.signals(change.phaseOrigin)=change.signalState)
        val movements = changes.flatMap { change =>
          state.junction.bindingsOfOrigin(change.phaseOrigin).map(movement => (movement,change))
        }
        movements.groupBy(_._1.incomingLinkId).toVector.sortBy(_._1).foreach { case (link,entries) =>
          val updates = entries.map { case (movement,change) =>
            model.mobility.entity.event.data.signal.MovementAspectUpdate(movement.outgoingLinkId,movement.laneIds,
              effectiveAspect(movement.incomingLinkId, movement.outgoingLinkId, change.signalState.state),change.nextTick)
          }
          val head=updates.head
          getLinkDependencyFn(link).foreach { dep =>
            sendMessageFn(dep.entityId,dep.classType,
              LinkSignalStateData(head.phase,head.nextTick,Some(head.targetLinkId),head.laneIds,updates.tail),
              EventTypeEnum.LinkSignalState.toString)
          }
        }
        movements.foreach { case (movement,change) =>
          if (change.signalState.state == Green || change.signalState.state == TrafficSignalPhaseStateEnum.Off) {
            tryDrainCapacityQueue(movement.outgoingLinkId)
            state.junction.movementsFor(movement.incomingLinkId, movement.outgoingLinkId).foreach(m => tryDrainCapacityQueue(m.connectorLinkId))
          }
        }
        changes.map(_.phaseOrigin).distinct.foreach(drainYieldersOfOrigin)
      } else changes.foreach(change => handleReceiveSignalChangeStatus(event,
        TrafficSignalChangeStatusData(change.signalState,change.nextTick,change.phaseOrigin)))
      return
    }
    if (state != null) {
      state.signals.put(data.phaseOrigin, data.signalState)

      state.junction.bindingsOfOrigin(data.phaseOrigin).foreach { movement =>
        getLinkDependencyFn(movement.incomingLinkId).foreach { dep =>
          sendMessageFn(dep.entityId,dep.classType,
            LinkSignalStateData(effectiveAspect(movement.incomingLinkId, movement.outgoingLinkId, data.signalState.state),data.nextTick,Some(movement.outgoingLinkId),movement.laneIds),
            EventTypeEnum.LinkSignalState.toString)
        }
        if (data.signalState.state == Green || data.signalState.state == TrafficSignalPhaseStateEnum.Off) {
          tryDrainCapacityQueue(movement.outgoingLinkId)
          state.junction.movementsFor(movement.incomingLinkId, movement.outgoingLinkId).foreach(m => tryDrainCapacityQueue(m.connectorLinkId))
        }
      }


      drainYieldersOfOrigin(data.phaseOrigin)

      val outgoingLinksForThisPhase = state.connections.collect {
        case (linkId, identify) if identify.id == data.phaseOrigin => linkId
      }


      val approachLinksForThisPhase = state.approachConnections.collect {
        case (linkId, identify) if identify.id == data.phaseOrigin => linkId
      }


      if (data.signalState.state == Green || data.signalState.state == TrafficSignalPhaseStateEnum.Off) {
        outgoingLinksForThisPhase.foreach { linkId =>
          state.signalWaitingCounts.remove(linkId)
          tryDrainCapacityQueue(linkId)
          state.junction.movementsInto(linkId).foreach(m => tryDrainCapacityQueue(m.connectorLinkId))
        }
      }

      approachLinksForThisPhase.foreach { linkId =>
        getLinkDependencyFn(linkId).foreach { dep =>
          sendMessageFn(
            dep.entityId,
            dep.classType,
            LinkSignalStateData(phase = data.signalState.state, nextTick = data.signalState.nextTick),
            EventTypeEnum.LinkSignalState.toString
          )
        }
      }
    } else {
      pendingSignals.put(data.phaseOrigin, data.signalState)
      logWarnFn(
        s"Node ${entityIdFn()} state not initialized, deferring signal change status for phase: ${data.phaseOrigin}"
      )
    }
  }
}
