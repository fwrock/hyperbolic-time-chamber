package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.interscsimulator.entity.state.NodeState
import model.interscsimulator.entity.state.enumeration.EventTypeEnum
import model.interscsimulator.entity.state.enumeration.EventTypeEnum.{ ForwardRoute, RequestRoute }
import model.interscsimulator.util.DataKeyConstants

import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem
import org.interscity.htc.model.interscsimulator.util.DataKeyConstants.{ CURRENT_COST, PATH, REQUESTER, REQUESTER_ID, TARGET_NODE_ID }

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.interscsimulator.entity.event.data.{ ForwardRouteData, ReceiveRouteData, RequestRouteData }
import org.interscity.htc.model.interscsimulator.entity.event.node.SignalStateData
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red

class Node(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends BaseActor[NodeState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {}

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[RequestRouteData] => handleRequestRoute(e)
      case e: ActorInteractionEvent[ForwardRouteData] => handleForwardRoute(e)
      case _ =>
        logEvent("Event not handled")
    }

  private def handleRequestRoute(event: ActorInteractionEvent[RequestRouteData]): Unit =
    if (getActorId == event.data.targetNodeId) {
      handleRequestRouteTarget(event)
    } else {
      handleRequestRouteLinks(event)
    }

  private def handleRequestRouteLinks(event: ActorInteractionEvent[RequestRouteData]): Unit = {
    val path = event.data.path
    // val updatedPath = path :+ (null, RoutePathItem(actorRef = self, actorId = getActorId))
    val updatedPath = path :+ (RoutePathItem(actorRef = self, actorId = getActorId), null)
    val data = RequestRouteData(
      requester = event.data.requester,
      requesterId = event.data.requesterId,
      currentCost = event.data.currentCost,
      targetNodeId = event.data.targetNodeId,
      path = updatedPath
    )

    state.links.foreach {
      link => sendMessageTo(link, dependencies(link), data, EventTypeEnum.RequestRoute.toString)
    }
  }

  private def handleRequestRouteTarget(event: ActorInteractionEvent[RequestRouteData]): Unit = {
    val path = event.data.path
    val updatedPath = path :+ (null, RoutePathItem(actorRef = self, actorId = getActorId))
    val data = ReceiveRouteData(
      path = updatedPath
    )
    sendMessageTo(
      event.data.requesterId,
      event.data.requester,
      data,
      EventTypeEnum.ReceiveRoute.toString
    )
  }

  private def handleForwardRoute(event: ActorInteractionEvent[ForwardRouteData]): Unit =
    sendMessageTo(
      event.data.requesterId,
      dependencies(event.data.requesterId),
      event.data,
      EventTypeEnum.ForwardRoute.toString
    )

  private def handleRequestSignalState(
    event: ActorInteractionEvent[RequestSignalStateData]
  ): Unit = {
    val data = SignalStateData(
      phase = Red,
      nextTick = 0
    )
    sendMessageTo(
      event.actorRefId,
      event.actorRef,
      data,
      EventTypeEnum.ReceiveSignalState.toString
    )
  }
}
