package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.mobility.entity.state.NodeState
import model.mobility.entity.state.enumeration.EventTypeEnum

import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.mobility.entity.event.data.link.LinkConnectionsData
import org.interscity.htc.model.mobility.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.mobility.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.data.{ ForwardRouteData, ReceiveRouteData, RequestRouteData }
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }

class Node(
  private val properties: Properties
) extends BaseActor[NodeState](
      properties = properties
    ) {

  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {}

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RegisterBusStopData       => handleRegisterBusStop(event, d)
      case d: RegisterSubwayStationData => handleRegisterSubwayStation(event, d)
      case d: RequestSignalStateData    => handleRequestSignalState(event, d)
      case d: TrafficSignalChangeStatusData =>
        handleReceiveSignalChangeStatus(event, d)
      case d: LinkConnectionsData => handleLinkConnections(event, d)
      case _ =>
        logWarn("Event not handled")
    }

  private def handleRegisterBusStop(event: ActorInteractionEvent, data: RegisterBusStopData): Unit =
    state.busStops.put(data.label, event.toIdentity)

  private def handleRegisterSubwayStation(
    event: ActorInteractionEvent,
    data: RegisterSubwayStationData
  ): Unit =
    data.lines.foreach {
      line =>
        state.subwayStations.put(line, event.toIdentity)
    }

  private def handleLinkConnections(event: ActorInteractionEvent, data: LinkConnectionsData): Unit =
    if (data.to.id == getEntityId) {
      state.connections.put(event.actorRefId, data.from)
    } else {
      state.connections.put(event.actorRefId, data.to)
    }

  private def handleRequestSignalState(
    event: ActorInteractionEvent,
    data: RequestSignalStateData
  ): Unit =
    state.connections.get(data.targetLinkId) match
      case Some(identify) =>
        state.signals.get(identify.id) match
          case Some(signalState) =>
            report(
              data = SignalStateData(
                phase = signalState.state,
                nextTick = signalState.nextTick
              ),
              "send signal state"
            )
            sendMessageTo(
              entityId = event.actorRefId,
              shardId = event.shardRefId,
              data = SignalStateData(
                phase = signalState.state,
                nextTick = signalState.nextTick
              ),
              eventType = EventTypeEnum.ReceiveSignalState.toString,
              actorType = LoadBalancedDistributed
            )
          case None =>
            report(
              data = SignalStateData(
                phase = Green,
                nextTick = currentTick
              ),
              "send signal state"
            )
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
      case None =>
        report(
          data = SignalStateData(
            phase = Green,
            nextTick = currentTick
          ),
          "send signal state"
        )
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

  private def handleReceiveSignalChangeStatus(
    event: ActorInteractionEvent,
    data: TrafficSignalChangeStatusData
  ): Unit =
    state.signals.put(data.phaseOrigin, data.signalState)
}
