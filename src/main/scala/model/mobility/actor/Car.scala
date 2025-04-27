package org.interscity.htc
package model.mobility.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.mobility.entity.state.CarState
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.mobility.util.SpeedUtil
import org.interscity.htc.model.mobility.entity.event.data.RequestRoute
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, RouteWaiting, Stopped, WaitingSignal, WaitingSignalState }
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red

class Car(
  private val properties: Properties
) extends Movable[CarState](
      properties = properties
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    state.movableStatus match {
      case Moving =>
        requestSignalState()
      case WaitingSignal =>
        leivingLink()
      case Stopped =>
        onFinishSpontaneous(Some(currentTick + 1))
      case _ => super.actSpontaneous(event)
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: SignalStateData => handleSignalState(event, d)
      case _ => super.actInteractWith(event)
    }
  }

  override def requestRoute(): Unit = {
    state.movableStatus = RouteWaiting
    report(data = state.movableStatus, "changed status")
    val data = RequestRoute(
      origin = state.origin,
      destination = state.destination
    )
    report(data = data)
    val dependency = getDependency(state.gpsId)
    sendMessageTo(
      entityId = dependency.id,
      shardId = dependency.resourceId,
      data = data,
      eventType = EventTypeEnum.RequestRoute.toString,
      actorType = CreationTypeEnum.valueOf(dependency.actorType)
    )
  }

  private def requestSignalState(): Unit = {
    state.movableStatus = WaitingSignalState
    report(data = state.movableStatus, "changed status")
    getCurrentNode match
      case node =>
        getNextLink match
          case link =>
            sendMessageTo(
              entityId = node.id,
              shardId = node.shardId,
              RequestSignalStateData(
                targetLinkId = link.id
              ),
              EventTypeEnum.RequestSignalState.toString
            )
          case null =>
      case null =>
  }

  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit =
    if (data.phase == Red) {
      state.movableStatus = WaitingSignal
      report(data = state.movableStatus, "changed status")
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      leivingLink()
    }

  override def leivingLink(): Unit = {
    state.movableStatus = Ready
    report(data = state.movableStatus, "link leaving changed status")
    super.leivingLink()
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    report(data = state.distance, "traveled distance")
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
    report(data = (time, data, s"km/s = ${data.linkLength / time}"), label = "time and average velocity")
    state.movableStatus = Moving
    report(data = state.movableStatus, "changed status")
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
}
