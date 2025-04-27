package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.mobility.entity.state.NodeState
import model.mobility.entity.state.enumeration.EventTypeEnum

import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.entity.actor.Properties
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
      case d: RequestRouteData          => handleRequestRoute(event, d)
      case d: ForwardRouteData          => handleForwardRoute(event, d)
      case d: RequestSignalStateData    => handleRequestSignalState(event, d)
      case d: TrafficSignalChangeStatusData =>
        handleReceiveSignalChangeStatus(event, d)
      case d: LinkConnectionsData => handleLinkConnections(event, d)
      case _ =>
        logInfo("Event not handled")
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
    if (data.to.id == getActorId) {
      state.connections.put(event.actorRefId, data.from)
    } else {
      state.connections.put(event.actorRefId, data.to)
    }

  private def handleRequestRoute(event: ActorInteractionEvent, data: RequestRouteData): Unit =
    if (getActorId == data.targetNodeId) {
      handleRequestRouteTarget(event, data)
    } else {
      handleRequestRouteLinks(event, data)
    }

  private def handleRequestRouteLinks(
    event: ActorInteractionEvent,
    data: RequestRouteData
  ): Unit = {
    val path = data.path
    val updatedPath = path :+ (toIdentify, null)
    val dataRequest = RequestRouteData(
      requester = data.requester,
      requesterId = data.requesterId,
      requesterClassType = data.requesterClassType,
      currentCost = data.currentCost,
      targetNodeId = data.targetNodeId,
      originNodeId = data.originNodeId,
      path = updatedPath
    )

    state.links.foreach {

      link =>
        val dependency = dependencies(link)
        sendMessageTo(
          dependency.id,
          dependency.classType,
          dataRequest,
          EventTypeEnum.RequestRoute.toString
        )
    }
  }

  private def handleRequestRouteTarget(
    event: ActorInteractionEvent,
    data: RequestRouteData
  ): Unit = {
    val path = data.path
    val updatedPath = path :+ (null, toIdentify)
    val dataReceive = ReceiveRouteData(
      path = updatedPath,
      label = data.label,
      origin = data.originNodeId,
      destination = data.targetNodeId
    )
    sendMessageTo(
      data.requesterId,
      data.requesterClassType,
      dataReceive,
      EventTypeEnum.ReceiveRoute.toString
    )
  }

  private def handleForwardRoute(event: ActorInteractionEvent, data: ForwardRouteData): Unit =
    val dependency = dependencies(data.requesterId)
    sendMessageTo(
      dependency.id,
      dependency.classType,
      event.data,
      EventTypeEnum.ForwardRoute.toString
    )

  private def handleRequestSignalState(
    event: ActorInteractionEvent,
    data: RequestSignalStateData
  ): Unit =
    state.connections.get(data.targetLinkId) match
      case Some(identify) =>
        state.signals.get(identify.id) match
          case Some(signalState) =>
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
            sendMessageTo(
              entityId = event.actorRefId,
              shardId = event.actorClassType,
              data = SignalStateData(
                phase = Green,
                nextTick = currentTick
              ),
              eventType = EventTypeEnum.ReceiveSignalState.toString,
              actorType = LoadBalancedDistributed
            )
      case None => ???

  private def handleReceiveSignalChangeStatus(
    event: ActorInteractionEvent,
    data: TrafficSignalChangeStatusData
  ): Unit =
    state.signals.put(data.phaseOrigin, data.signalState)
}
