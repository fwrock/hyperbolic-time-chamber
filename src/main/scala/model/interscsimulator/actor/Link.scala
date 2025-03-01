package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.LinkState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.{ ActorTypeEnum, EventTypeEnum }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum.{ EnterLink, ForwardRoute, RequestRoute }
import org.interscity.htc.model.interscsimulator.entity.state.model.{ LinkRegister, RoutePathItem }

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import model.interscsimulator.entity.event.data.{ EnterLinkData, ForwardRouteData, RequestRouteData }

import org.interscity.htc.core.entity.actor.{ Dependency, Identify }
import org.interscity.htc.model.interscsimulator.entity.event.data.link.{ LinkConnectionsData, LinkInfoData }

class Link(
  private var id: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, Dependency] =
    mutable.Map[String, Dependency]()
) extends BaseActor[LinkState](
      actorId = id,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }

  override def onStart(): Unit = {
    super.onStart()
    sendConnections(state.to, dependencies(state.to).toIdentify())
    sendConnections(state.from, dependencies(state.from).toIdentify())
  }

  private def sendConnections(actorId: String, identify: Identify): Unit =
    sendMessageTo(
      identify.id,
      identify.classType,
      LinkConnectionsData(
        to = dependencies(state.to).toIdentify(),
        from = dependencies(state.from).toIdentify()
      ),
      EventTypeEnum.RequestRoute.toString
    )

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[RequestRouteData] => handleRequestRoute(e)
      case e: ActorInteractionEvent[EnterLinkData]    => handleEnterLink(e)
      case _ =>
        logEvent("Event not handled")
    }

  private def handleEnterLink(event: ActorInteractionEvent[EnterLinkData]): Unit = {
    val data = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )
    state.registered.add(
      LinkRegister(
        actorId = event.data.actorId,
        actorRef = event.data.actorRef,
        actorType = event.data.actorType,
        actorSize = event.data.actorSize
      )
    )
    sendMessageTo(
      event.actorRefId,
      event.actorClassType,
      data,
      EventTypeEnum.ReceiveEnterLinkInfo.toString
    )
  }

  private def handleRequestRoute(event: ActorInteractionEvent[RequestRouteData]): Unit = {
    val path = event.data.path
    val updatedPath = path :+ (
      dependencies(state.to).toIdentify(),
      toIdentify
    )
    val data = ForwardRouteData(
      requester = event.data.requester,
      requesterId = event.data.requesterId,
      updatedCost = cost + event.data.currentCost,
      targetNodeId = event.data.targetNodeId,
      path = updatedPath
    )
    val to = dependencies(state.to)
    sendMessageTo(to.id, to.classType, data, ForwardRoute.toString)
  }
}
