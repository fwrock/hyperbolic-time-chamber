package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.LinkState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.ForwardRoute
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
import model.mobility.entity.event.data.{EnterLinkData, ForwardRouteData, LeaveLinkData, RequestRouteData}

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.core.util.IdentifyUtil
import org.interscity.htc.model.mobility.entity.event.data.link.{LinkConnectionsData, LinkInfoData}

class Link(
  private var id: String = null,
  private var shard: String = null,
  private val timeManager: ActorRef = null,
  private val creatorManager: ActorRef = null,
  private val data: Any = null,
  private val actorType: CreationTypeEnum = LoadBalancedDistributed
) extends BaseActor[LinkState](
      actorId = id,
      shardId = shard,
      timeManager = timeManager,
      creatorManager = creatorManager,
      data = data,
      actorType = actorType
    ) {

  private def cost: Double = {
    val speedFactor =
      if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }

  override def onInitialize(event: InitializeEvent): Unit = {
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
        logInfo("Event not handled")
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
        shardId = data.shardId,
        actorType = data.actorType,
        actorSize = data.actorSize,
        actorCreationType = data.actorCreationType
      )
    )
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
    val dataLink = LinkInfoData(
      linkLength = state.length,
      linkCapacity = state.capacity,
      linkNumberOfCars = state.registered.size,
      linkFreeSpeed = state.freeSpeed,
      linkLanes = state.lanes
    )
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = dataLink,
      eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
      actorType = LoadBalancedDistributed
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
