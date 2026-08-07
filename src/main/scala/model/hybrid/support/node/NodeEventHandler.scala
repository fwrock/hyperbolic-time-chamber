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
  pendingSignals:      mutable.Map[String, SignalState],
  reportFn:            (Map[String, Any], String) => Unit,
  sendMessageFn:       (String, String, AnyRef, String) => Unit,
  getLinkDependencyFn: String => Option[ShardActorId],
  logWarnFn:           String => Unit,
  logDebugFn:          String => Unit
) {

  private def state: NodeState = getStateFn()

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
      state.capacityWaitQueue.get(data.targetLinkId).foreach { queue =>
        queue.dequeueAll(_.actorRefId == event.actorRefId)
        if (queue.isEmpty) state.capacityWaitQueue.remove(data.targetLinkId)
      }
    }

  def handleRequestLinkAccess(event: ActorInteractionEvent, data: RequestLinkAccessData): Unit =
    if (state != null) {
      state.connections.get(data.targetLinkId) match {
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
                LinkAccessData(phase = sig.state, nextTick = sig.nextTick, queuePosition = queuePos),
                EventTypeEnum.ReceiveLinkAccess.toString
              )
            case Some(_) =>
              state.signalWaitingCounts.remove(data.targetLinkId)
              replyGreenOrBufferForCapacity(event, data.targetLinkId)
            case None =>
              logDebugFn(
                s"Node ${entityIdFn()}: no signal entry for id=${identify.id} (link=${data.targetLinkId}); assuming Green"
              )
              replyGreenOrBufferForCapacity(event, data.targetLinkId)
          }
        case None =>
          logDebugFn(
            s"Node ${entityIdFn()}: no connection entry for link=${data.targetLinkId}; assuming Green"
          )
          replyGreenOrBufferForCapacity(event, data.targetLinkId)
      }
    } else {
      logWarnFn(
        s"Node ${entityIdFn()} state not initialized, deferring link access request for link: ${data.targetLinkId}"
      )
      sendMessageFn(
        event.actorRefId,
        event.shardRefId,
        LinkAccessData(phase = Green, nextTick = currentTickFn()),
        EventTypeEnum.ReceiveLinkAccess.toString
      )
    }

  /** Shared by every "signal says go" outcome (Green, no signal registered, uncontrolled
    * intersection) — capacity backpressure is orthogonal to signal control, so all three funnel
    * through the same check. Grants immediately if capacity is available (or unknown — see
    * `availableCapacity`'s doc on `NodeState`), decrementing Node's own counter; otherwise
    * buffers the requester FIFO and replies with `capacityState = Full`.
    */
  private def replyGreenOrBufferForCapacity(event: ActorInteractionEvent, targetLinkId: String): Unit = {
    val capacityKnown = state.availableCapacity.contains(targetLinkId)
    val hasCapacity   = !capacityKnown || state.availableCapacity(targetLinkId) > 0
    if (hasCapacity) {
      if (capacityKnown) state.availableCapacity(targetLinkId) -= 1
      sendMessageFn(
        event.actorRefId,
        event.shardRefId,
        LinkAccessData(phase = Green, nextTick = currentTickFn()),
        EventTypeEnum.ReceiveLinkAccess.toString
      )
    } else {
      state.capacityWaitQueue
        .getOrElseUpdate(targetLinkId, mutable.Queue.empty)
        .enqueue(PendingLinkAccessRequest(actorRefId = event.actorRefId, shardRefId = event.shardRefId))
      sendMessageFn(
        event.actorRefId,
        event.shardRefId,
        LinkAccessData(phase = Green, nextTick = currentTickFn(), capacityState = LinkCapacityStateEnum.Full),
        EventTypeEnum.ReceiveLinkAccess.toString
      )
    }
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

  /** Grants access to buffered vehicles up to `availableCapacity`, FIFO, only while the relevant
    * signal (if any) is currently Green — checked against this Node's own already-in-memory
    * `state.signals`, at zero message cost. This closes the gap where a signal could have cycled
    * back to Red while a vehicle sat in the capacity buffer: if Red right now, nothing is
    * dequeued; the buffer is picked up again either on the next freed-slots notification or the
    * next time `handleReceiveSignalChangeStatus` sees this movement turn Green. See
    * docs/CONGESTION_PROPAGATION_DESIGN.md.
    */
  private def tryDrainCapacityQueue(linkId: String): Unit = {
    val signalCurrentlyGreen: Boolean =
      state.connections.get(linkId).flatMap(identify => state.signals.get(identify.id)) match {
        case Some(sig) => sig.state == Green
        case None      => true // no signal registered — uncontrolled intersection, always passable
      }
    if (!signalCurrentlyGreen) return

    state.capacityWaitQueue.get(linkId).foreach { queue =>
      var available = state.availableCapacity.getOrElse(linkId, 0)
      while (available > 0 && queue.nonEmpty) {
        val pending = queue.dequeue()
        available -= 1
        sendMessageFn(
          pending.actorRefId,
          pending.shardRefId,
          LinkAccessData(phase = Green, nextTick = currentTickFn(), capacityState = LinkCapacityStateEnum.Available),
          EventTypeEnum.ReceiveLinkAccess.toString
        )
      }
      state.availableCapacity(linkId) = available
      if (queue.isEmpty) state.capacityWaitQueue.remove(linkId)
    }
  }

  def handleReceiveSignalChangeStatus(event: ActorInteractionEvent, data: TrafficSignalChangeStatusData): Unit =
    if (state != null) {
      state.signals.put(StringPool.intern(data.phaseOrigin), data.signalState)


      val outgoingLinksForThisPhase = state.connections.collect {
        case (linkId, identify) if identify.id == data.phaseOrigin => linkId
      }


      val approachLinksForThisPhase = state.approachConnections.collect {
        case (linkId, identify) if identify.id == data.phaseOrigin => linkId
      }


      if (data.signalState.state == Green) {
        outgoingLinksForThisPhase.foreach { linkId =>
          state.signalWaitingCounts.remove(linkId)
          tryDrainCapacityQueue(linkId)
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
