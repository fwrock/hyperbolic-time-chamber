package org.interscity.htc
package model.hybrid.support.node

import org.interscity.htc.model.hybrid.entity.state.NodeState
import org.interscity.htc.model.hybrid.entity.state.model.{ PendingLinkAccessRequest, SignalState }
import org.interscity.htc.model.hybrid.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.hybrid.entity.event.data.link.{ LinkCapacityFreedData, LinkSignalStateData, RegisterLinkCapacityData }
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
  logDebugFn:          String => Unit
) {

  private def state: NodeState = getStateFn()

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
    if (hasCapacity) {
      if (capacityKnown) state.availableCapacity(admissionLinkId) -= 1
      sendGrant(event.actorRefId, event.shardRefId, event.seq, data.targetLinkId, junction,movement.flatMap(_.outgoingLane))
    } else {
      state.capacityWaitQueue.getOrElseUpdate(admissionLinkId, mutable.Queue.empty)
        .enqueue(PendingLinkAccessRequest(actorRefId = event.actorRefId, shardRefId = event.shardRefId,
          requestSeq = event.seq, signalAlreadyCleared = data.signalAlreadyCleared,
          targetLinkId = if (junction.nonEmpty) data.targetLinkId else 0L, junctionLink = junction,
          outgoingLane=movement.flatMap(_.outgoingLane),approachLinkId=data.approachLinkId,approachLane=data.approachLane))
      sendMessageFn(event.actorRefId, event.shardRefId,
        LinkAccessData(phase = Green, nextTick = currentTickFn(), capacityState = LinkCapacityStateEnum.Full,
          requestSeq = event.seq), EventTypeEnum.ReceiveLinkAccess.toString)
    }
  }

  private def sendGrant(vehicleId: Long, shardId: String, requestSeq: Long, targetLinkId: Long,
                        junction: Option[model.hybrid.entity.state.model.PrecomputedRouteItem], outgoingLane: Option[Int] = None): Unit = {
    junction.foreach { path =>
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
      tryDrainCapacityQueue(data.linkId)
    } else {
      logWarnFn(
        s"Node ${entityIdFn()}: LinkCapacityFreedData arrived before initialization for link=${data.linkId}"
      )
    }

  /** Capacity waiting cannot retroactively invalidate a MICRO crossing already authorized by
    * its approach Link. Other requests still need the current Green phase. Retaining FIFO and
    * the original reply identity lets a freed-slot or signal transition drain the same wait
    * obligation without another model round-trip. See docs/CONGESTION_PROPAGATION_DESIGN.md.
    */
  private def tryDrainCapacityQueue(linkId: Long): Unit = {
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
              change.signalState.state,change.nextTick)
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
      } else changes.foreach(change => handleReceiveSignalChangeStatus(event,
        TrafficSignalChangeStatusData(change.signalState,change.nextTick,change.phaseOrigin)))
      return
    }
    if (state != null) {
      state.signals.put(data.phaseOrigin, data.signalState)

      state.movementSignals.filter(_.origin == data.phaseOrigin).foreach { movement =>
        getLinkDependencyFn(movement.incomingLinkId).foreach { dep =>
          sendMessageFn(dep.entityId,dep.classType,
            LinkSignalStateData(data.signalState.state,data.nextTick,Some(movement.outgoingLinkId),movement.laneIds),
            EventTypeEnum.LinkSignalState.toString)
        }
        if (data.signalState.state == Green || data.signalState.state == TrafficSignalPhaseStateEnum.Off) {
          tryDrainCapacityQueue(movement.outgoingLinkId)
          state.junctionMovements.filter(m => m.incomingLinkId == movement.incomingLinkId &&
            m.outgoingLinkId == movement.outgoingLinkId).foreach(m => tryDrainCapacityQueue(m.connectorLinkId))
        }
      }


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
