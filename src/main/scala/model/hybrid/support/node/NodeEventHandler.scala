package org.interscity.htc
package model.hybrid.support.node

import org.interscity.htc.model.hybrid.entity.state.NodeState
import org.interscity.htc.model.hybrid.entity.state.model.SignalState
import org.interscity.htc.model.hybrid.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.hybrid.entity.event.data.link.LinkSignalStateData
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, TrafficSignalPhaseStateEnum }
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

  def handleRequestSignalState(event: ActorInteractionEvent, data: RequestSignalStateData): Unit =
    if (state != null) {
      state.connections.get(data.targetLinkId) match {
        case Some(identify) =>
          state.signals.get(identify.id) match {
            case Some(sig) =>
              val queuePos =
                if (sig.state == Red)
                  state.signalWaitingCounts.updateWith(data.targetLinkId) {
                    case Some(n) => Some(n + 1)
                    case None    => Some(1)
                  }.getOrElse(1) - 1
                else {
                  state.signalWaitingCounts.remove(data.targetLinkId)
                  0
                }
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
                SignalStateData(phase = sig.state, nextTick = sig.nextTick, queuePosition = queuePos),
                EventTypeEnum.ReceiveSignalState.toString
              )
            case None =>
              // Signal not registered for this connection — treat as uncontrolled intersection.
              // Expected for most intersections in a real map, so this is debug-level, not a warning.
              logDebugFn(
                s"Node ${entityIdFn()}: no signal entry for id=${identify.id} (link=${data.targetLinkId}); assuming Green"
              )
              sendMessageFn(
                event.actorRefId,
                event.shardRefId,
                SignalStateData(phase = Green, nextTick = currentTickFn()),
                EventTypeEnum.ReceiveSignalState.toString
              )
          }
        case None =>
          // No signal for this outgoing link — uncontrolled intersection, pass freely.
          // Expected for most intersections in a real map, so this is debug-level, not a warning.
          logDebugFn(
            s"Node ${entityIdFn()}: no connection entry for link=${data.targetLinkId}; assuming Green"
          )
          sendMessageFn(
            event.actorRefId,
            event.shardRefId,
            SignalStateData(phase = Green, nextTick = currentTickFn()),
            EventTypeEnum.ReceiveSignalState.toString
          )
      }
    } else {
      logWarnFn(
        s"Node ${entityIdFn()} state not initialized, deferring signal state request for link: ${data.targetLinkId}"
      )
      sendMessageFn(
        event.actorRefId,
        event.shardRefId,
        SignalStateData(phase = Green, nextTick = currentTickFn()),
        EventTypeEnum.ReceiveSignalState.toString
      )
    }

  def handleReceiveSignalChangeStatus(event: ActorInteractionEvent, data: TrafficSignalChangeStatusData): Unit =
    if (state != null) {
      state.signals.put(StringPool.intern(data.phaseOrigin), data.signalState)

      // Drain queue counts when phase turns Green so stale counts don't accumulate.
      if (data.signalState.state == Green) {
        state.connections
          .collect { case (linkId, identify) if identify.id == data.phaseOrigin => linkId }
          .foreach(state.signalWaitingCounts.remove)
      }

      // Notify the outgoing Link so MICRO vehicles get the updated signal phase.
      state.connections
        .collect { case (linkId, identify) if identify.id == data.phaseOrigin => linkId }
        .foreach { linkId =>
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
