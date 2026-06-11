package org.interscity.htc
package model.hybrid.support.node

import org.interscity.htc.model.hybrid.entity.state.NodeState
import org.interscity.htc.model.hybrid.entity.state.model.SignalState
import org.interscity.htc.model.hybrid.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.{ EventTypeEnum, TrafficSignalPhaseStateEnum }
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.types.Tick
import scala.collection.mutable

class NodeEventHandler(
  getStateFn:     () => NodeState,
  entityIdFn:     () => String,
  currentTickFn:  () => Tick,
  pendingSignals: mutable.Map[String, SignalState],
  reportFn:       (Map[String, Any], String) => Unit,
  sendMessageFn:  (String, String, AnyRef, String) => Unit,
  logWarnFn:      String => Unit
) {

  private def state: NodeState = getStateFn()

  def handleRegisterBusStop(event: ActorInteractionEvent, data: RegisterBusStopData): Unit =
    if (state != null) {
      state.busStops.put(data.label, event.toIdentity)
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
              reportFn(
                Map(
                  "event_type"     -> "signal_state_requested",
                  "node_id"        -> entityIdFn(),
                  "link_id"        -> data.targetLinkId,
                  "signal_id"      -> identify.id,
                  "phase_state"    -> sig.state.toString,
                  "remaining_time" -> sig.remainingTime,
                  "vehicle_id"     -> event.actorRefId,
                  "tick"           -> currentTickFn()
                ),
                "node_signal_requested"
              )
              sendMessageFn(
                event.actorRefId,
                event.shardRefId,
                SignalStateData(phase = sig.state, nextTick = sig.nextTick),
                EventTypeEnum.ReceiveSignalState.toString
              )
            case None =>
              sendMessageFn(
                event.actorRefId,
                event.shardRefId,
                SignalStateData(phase = Green, nextTick = currentTickFn()),
                EventTypeEnum.ReceiveSignalState.toString
              )
          }
        case None =>
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
      state.signals.put(data.phaseOrigin, data.signalState)
    } else {
      pendingSignals.put(data.phaseOrigin, data.signalState)
      logWarnFn(
        s"Node ${entityIdFn()} state not initialized, deferring signal change status for phase: ${data.phaseOrigin}"
      )
    }
}
