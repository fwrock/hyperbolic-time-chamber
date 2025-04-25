package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.MovableState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum.{ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo, ReceiveRoute}
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{Finished, Ready, RouteWaiting, Start}

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.{EnterLinkData, ForwardRoute, ForwardRouteData, LeaveLinkData, ReceiveRouteData, RequestRouteData}
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.model.RoutePathItem

abstract class Movable[T <: MovableState](
  private var movableId: String = null,
  private val timeManager: ActorRef
)(implicit m: Manifest[T])
    extends BaseActor[T](
      actorId = movableId,
      timeManager = timeManager
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

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: ForwardRouteData => handleForwardRoute(d)
      case d: ForwardRoute =>
      case d: ReceiveRouteData => handleReceiveRoute()
      case d: LinkInfoData     => handleLinkInfo(event, d)
      case _ =>
        logInfo("Event not handled")
    }

  private def handleForwardRoute(data: ForwardRouteData): Unit = {
    val updatedCost = data.updatedCost
    if (updatedCost < state.movableBestCost) {
      state.movableBestCost = updatedCost
      state.movableBestRoute = Some(data.path)
    }
  }

  private def handleForwardRoute(data: ForwardRoute): Unit = {
    val updatedCost = data.cost
    state.movableBestRoute = data.path
  }


  private def handleReceiveRoute(): Unit = {
    state.movableStatus = Ready
    linkEnter()
  }

  private def handleLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit =
    EventTypeEnum.valueOf(event.eventType) match {
      case ReceiveEnterLinkInfo => actHandleReceiveEnterLinkInfo(event, data)
      case ReceiveLeaveLinkInfo => actHandleReceiveLeaveLinkInfo(event, data)
      case _ =>
        logInfo("Event not handled")
    }

  protected def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {}

  protected def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {}

  protected def onFinish(nodeId: String): Unit =
    if (state.destination == nodeId) {
      state.movableReachedDestination = true
      state.movableStatus = Finished
    } else {
      state.movableStatus = Finished
    }
    onFinishSpontaneous()

  protected def linkEnter(): Unit =
    state.movableCurrentPath match
      case Some(item) =>
        (item._1, item._2) match
          case (from, null) =>
            state.movableCurrentPath = getNextPath
            linkEnter()
          case (node, link) =>
            sendMessageTo(
              link.id,
              link.classType,
              data = EnterLinkData(
                actorId = getActorId,
                actorRef = getSelfShard,
                actorType = state.actorType,
                actorSize = state.size
              ),
              EventTypeEnum.EnterLink.toString
            )
          case (node, null) =>
            onFinish(node.id)
          case _ =>
            logInfo("Path item not handled")
      case None =>
        state.movableCurrentPath = getNextPath
        linkEnter()

  protected def linkLeaving(): Unit =
    state.movableCurrentPath match
      case Some(item) =>
        (item._1, item._2) match
          case (from, null) =>
            logInfo("No link to leave")
          case (node, link) =>
            sendMessageTo(
              link.id,
              link.classType,
              data = LeaveLinkData(
                actorId = getActorId,
                actorRef = self,
                actorType = state.actorType,
                actorSize = state.size
              ),
              EventTypeEnum.LeaveLink.toString
            )
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
}
