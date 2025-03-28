package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.interscsimulator.entity.state.NodeState
import model.interscsimulator.entity.state.enumeration.EventTypeEnum

import org.interscity.htc.core.entity.actor.{ Dependency, Identify }
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

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[RegisterBusStopData]       => handleRegisterBusStop(e)
      case e: ActorInteractionEvent[RegisterSubwayStationData] => handleRegisterSubwayStation(e)
      case e: ActorInteractionEvent[RequestRouteData]          => handleRequestRoute(e)
      case e: ActorInteractionEvent[ForwardRouteData]          => handleForwardRoute(e)
      case e: ActorInteractionEvent[RequestSignalStateData]    => handleRequestSignalState(e)
      case e: ActorInteractionEvent[TrafficSignalChangeStatusData] =>
        handleReceiveSignalChangeStatus(e)
      case e: ActorInteractionEvent[LinkConnectionsData] => handleLinkConnections(e)
      case _ =>
        logEvent("Event not handled")
    }

  private def handleRegisterBusStop(event: ActorInteractionEvent[RegisterBusStopData]): Unit =
    state.busStops.put(event.data.label, event.toIdentity)

  private def handleRegisterSubwayStation(
    event: ActorInteractionEvent[RegisterSubwayStationData]
  ): Unit =
    event.data.lines.foreach {
      line =>
        state.subwayStations.put(line, event.toIdentity)
    }

  private def handleLinkConnections(event: ActorInteractionEvent[LinkConnectionsData]): Unit =
    if (event.data.to.id == getActorId) {
      state.connections.put(event.actorRefId, event.data.from)
    } else {
      state.connections.put(event.actorRefId, event.data.to)
    }

  private def handleRequestRoute(event: ActorInteractionEvent[RequestRouteData]): Unit =
    if (getActorId == event.data.targetNodeId) {
      handleRequestRouteTarget(event)
    } else {
      handleRequestRouteLinks(event)
    }

  private def handleRequestRouteLinks(event: ActorInteractionEvent[RequestRouteData]): Unit = {
    val path = event.data.path
    val updatedPath = path :+ (toIdentify, null)
    val data = RequestRouteData(
      requester = event.data.requester,
      requesterId = event.data.requesterId,
      requesterClassType = event.data.requesterClassType,
      currentCost = event.data.currentCost,
      targetNodeId = event.data.targetNodeId,
      originNodeId = event.data.originNodeId,
      path = updatedPath
    )

    state.links.foreach {

      link =>
        val dependency = dependencies(link)
        sendMessageTo(
          dependency.id,
          dependency.classType,
          data,
          EventTypeEnum.RequestRoute.toString
        )
    }
  }

  private def handleRequestRouteTarget(event: ActorInteractionEvent[RequestRouteData]): Unit = {
    val path = event.data.path
    val updatedPath = path :+ (null, toIdentify)
    val data = ReceiveRouteData(
      path = updatedPath,
      label = event.data.label,
      origin = event.data.originNodeId,
      destination = event.data.targetNodeId
    )
    sendMessageTo(
      event.data.requesterId,
      event.data.requesterClassType,
      data,
      EventTypeEnum.ReceiveRoute.toString
    )
  }

  private def handleForwardRoute(event: ActorInteractionEvent[ForwardRouteData]): Unit =
    val dependency = dependencies(event.data.requesterId)
    sendMessageTo(
      dependency.id,
      dependency.classType,
      event.data,
      EventTypeEnum.ForwardRoute.toString
    )

  private def handleRequestSignalState(
    event: ActorInteractionEvent[RequestSignalStateData]
  ): Unit =
    state.connections.get(event.data.targetLinkId) match
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
    event: ActorInteractionEvent[TrafficSignalChangeStatusData]
  ): Unit =
    state.signals.put(event.data.phaseOrigin, event.data.signalState)
}
