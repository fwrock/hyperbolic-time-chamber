package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.LinkState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.{ActorTypeEnum, EventTypeEnum}
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum.{EnterLink, ForwardRoute, RequestRoute}
import org.interscity.htc.model.interscsimulator.entity.state.model.{LinkRegister, RoutePathItem}

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import model.interscsimulator.entity.event.data.{EnterLinkData, ForwardRouteData, RequestRouteData}

import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.interscity.htc.core.util.IdentifyUtil
import org.interscity.htc.model.interscsimulator.entity.event.data.link.{LinkConnectionsData, LinkInfoData}

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
    sendConnections(state.to, IdentifyUtil.fromDependency(dependencies(state.to)))
    sendConnections(state.from, IdentifyUtil.fromDependency(dependencies(state.from)))
  }

  private def sendConnections(actorId: String, identify: Identify): Unit =
    sendMessageTo(
      identify.id,
      identify.classType,
      LinkConnectionsData(
        to = IdentifyUtil.fromDependency(dependencies(state.to)),
        from = IdentifyUtil.fromDependency(dependencies(state.from))
      ),
      EventTypeEnum.RequestRoute.toString
    )

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RequestRouteData => handleRequestRoute(event, d)
      case d: EnterLinkData    => handleEnterLink(event, d)
      case _ =>
        logEvent("Event not handled")
    }

  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    val dataLink = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )
    state.registered.add(
      LinkRegister(
        actorId = data.actorId,
        actorRef = data.actorRef,
        actorType = data.actorType,
        actorSize = data.actorSize
      )
    )
    sendMessageTo(
      event.actorRefId,
      event.actorClassType,
      dataLink,
      EventTypeEnum.ReceiveEnterLinkInfo.toString
    )
  }

  private def handleRequestRoute(event: ActorInteractionEvent, data: RequestRouteData): Unit = {
    val path = data.path
    val updatedPath = path :+ (
      IdentifyUtil.fromDependency(dependencies(state.to)),
      toIdentify
    )
    val dataForward = ForwardRouteData(
      requester = data.requester,
      requesterId = data.requesterId,
      updatedCost = cost + data.currentCost,
      targetNodeId = data.targetNodeId,
      path = updatedPath
    )
    val to = dependencies(state.to)
    sendMessageTo(to.id, to.classType, dataForward, ForwardRoute.toString)
  }
}
