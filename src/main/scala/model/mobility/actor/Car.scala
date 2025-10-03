package org.interscity.htc
package model.mobility.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import model.mobility.entity.state.CarState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.mobility.util.{CityMapUtil, GPSUtil, SpeedUtil}
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{Finished, Moving, Ready, RouteWaiting, Stopped, WaitingSignal, WaitingSignalState}
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red

class Car(
  private val properties: Properties
) extends Movable[CarState](
      properties = properties
    ) {

  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match {
      case Moving =>
        requestSignalState()
      case WaitingSignal =>
        leavingLink()
      case Stopped =>
        onFinishSpontaneous(Some(currentTick + 1))
      case _ => super.actSpontaneous(event)
    }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: SignalStateData => handleSignalState(event, d)
      case _                  => super.actInteractWith(event)
    }

  override def requestRoute(): Unit = {
    if (state.movableStatus == Finished) {
      return
    }
    try {
      state.movableStatus = RouteWaiting
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.bestCost = cost
          state.movableBestRoute = Some(pathQueue)
          state.movableStatus = Ready
          state.movableCurrentPath = None

          report(
            data = Map(
              "event_type" -> "journey_started",
              "car_id" -> getEntityId,
              "origin" -> state.origin,
              "destination" -> state.destination,
              "route_cost" -> cost,
              "route_length" -> pathQueue.size,
              "tick" -> currentTick
            ),
            label = "vehicle_flow"
          )
          
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            state.movableStatus = Finished
            onFinishSpontaneous()
          }
        case None =>
          logError(s"Falha ao calcular rota de ${state.origin} para ${state.destination} para o carro ${getEntityId}.")
          state.movableStatus = Finished
          onFinishSpontaneous()
      }
    } catch {
      case e: Exception =>
        logError(s"Exceção durante a solicitação de rota para ${getEntityId}: ${e.getMessage}", e)
        state.movableStatus = Finished
        onFinishSpontaneous()
    }
  }

  private def requestSignalState(): Unit = {
    if (
      state.destination == state.currentPath
        .map(
          p => p._2.actorId
        )
        .orNull || state.movableBestRoute.isEmpty
    ) {
      state.movableStatus = Finished
      onFinishSpontaneous()
      selfDestruct()
    } else {
      state.movableStatus = WaitingSignalState
      getCurrentNode match
        case nodeId =>
          CityMapUtil.nodesById.get(nodeId) match
            case Some(node) =>
              getNextLink match
                case linkId =>
                  sendMessageTo(
                    entityId = node.id,
                    shardId = node.classType,
                    RequestSignalStateData(
                      targetLinkId = linkId
                    ),
                    EventTypeEnum.RequestSignalState.toString
                  )
                case null =>
            case None =>
        case null =>
    }
  }

  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit =
    if (data.phase == Red) {
      state.movableStatus = WaitingSignal
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      leavingLink()
    }

  override def leavingLink(): Unit = {
    state.movableStatus = Ready
    super.leavingLink()
  }

  override protected def onFinish(nodeId: String): Unit = {
    report(
      data = Map(
        "event_type" -> "journey_completed",
        "car_id" -> getEntityId,
        "origin" -> state.origin,
        "destination" -> state.destination,
        "final_node" -> nodeId,
        "reached_destination" -> (state.destination == nodeId),
        "total_distance" -> state.distance,
        "best_cost" -> state.bestCost,
        "tick" -> currentTick
      ),
      label = "vehicle_flow"
    )
    
    super.onFinish(nodeId)
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength

    report(
      data = Map(
        "event_type" -> "leave_link",
        "car_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "link_length" -> data.linkLength,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "vehicle_flow"
    )
    
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val speed = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )

    val time = data.linkLength / speed
    state.movableStatus = Moving

    report(
      data = Map(
        "event_type" -> "enter_link",
        "car_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "link_length" -> data.linkLength,
        "link_capacity" -> data.linkCapacity,
        "cars_in_link" -> data.linkNumberOfCars,
        "free_speed" -> data.linkFreeSpeed,
        "calculated_speed" -> speed,
        "travel_time" -> time,
        "lanes" -> data.linkLanes,
        "tick" -> currentTick
      ),
      label = "vehicle_flow"
    )
    
    if (time.isNaN || time.isInfinite || time < 0) {
      logError(s"Invalid time calculated for link ${data} with length ${data.linkLength} and velocity $speed, current tick $currentTick. ${(currentTick + Math.ceil(time).toLong)}")
    }
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
}
