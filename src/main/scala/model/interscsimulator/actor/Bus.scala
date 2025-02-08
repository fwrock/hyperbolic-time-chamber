package org.interscity.htc
package model.interscsimulator.actor

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.actor.BaseActor
import org.interscity.htc.core.entity.event.SpontaneousEvent
import org.interscity.htc.model.interscsimulator.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.interscsimulator.entity.event.data.{ EnterLinkData, LeaveLinkData }
import org.interscity.htc.model.interscsimulator.entity.state.BusState
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, Start, WaitingSignal, WaitingSignalState }
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

class Bus(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, ActorRef] =
    mutable.Map[String, ActorRef]()
) extends Movable[BusState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.status match
      case Start =>
        state.status = Ready
        linkEnter()
      case Ready =>
        linkEnter()
      case Moving =>
        requestSignalState()
      case WaitingSignal =>
        linkLeaving()
      case _ =>
        logEvent(s"Event current status not handled ${state.status}")

  override def getNextPath: Option[(RoutePathItem, RoutePathItem)] =
    state.bestRoute match
      case Some(path) =>
        if state.currentPathPosition < path.size then
          val nextPath = path(state.currentPathPosition)
          state.currentPathPosition += 1
          Some(nextPath)
        else
          state.currentPathPosition = 0
          Some(path(state.currentPathPosition))
      case None =>
        None

  private def requestSignalState(): Unit = {
    state.status = WaitingSignalState
    viewNextPath match
      case Some(item) =>
        (item._1, item._2) match
          case (node, link) =>
            sendMessageTo(
              actorId = node.actorId,
              actorRef = node.actorRef,
              RequestSignalStateData(
                targetLinkId = link.actorId
              ),
              EventTypeEnum.RequestSignalState.toString
            )
      case None => ???
  }
}
