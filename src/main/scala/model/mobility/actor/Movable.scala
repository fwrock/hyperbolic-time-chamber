package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.MovableState

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{ ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo }
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Finished, Ready, Start }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.{ EnterLinkData, LeaveLinkData, ReceiveRoute }
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum

abstract class Movable[T <: MovableState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends BaseActor[T](
      properties = properties
    ) {

  protected def requestRoute(): Unit = {}

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        requestRoute()
      case Ready =>
        linkEnter()
      case _ =>
        logInfo(s"Event current status not handled ${state.movableStatus}")
        onFinishSpontaneous(Some(currentTick + 1))

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: ReceiveRoute => handleReceiveRoute(d)
      case d: LinkInfoData => handleLinkInfo(event, d)
      case _ =>
        logInfo(s"Movable Event not handled: $event")
    }

  private def handleReceiveRoute(data: ReceiveRoute): Unit = {
    val updatedCost = data.cost
    state.movableBestRoute = data.path
    state.movableStatus = Ready
    linkEnter()
  }

  private def handleLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit =
    EventTypeEnum.valueOf(event.eventType) match {
      case ReceiveEnterLinkInfo => actHandleReceiveEnterLinkInfo(event, data)
      case ReceiveLeaveLinkInfo => actHandleReceiveLeaveLinkInfo(event, data)
      case _ =>
        logInfo(s"Event not handled: $event with data: $data")
    }

  protected def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {}

  protected def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {}

  protected def onFinish(nodeId: String): Unit = {
    if (state.destination == nodeId) {
      state.movableReachedDestination = true
      state.movableStatus = Finished
    } else {
      state.movableStatus = Finished
    }
    report(data = state.movableStatus, "changed status")
    onFinishSpontaneous()
  }

  protected def linkEnter(): Unit =
    state.movableCurrentPath match
      case Some(item) =>
        (item._1, item._2) match
          case (link, node) =>
            report(data = EnterLinkData(
              actorId = getActorId,
              shardId = getShardId,
              actorType = state.actorType,
              actorSize = state.size,
              actorCreationType = LoadBalancedDistributed
            ), "enter link")
            sendMessageTo(
              entityId = link.id,
              shardId = link.shardId,
              data = EnterLinkData(
                actorId = getActorId,
                shardId = getShardId,
                actorType = state.actorType,
                actorSize = state.size,
                actorCreationType = LoadBalancedDistributed
              ),
              EventTypeEnum.EnterLink.toString,
              actorType = LoadBalancedDistributed
            )
          case null =>
            logInfo("Path item not handled")
      case None if state.movableBestRoute.isEmpty =>
        state.movableStatus = Finished
        onFinishSpontaneous()
      case None =>
        state.movableCurrentPath = getNextPath
        linkEnter()

  protected def leivingLink(): Unit =
    state.movableCurrentPath match
      case Some(item) =>
        (item._1, item._2) match
          case (link, node) =>
            report(data = LeaveLinkData(
              actorId = getActorId,
              shardId = getShardId,
              actorType = state.actorType,
              actorSize = state.size,
              actorCreationType = LoadBalancedDistributed
            ), "leaving link")
            sendMessageTo(
              entityId = link.id,
              shardId = link.shardId,
              data = LeaveLinkData(
                actorId = getActorId,
                shardId = getShardId,
                actorType = state.actorType,
                actorSize = state.size,
                actorCreationType = LoadBalancedDistributed
              ),
              EventTypeEnum.LeaveLink.toString,
              actorType = LoadBalancedDistributed
            )
            if (state.movableBestRoute.isEmpty) {
              onFinish(node.id)
            }
            state.movableCurrentPath = None
          case _ =>
            logInfo("Path item not handled")
      case None =>
        logInfo("No link to leave")

  protected def getNextPath: Option[(Identify, Identify)] =
    state.movableBestRoute match
      case Some(path) =>
        Some(path.dequeue)
      case None =>
        logInfo("No path to follow")
        None

  protected def viewNextPath: Option[(Identify, Identify)] =
    state.movableBestRoute match
      case Some(path) =>
        Some(path.head)
      case None =>
        logInfo("No path to follow")
        None

  protected def getCurrentNode: Identify =
    state.movableCurrentPath match
      case Some(item) =>
        item._2
      case None =>
        null

  protected def getNextLink: Identify =
    viewNextPath match
      case Some(item) =>
        item._1
      case None =>
        null
}
