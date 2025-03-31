package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.interscsimulator.entity.state.NodeState
import model.interscsimulator.entity.state.enumeration.EventTypeEnum

import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.event.data.bus.RegisterBusStopData
import org.interscity.htc.model.interscsimulator.entity.event.data.link.LinkConnectionsData
import org.interscity.htc.model.interscsimulator.entity.event.data.signal.TrafficSignalChangeStatusData
import org.interscity.htc.model.interscsimulator.entity.event.data.subway.RegisterSubwayStationData
import org.interscity.htc.model.interscsimulator.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.interscsimulator.entity.event.data.{ ForwardRouteData, ReceiveRouteData, RequestRouteData }
import org.interscity.htc.model.interscsimulator.entity.event.node.SignalStateData
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum.{ Green, Red }

class Node(
  private var id: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, Dependency] =
    mutable.Map[String, Dependency]()
) extends BaseActor[NodeState](
      actorId = id,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
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
        logEvent("Event not handled")
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
              event.actorRefId,
              event.actorClassType,
              SignalStateData(
                phase = signalState.state,
                nextTick = signalState.nextTick
              ),
              EventTypeEnum.ReceiveSignalState.toString
            )
          case None =>
            sendMessageTo(
              event.actorRefId,
              event.actorClassType,
              SignalStateData(
                phase = Green,
                nextTick = currentTick
              ),
              EventTypeEnum.ReceiveSignalState.toString
            )
      case None => ???

  private def handleReceiveSignalChangeStatus(
    event: ActorInteractionEvent,
    data: TrafficSignalChangeStatusData
  ): Unit =
    state.signals.put(data.phaseOrigin, data.signalState)
}
