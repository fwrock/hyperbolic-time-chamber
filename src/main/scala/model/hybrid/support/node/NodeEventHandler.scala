package org.interscity.htc
package model.hybrid.support.node

import org.interscity.htc.model.hybrid.entity.state.NodeState
import org.interscity.htc.model.hybrid.entity.state.model.{ MicroBoundaryHistory, MicroBoundarySnapshot, PendingLinkAccessRequest, SignalState }
import org.interscity.htc.model.hybrid.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.hybrid.entity.event.data.link.{ LinkCapacityFreedData, LinkSignalStateData, RegisterLinkCapacityData, SubscribeMicroBoundaryData }
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.{ CancelLinkAccessRequestData, RequestLinkAccessData }
import org.interscity.htc.model.hybrid.entity.event.node.LinkAccessData
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, LinkCapacityStateEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }
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
  scheduleFn:          Tick => Unit = _ => ()
) {

  private def state: NodeState = getStateFn()

  /** True only while the Node runs its batched decision for the current tick. */
  private var deciding = false

  private def batched(c: Long): Boolean = state.conflicts.exists(r => r.involves(c) && r.decisionBatching)

  /** Ask for a decision at the next tick; at most one wake-up per tick (checkpointed, so a restored Node does not repeat it). */
  private def requestDecision(): Unit = {
    val t = currentTickFn() + 1
    if (state.decisionScheduledFor < t) {
      state.decisionScheduledFor = t
      scheduleFn(t)
    }
  }

  private def signalFor(approach: Long,target: Long,lane: Option[Int]): Option[SignalState] = {
    val bindings = state.movementSignals.filter(m => m.incomingLinkId == approach && m.outgoingLinkId == target &&
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
          queue.dequeueAll(p => p.actorRefId == event.actorRefId &&
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
      val nativeSignal = Option.when(state.movementSignals.exists(m => m.incomingLinkId == data.approachLinkId &&
        m.outgoingLinkId == data.targetLinkId))(signalFor(data.approachLinkId,data.targetLinkId,data.approachLane)).flatten
      nativeSignal match {
        case Some(sig) if sig.state == Red || sig.state == TrafficSignalPhaseStateEnum.Yellow =>
          sendMessageFn(event.actorRefId,event.shardRefId,
            LinkAccessData(Red,math.max(currentTickFn()+1,sig.nextTick),requestSeq=event.seq),
            EventTypeEnum.ReceiveLinkAccess.toString)
        case Some(_) if state.movementSignals.exists(m => m.incomingLinkId == data.approachLinkId && m.outgoingLinkId == data.targetLinkId) =>
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
                  "vehicle_id"     -> event.actorRefId,
                  "tick"           -> currentTickFn()
                ),
                "node_signal_requested"
              )
              sendMessageFn(
                event.actorRefId,
                event.shardRefId,
                LinkAccessData(phase = sig.state, nextTick = sig.nextTick, queuePosition = queuePos, requestSeq = event.seq),
                EventTypeEnum.ReceiveLinkAccess.toString
              )
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
      sendMessageFn(
        event.actorRefId,
        event.shardRefId,
        LinkAccessData(phase = Green, nextTick = currentTickFn(), requestSeq = event.seq),
        EventTypeEnum.ReceiveLinkAccess.toString
      )
    }

  /** Shared by every "signal says go" outcome (Green, no signal registered, uncontrolled
    * intersection) — capacity backpressure is orthogonal to signal control, so all three funnel
    * through the same check. Grants immediately if capacity is available (or unknown — see
    * `availableCapacity`'s doc on `NodeState`), decrementing Node's own counter; otherwise
    * buffers the requester FIFO and replies with `capacityState = Full`.
    */
  private def replyGreenOrBufferForCapacity(event: ActorInteractionEvent, data: RequestLinkAccessData): Unit = {
    val candidates = state.junctionMovements.filter(m =>
      m.incomingLinkId == data.approachLinkId && m.outgoingLinkId == data.targetLinkId &&
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
      .map(m => model.hybrid.entity.state.model.PrecomputedRouteItem(m.connectorLinkId, entityIdFn().toLong))
    val admissionLinkId = junction.map(_.linkId).getOrElse(data.targetLinkId)
    val capacityKnown = state.availableCapacity.contains(admissionLinkId)
    val hasCapacity = !capacityKnown || state.availableCapacity(admissionLinkId) > 0
    val key = (currentTickFn(), event.actorRefId, event.seq)
    junction.foreach(j => ensureObserved(j.linkId))
    val batchedNow = junction.exists(j => batched(j.linkId))
    if (hasCapacity && !batchedNow && !conflictBlocked(junction.map(_.linkId), key)) {
      if (capacityKnown) state.availableCapacity(admissionLinkId) -= 1
      sendGrant(event.actorRefId, event.shardRefId, event.seq, data.targetLinkId, junction,movement.flatMap(_.outgoingLane))
    } else {
      state.capacityWaitQueue.getOrElseUpdate(admissionLinkId, mutable.Queue.empty)
        .enqueue(PendingLinkAccessRequest(actorRefId = event.actorRefId, shardRefId = event.shardRefId,
          requestSeq = event.seq, signalAlreadyCleared = data.signalAlreadyCleared,
          targetLinkId = if (junction.nonEmpty) data.targetLinkId else 0L, junctionLink = junction,
          outgoingLane=movement.flatMap(_.outgoingLane),approachLinkId=data.approachLinkId,approachLane=data.approachLane,
          requestedTick=currentTickFn()))
      if (batchedNow) requestDecision()
      sendMessageFn(event.actorRefId, event.shardRefId,
        LinkAccessData(phase = Green, nextTick = currentTickFn(), capacityState = LinkCapacityStateEnum.Full,
          requestSeq = event.seq), EventTypeEnum.ReceiveLinkAccess.toString)
    }
  }

  private def sendGrant(vehicleId: Long, shardId: String, requestSeq: Long, targetLinkId: Long,
                        junction: Option[model.hybrid.entity.state.model.PrecomputedRouteItem], outgoingLane: Option[Int] = None): Unit = {
    junction.foreach { path =>
      if (state.conflicts.nonEmpty) {
        state.occupiedConnectors(path.linkId) = state.occupiedConnectors.getOrElse(path.linkId, 0) + 1
        refreshPermissions()
      }
      reportFn(Map("node_id" -> entityIdFn(), "vehicle_id" -> vehicleId,
        "connector_link_id" -> path.linkId, "target_link_id" -> targetLinkId,
        "tick" -> currentTickFn()), "junction_connector_admitted")
    }
    sendMessageFn(vehicleId, shardId,
      LinkAccessData(phase = Green, nextTick = currentTickFn(), requestSeq = requestSeq, junctionLink = junction,outgoingLane=outgoingLane),
      EventTypeEnum.ReceiveLinkAccess.toString)
  }

  /** A Link reports exactly how many slots freed (never an estimate) every time a vehicle leaves
    * it. Increments this Node's authoritative counter and attempts to drain any vehicles buffered
    * for that link.
    */
  def handleLinkCapacityFreed(data: LinkCapacityFreedData): Unit =
    if (state != null) {
      state.availableCapacity(data.linkId) = state.availableCapacity.getOrElse(data.linkId, 0) + data.freedCount
      if (state.conflicts.exists(_.involves(data.linkId))) {
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
      state.conflicts.filter(_.involves(c)).exists { rule =>
        val other = rule.other(c)
        val otherWins = rule.priorityConnector.contains(other)
        state.occupiedConnectors.getOrElse(other, 0) > 0 ||
          state.capacityWaitQueue.get(other).exists(_.exists { p =>
            (!deciding || p.requestedTick < currentTickFn()) && {
            val theirs = (p.requestedTick, p.actorRefId, p.requestSeq)
            otherWins || (rule.priorityConnector.isEmpty && Ordering[(Long, Long, Long)].lt(theirs, mine)) }
          }) ||
          (otherWins && rule.gapAcceptance.exists(gap => !gapAccepted(other, gap, state.occupiedConnectors.getOrElse(c, 0))))
      }
    }

  /** The priority approach link of a connector's movement, when known. */
  private def priorityMovement(connector: Long) = state.junctionMovements.find(_.connectorLinkId == connector)

  /** Request an observation stream from the priority approach link, once; checkpointed so a restored
    * Node neither repeats nor forgets it. The initial publication answers the subscription.
    */
  private def ensureObserved(connector: Long): Unit =
    state.conflicts.filter(_.involves(connector)).foreach { rule =>
      val other = rule.other(connector)
      if (rule.priorityConnector.contains(other) && rule.gapAcceptance.nonEmpty) {
        priorityMovement(other).map(_.incomingLinkId).filterNot(state.observationsRequested.contains).foreach { link =>
          state.observationsRequested = (link :: state.observationsRequested).sorted
          sendMessageFn(link, "hybrid.actor.Link", SubscribeMicroBoundaryData(),
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
  private def gapAccepted(priority: Long, gap: model.hybrid.entity.state.model.GapAcceptance, alreadyAdmitted: Int = 0): Boolean =
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
    state.conflicts.filter(_.involves(c)).forall { rule =>
      val other = rule.other(c)
      state.occupiedConnectors.getOrElse(other, 0) == 0 &&
        (!rule.priorityConnector.contains(other) ||
          rule.gapAcceptance.forall(g => gapAccepted(other, g, state.occupiedConnectors.getOrElse(c, 0))))
    }

  private def publishesPermission(c: Long): Boolean =
    state.conflicts.exists(r => r.involves(c) && r.priorityConnector.contains(r.other(c)) && r.gapAcceptance.exists(_.publishPermission))

  /** The aspect the approach Link must obey for a movement: its own signal ANDed with the published conflict permission. */
  private def effectiveAspect(incoming: Long, outgoing: Long, raw: TrafficSignalPhaseStateEnum): TrafficSignalPhaseStateEnum = {
    val denied = state.junctionMovements.find(m => m.incomingLinkId == incoming && m.outgoingLinkId == outgoing)
      .exists(m => state.publishedPermission.get(m.connectorLinkId).contains(false))
    if (denied && (raw == Green || raw == TrafficSignalPhaseStateEnum.Yellow || raw == TrafficSignalPhaseStateEnum.Off)) Red else raw
  }

  /** Tell the approach Link, only when it changes, whether the yielding movement may be entered now (anticipatory braking). */
  private def refreshPermission(c: Long): Unit =
    if (publishesPermission(c)) {
      val allowed = permissionNow(c)
      if (!state.publishedPermission.get(c).contains(allowed)) {
        state.publishedPermission(c) = allowed
        state.junctionMovements.find(_.connectorLinkId == c).foreach { m =>
          val binding = state.movementSignals.find(b => b.incomingLinkId == m.incomingLinkId && b.outgoingLinkId == m.outgoingLinkId)
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

  private def refreshPermissions(): Unit = state.conflicts.flatMap(r => List(r.connectorA, r.connectorB)).distinct.sorted.foreach(refreshPermission)

  /** Batched decision (opt-in): grants over the requests received before this tick, in deterministic order, then refreshes the published permissions. */
  def onDecisionTick(): Unit =
    if (state != null) {
      deciding = true
      try state.conflicts.filter(_.decisionBatching).flatMap(r => List(r.connectorA, r.connectorB)).distinct.sorted.foreach { c =>
        if (state.capacityWaitQueue.get(c).exists(_.nonEmpty)) drainConflictGroup(c)
      } finally deciding = false
      refreshPermissions()
    }

  /** A priority movement turning Red (or Green) changes what its yielding movements may do: re-evaluate their waiters. */
  private def drainYieldersOfOrigin(origin: Long): Unit = {
    refreshPermissions()
    state.movementSignals.filter(_.origin == origin).flatMap { binding =>
      state.junctionMovements.filter(m => m.incomingLinkId == binding.incomingLinkId && m.outgoingLinkId == binding.outgoingLinkId)
    }.map(_.connectorLinkId).distinct.foreach { priority =>
      state.conflicts.filter(_.priorityConnector.contains(priority)).map(_.other(priority)).distinct.sorted.foreach { yielding =>
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
    val group = (connector :: state.conflicts.filter(_.involves(connector)).map(_.other(connector))).distinct
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
          !conflictBlocked(Some(c), (p.requestedTick, p.actorRefId, p.requestSeq))
      }.sortBy { case (_, p) => (p.requestedTick, p.actorRefId, p.requestSeq) }
      candidates.headOption.foreach { case (c, _) =>
        val queue = state.capacityWaitQueue(c)
        val pending = queue.dequeue()
        state.availableCapacity(c) = state.availableCapacity.getOrElse(c, 0) - 1
        sendGrant(pending.actorRefId, pending.shardRefId, pending.requestSeq,
          if (pending.targetLinkId == 0L) c else pending.targetLinkId, pending.junctionLink, pending.outgoingLane)
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
    if (state.conflicts.exists(_.involves(linkId))) { drainConflictGroup(linkId); return }
    def mayProceed(pending: PendingLinkAccessRequest): Boolean = {
      val target = if (pending.targetLinkId == 0L) linkId else pending.targetLinkId
      pending.signalAlreadyCleared || signalFor(pending.approachLinkId,target,pending.approachLane)
        .forall(s => s.state == Green || s.state == TrafficSignalPhaseStateEnum.Off)
    }
    state.capacityWaitQueue.get(linkId).foreach { queue =>
      var available = state.availableCapacity.getOrElse(linkId, 0)
      while (available > 0 && queue.nonEmpty && mayProceed(queue.front)) {
        val pending = queue.dequeue()
        available -= 1
        sendGrant(pending.actorRefId, pending.shardRefId, pending.requestSeq,
          if (pending.targetLinkId == 0L) linkId else pending.targetLinkId, pending.junctionLink,pending.outgoingLane)
      }
      state.availableCapacity(linkId) = available
      if (queue.isEmpty) state.capacityWaitQueue.remove(linkId)
    }
  }

  def handleReceiveSignalChangeStatus(event: ActorInteractionEvent, data: TrafficSignalChangeStatusData): Unit = {
    if (data.updates.nonEmpty) {
      val changes = model.hybrid.entity.event.data.signal.SignalAspectUpdate(data.phaseOrigin,data.signalState,data.nextTick) :: data.updates
      if (state == null) changes.foreach(change => pendingSignals(change.phaseOrigin)=change.signalState)
      else if (changes.forall(change => state.movementSignals.exists(_.origin == change.phaseOrigin))) {
        changes.foreach(change => state.signals(change.phaseOrigin)=change.signalState)
        val movements = changes.flatMap { change =>
          state.movementSignals.filter(_.origin == change.phaseOrigin).map(movement => (movement,change))
        }
        movements.groupBy(_._1.incomingLinkId).toVector.sortBy(_._1).foreach { case (link,entries) =>
          val updates = entries.map { case (movement,change) =>
            model.hybrid.entity.event.data.signal.MovementAspectUpdate(movement.outgoingLinkId,movement.laneIds,
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
            state.junctionMovements.filter(m => m.incomingLinkId == movement.incomingLinkId &&
              m.outgoingLinkId == movement.outgoingLinkId).foreach(m => tryDrainCapacityQueue(m.connectorLinkId))
          }
        }
        changes.map(_.phaseOrigin).distinct.foreach(drainYieldersOfOrigin)
      } else changes.foreach(change => handleReceiveSignalChangeStatus(event,
        TrafficSignalChangeStatusData(change.signalState,change.nextTick,change.phaseOrigin)))
      return
    }
    if (state != null) {
      state.signals.put(data.phaseOrigin, data.signalState)

      state.movementSignals.filter(_.origin == data.phaseOrigin).foreach { movement =>
        getLinkDependencyFn(movement.incomingLinkId).foreach { dep =>
          sendMessageFn(dep.entityId,dep.classType,
            LinkSignalStateData(effectiveAspect(movement.incomingLinkId, movement.outgoingLinkId, data.signalState.state),data.nextTick,Some(movement.outgoingLinkId),movement.laneIds),
            EventTypeEnum.LinkSignalState.toString)
        }
        if (data.signalState.state == Green || data.signalState.state == TrafficSignalPhaseStateEnum.Off) {
          tryDrainCapacityQueue(movement.outgoingLinkId)
          state.junctionMovements.filter(m => m.incomingLinkId == movement.incomingLinkId &&
            m.outgoingLinkId == movement.outgoingLinkId).foreach(m => tryDrainCapacityQueue(m.connectorLinkId))
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
          state.junctionMovements.filter(_.outgoingLinkId == linkId).foreach(m => tryDrainCapacityQueue(m.connectorLinkId))
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
