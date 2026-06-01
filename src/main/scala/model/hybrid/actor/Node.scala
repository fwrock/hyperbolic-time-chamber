package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.util.StringPool
import org.interscity.htc.model.hybrid.entity.state.*

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.hybrid.entity.state.NodeState
import org.interscity.htc.model.hybrid.entity.state.enumeration.EventTypeEnum

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent

import scala.collection.mutable
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.hybrid.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.hybrid.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.hybrid.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.hybrid.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.hybrid.entity.event.node.SignalStateData
import org.interscity.htc.model.hybrid.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }

class Node(
  private val properties: Properties
) extends SimulationBaseActor[NodeState](
      properties = properties
    ) {

  override protected def internStateStrings(s: NodeState): NodeState =
    s.copy(links = s.links.map(StringPool.intern))

  private val pendingSignals
    : mutable.Map[String, _root_.org.interscity.htc.model.hybrid.entity.state.model.SignalState] =
    mutable.Map.empty

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)

    if (state != null) {
      if (pendingSignals.nonEmpty) {
        pendingSignals.foreach {
          case (phaseOrigin, signalState) =>
            state.signals.put(phaseOrigin, signalState)
        }
        pendingSignals.clear()
      }
    }
  }

  override protected def actSpontaneous(event: SpontaneousEvent): Unit =
    onFinishSpontaneous(None)

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterBusStopData       => handleRegisterBusStop(event, d)
      case d: RegisterSubwayStationData => handleRegisterSubwayStation(event, d)
      case d: RequestSignalStateData    => handleRequestSignalState(event, d)
      case d: TrafficSignalChangeStatusData =>
        handleReceiveSignalChangeStatus(event, d)
      case _ =>
        logWarn("Event not handled")
    }

  private def handleRegisterBusStop(event: ActorInteractionEvent, data: RegisterBusStopData): Unit =
    if (state != null) {
      state.busStops.put(data.label, event.toIdentity)
    } else {
      logWarn(
        s"Node ${getEntityId}: RegisterBusStopData arrived before initialization for ${data.label}. " +
          s"This should not happen — PostLoadRegistrationCoordinator guarantees Node is initialized first."
      )
    }

  private def handleRegisterSubwayStation(
    event: ActorInteractionEvent,
    data: RegisterSubwayStationData
  ): Unit =
    if (state != null) {
      data.lines.foreach {
        line =>
          state.subwayStations.put(line, event.toIdentity)
      }
    } else {
      logWarn(
        s"Node ${getEntityId}: RegisterSubwayStationData arrived before initialization for lines: ${data.lines
            .mkString(", ")}. " +
          s"This should not happen — PostLoadRegistrationCoordinator guarantees Node is initialized first."
      )
    }

  private def handleRequestSignalState(
    event: ActorInteractionEvent,
    data: RequestSignalStateData
  ): Unit =
    if (state != null) {
      state.connections.get(data.targetLinkId) match {
        case Some(identify) =>
          state.signals.get(identify.id) match {
            case Some(sig) =>
              // Report signal request
              report(
                data = Map(
                  "event_type" -> "signal_state_requested",
                  "node_id" -> getEntityId,
                  "link_id" -> data.targetLinkId,
                  "signal_id" -> identify.id,
                  "phase_state" -> sig.state.toString,
                  "remaining_time" -> sig.remainingTime,
                  "vehicle_id" -> event.actorRefId,
                  "tick" -> currentTick
                ),
                label = "node_signal_requested"
              )

              sendMessageTo(
                entityId = event.actorRefId,
                shardId = event.shardRefId,
                data = SignalStateData(
                  phase = sig.state,
                  nextTick = sig.nextTick
                ),
                eventType = EventTypeEnum.ReceiveSignalState.toString,
                actorType = LoadBalancedDistributed
              )
            case None =>
//            report(
//              data = SignalStateData(
//                phase = Green,
//                nextTick = currentTick
//              ),
//              "send signal state"
//            )
              sendMessageTo(
                entityId = event.actorRefId,
                shardId = event.shardRefId,
                data = SignalStateData(
                  phase = Green,
                  nextTick = currentTick
                ),
                eventType = EventTypeEnum.ReceiveSignalState.toString,
                actorType = LoadBalancedDistributed
              )
          }
        case None =>
//        report(
//          data = SignalStateData(
//            phase = Green,
//            nextTick = currentTick
//          ),
//          "send signal state"
//        )
          sendMessageTo(
            entityId = event.actorRefId,
            shardId = event.shardRefId,
            data = SignalStateData(
              phase = Green,
              nextTick = currentTick
            ),
            eventType = EventTypeEnum.ReceiveSignalState.toString,
            actorType = LoadBalancedDistributed
          )
      }
    } else {
      logWarn(
        s"Node ${getEntityId} state not initialized, deferring signal state request for link: ${data.targetLinkId}"
      )
      // Send default Green signal as fallback
      sendMessageTo(
        entityId = event.actorRefId,
        shardId = event.shardRefId,
        data = SignalStateData(
          phase = Green,
          nextTick = currentTick
        ),
        eventType = EventTypeEnum.ReceiveSignalState.toString,
        actorType = LoadBalancedDistributed
      )
    }

  private def handleReceiveSignalChangeStatus(
    event: ActorInteractionEvent,
    data: TrafficSignalChangeStatusData
  ): Unit =
    if (state != null) {
      state.signals.put(data.phaseOrigin, data.signalState)
    } else {
      pendingSignals.put(data.phaseOrigin, data.signalState)
      logWarn(
        s"Node ${getEntityId} state not initialized, deferring signal change status for phase: ${data.phaseOrigin}"
      )
    }
}
