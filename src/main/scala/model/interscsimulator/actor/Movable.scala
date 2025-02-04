package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.MovableState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum.{ReceiveEnterLinkInfo, ReceiveLeaveLinkInfo, ReceiveRoute}
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.{Finished, Ready, RouteWaiting, Start}

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.interscsimulator.entity.event.data.{EnterLinkData, ForwardRouteData, LeaveLinkData, ReceiveRouteData, RequestRouteData}
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

abstract class Movable[T <: MovableState](
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
)(implicit m: Manifest[T])
    extends BaseActor[T](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  private def requestRoute(): Unit = {
    state.status = RouteWaiting
    val data = RequestRouteData(
      requester = self,
      requesterId = actorId,
      currentCost = 0,
      targetNodeId = state.destination,
      path = mutable.Queue()
    )
    sendMessageTo(
      actorId = state.origin,
      actorRef = dependencies(state.origin),
      data,
      EventTypeEnum.RequestRoute.toString
    )
  }

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match
      case Start =>
        requestRoute()
      case Ready =>
        linkEnter()
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit =
    event match {
      case e: ActorInteractionEvent[ForwardRouteData] => handleForwardRoute(e)
      case e: ActorInteractionEvent[ReceiveRouteData] => handleReceiveRoute()
      case e: ActorInteractionEvent[LinkInfoData]     => handleLinkInfo(e)
      case _ =>
        logEvent("Event not handled")
    }

  private def handleForwardRoute(event: ActorInteractionEvent[ForwardRouteData]): Unit = {
    val updatedCost = event.data.updatedCost
    if (updatedCost < state.bestCost) {
      state.bestCost = updatedCost
      state.bestRoute = Some(event.data.path)
    }
  }

  private def handleReceiveRoute(): Unit = {
    state.status = Ready
    linkEnter()
  }

  private def handleLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit =
    EventTypeEnum.valueOf(event.eventType) match {
      case ReceiveEnterLinkInfo => actHandleReceiveEnterLinkInfo(event)
      case ReceiveLeaveLinkInfo => actHandleReceiveLeaveLinkInfo(event)
      case _ =>
        logEvent("Event not handled")
    }

  protected def actHandleReceiveEnterLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {}

  protected def actHandleReceiveLeaveLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {}

  private def onFinish(nodeId: String): Unit =
    if (state.destination == nodeId) {
      state.reachedDestination = true
      state.status = Finished
    } else {
      state.status = Finished
    }
    onFinishSpontaneous()

  protected def linkEnter(): Unit =
    state.currentPath match
      case Some(item) =>
        (item._1, item._2) match
          case (from, null) =>
            state.currentPath = getNextPath
            linkEnter()
          case (node, link) =>
            sendMessageTo(
              actorId = link.actorId,
              actorRef = link.actorRef,
              data = EnterLinkData(
                actorId = getActorId,
                actorRef = self,
                actorType = state.actorType,
                actorSize = state.size
              ),
              EventTypeEnum.EnterLink.toString
            )
          case (node, null) =>
            onFinish(node.actorId)
          case _ =>
            logEvent("Path item not handled")
      case None =>
        state.currentPath = getNextPath
        linkEnter()

  protected def linkLeaving(): Unit =
    state.currentPath match
      case Some(item) =>
        (item._1, item._2) match
          case (from, null) =>
            logEvent("No link to leave")
          case (node, link) =>
            sendMessageTo(
              actorId = link.actorId,
              actorRef = link.actorRef,
              data = LeaveLinkData(
                actorId = getActorId,
                actorRef = self,
                actorType = state.actorType,
                actorSize = state.size
              ),
              EventTypeEnum.LeaveLink.toString
            )
            state.currentPath = None
          case _ =>
            logEvent("Path item not handled")
      case None =>
        logEvent("No link to leave")

  private def getNextPath: Option[(RoutePathItem, RoutePathItem)] =
    state.bestRoute match
      case Some(path) =>
        Some(path.dequeue)
      case None =>
        logEvent("No path to follow")
        None

  protected def viewNextPath: Option[(RoutePathItem, RoutePathItem)] =
    state.bestRoute match
      case Some(path) =>
        Some(path.head)
      case None =>
        logEvent("No path to follow")
        None
}
