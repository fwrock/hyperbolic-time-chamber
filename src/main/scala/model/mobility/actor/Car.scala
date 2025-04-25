package org.interscity.htc
package model.mobility.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.mobility.entity.state.CarState

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.mobility.util.SpeedUtil

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.mobility.entity.event.data.RequestRouteData
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, RouteWaiting, Stopped, WaitingSignal, WaitingSignalState }
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red

class Car(
  private var id: String = null,
  private val timeManager: ActorRef = null
) extends Movable[CarState](
      movableId = id,
      timeManager = timeManager
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    super.actSpontaneous(event)
    state.movableStatus match {
      case Moving =>
        requestSignalState()
      case WaitingSignal =>
        linkLeaving()
      case Stopped =>
        onFinishSpontaneous(Some(currentTick + 1))
      case _ =>
        onFinishSpontaneous(Some(currentTick + 1))
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    super.actInteractWith(event)
    event.data match {
      case d: SignalStateData => handleSignalState(event, d)
      case _ =>
        logInfo("Event not handled")
    }
  }

  override def requestRoute(): Unit = {
    state.movableStatus = RouteWaiting
    val data = RequestRouteData(
      requester = getSelfShard,
      requesterId = actorId,
      requesterClassType = getShardId,
      currentCost = 0,
      targetNodeId = state.destination,
      originNodeId = state.origin,
      path = mutable.Queue()
    )
    val dependency = dependencies(state.origin)
    sendMessageTo(
      dependency.id,
      dependency.classType,
      data,
      EventTypeEnum.RequestRoute.toString
    )
  }

  private def requestSignalState(): Unit = {
    state.movableStatus = WaitingSignalState
    viewNextPath match
      case Some(item) =>
        (item._1, item._2) match
          case (node, link) =>
            sendMessageTo(
              node.id,
              node.classType,
              RequestSignalStateData(
                targetLinkId = link.id
              ),
              EventTypeEnum.RequestSignalState.toString
            )
      case None => ???
  }

  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit =
    if (data.phase == Red) {
      state.movableStatus = WaitingSignal
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      linkLeaving()
    }

  override def linkLeaving(): Unit = {
    state.movableStatus = Ready
    super.linkLeaving()
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val time = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )
    state.movableStatus = Moving
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }
}
