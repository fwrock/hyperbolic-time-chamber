package org.interscity.htc
package model.interscsimulator.actor

import core.actor.BaseActor
import model.interscsimulator.entity.state.MovableState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify
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
  override protected val actorId: String,
  private val timeManager: ActorRef,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, Identify] =
    mutable.Map[String, Identify]()
)(implicit m: Manifest[T])
    extends BaseActor[T](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  protected def requestRoute(): Unit = {}

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match
      case Start =>
        requestRoute()
      case Ready =>
        linkEnter()
      case _ =>
        logEvent(s"Event current status not handled ${state.movableStatus}")

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
    if (updatedCost < state.movableBestCost) {
      state.movableBestCost = updatedCost
      state.movableBestRoute = Some(event.data.path)
    }
  }

  private def handleReceiveRoute(): Unit = {
    state.movableStatus = Ready
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
             link,
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
            logEvent("Path item not handled")
      case None =>
        state.movableCurrentPath = getNextPath
        linkEnter()

  protected def linkLeaving(): Unit =
    state.movableCurrentPath match
      case Some(item) =>
        (item._1, item._2) match
          case (from, null) =>
            logEvent("No link to leave")
          case (node, link) =>
            sendMessageTo(
              link,
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
            logEvent("Path item not handled")
      case None =>
        logEvent("No link to leave")

  protected def getNextPath: Option[(Identify, Identify)] =
    state.movableBestRoute match
      case Some(path) =>
        Some(path.dequeue)
      case None =>
        logEvent("No path to follow")
        None

  protected def viewNextPath: Option[(Identify, Identify)] =
    state.movableBestRoute match
      case Some(path) =>
        Some(path.head)
      case None =>
        logEvent("No path to follow")
        None
}
