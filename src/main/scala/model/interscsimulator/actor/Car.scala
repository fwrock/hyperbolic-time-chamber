package org.interscity.htc
package model.interscsimulator.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.interscsimulator.entity.state.CarState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.interscsimulator.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.interscsimulator.util.SpeedUtil

import scala.collection.mutable
import org.interscity.htc.core.entity.event.data.BaseEventData
import org.interscity.htc.model.interscsimulator.entity.event.data.RequestRouteData
import org.interscity.htc.model.interscsimulator.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.interscsimulator.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.interscsimulator.entity.event.node.SignalStateData
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, RouteWaiting, Stopped, WaitingSignal, WaitingSignalState }
import org.interscity.htc.model.interscsimulator.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red

class Car(
  override protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  override protected val dependencies: mutable.Map[String, Identify] =
    mutable.Map[String, Identify]()
) extends Movable[CarState](
      actorId = actorId,
      timeManager = timeManager,
      data = data,
      dependencies = dependencies
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

  override def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {
    super.actInteractWith(event)
    event match {
      case e: ActorInteractionEvent[SignalStateData] => handleSignalState(e)
      case _ =>
        logEvent("Event not handled")
    }
  }

  override def requestRoute(): Unit = {
    state.movableStatus = RouteWaiting
    val data = RequestRouteData(
      requester = getSelfShard,
      requesterId = actorId,
      requesterClassType = getShardName,
      currentCost = 0,
      targetNodeId = state.destination,
      originNodeId = state.origin,
      path = mutable.Queue()
    )
    sendMessageTo(
      dependencies(state.origin),
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
              node,
              RequestSignalStateData(
                targetLinkId = link.id
              ),
              EventTypeEnum.RequestSignalState.toString
            )
      case None => ???
  }

  private def handleSignalState(event: ActorInteractionEvent[SignalStateData]): Unit =
    if (event.data.phase == Red) {
      state.movableStatus = WaitingSignal
      onFinishSpontaneous(Some(event.data.nextTick))
    } else {
      linkLeaving()
    }

  override def linkLeaving(): Unit = {
    state.movableStatus = Ready
    super.linkLeaving()
  }

  override def actHandleReceiveLeaveLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {
    state.distance += event.data.linkLength
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(event: ActorInteractionEvent[LinkInfoData]): Unit = {
    val time = linkDensitySpeed(
      length = event.data.linkLength,
      capacity = event.data.linkCapacity,
      numberOfCars = event.data.linkNumberOfCars,
      freeSpeed = event.data.linkFreeSpeed,
      lanes = event.data.linkLanes
    )
    state.movableStatus = Moving
    onFinishSpontaneous(Some(currentTick + time.toLong))
  }
}
