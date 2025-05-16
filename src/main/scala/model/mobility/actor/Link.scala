package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.LinkState

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.ForwardRoute
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
import model.mobility.entity.event.data.{ EnterLinkData, ForwardRouteData, LeaveLinkData, RequestRouteData }

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.util.IdentifyUtil
import org.interscity.htc.model.mobility.entity.event.data.link.{ LinkConnectionsData, LinkInfoData }

class Link(
  private val properties: Properties
) extends BaseActor[LinkState](
      properties = properties
    ) {

  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }

  override def onInitialize(event: InitializeEvent): Unit =
    super.onStart()
//    sendConnections(state.to, IdentifyUtil.fromDependency(getDependency(state.to)))
//    sendConnections(state.from, IdentifyUtil.fromDependency(getDependency(state.from)))

  private def sendConnections(actorId: String, identify: Identify): Unit =
    sendMessageTo(
      identify.id,
      identify.classType,
      LinkConnectionsData(
        to = IdentifyUtil.fromDependency(getDependency(state.to)),
        from = IdentifyUtil.fromDependency(getDependency(state.from))
      ),
      EventTypeEnum.RequestRoute.toString
    )

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: EnterLinkData => handleEnterLink(event, d)
      case d: LeaveLinkData => handleLeaveLink(event, d)
      case _ =>
        logWarn("Event not handled")
    }

  private def handleEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    val dataLink = if (state == null) {
      LinkInfoData(
        linkCapacity = Int.MaxValue,
        linkFreeSpeed = 50,
        linkLanes = 1,
      )
    } else {
      LinkInfoData(
        linkLength = state.length,
        linkCapacity = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed = state.freeSpeed,
        linkLanes = state.lanes
      )
    }
    if (state != null) {
      state.registered.add(
        LinkRegister(
          actorId = data.actorId,
          shardId = data.shardId,
          actorType = data.actorType,
          actorSize = data.actorSize,
          actorCreationType = data.actorCreationType
        )
      )
      report(data = (data.actorId, state.registered.size), "registered cars")
    }
    report(data = event.actorRefId, "send enter link info")
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = dataLink,
      eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }

  private def handleLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    state.registered.filterInPlace(_.actorId != data.actorId)
    report(data = (data.actorId, state.registered.size), "remove registered cars")
    val dataLink = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )
    report(data = event.actorRefId, "send leaving link info")
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = dataLink,
      eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
      actorType = LoadBalancedDistributed
    )
  }
}
