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
import org.interscity.htc.system.database.redis.RedisClientManager

import scala.collection.mutable

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
      logWarn(s"Carro ${getEntityId} já está no estado Finished. Não pode solicitar nova rota.")
      return
    }
    try {
//      report(data = s"${state.movableStatus} -> $RouteWaiting", "change_status_request_route")
      state.movableStatus = RouteWaiting

      val redisClientManager = RedisClientManager()

      GPSUtil.calcRoute(redisManager = redisClientManager, originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.bestCost = cost
          state.movableBestRoute = Some(pathQueue)
          state.movableStatus = Ready
          state.movableCurrentPath = None
//          report(data = s"Rota calculada com ${pathQueue.size} segmentos. Custo: $cost. Status: ${state.movableStatus}", "route_calculated")
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            logWarn(s"Rota calculada é vazia para ${getEntityId} de ${state.origin} para ${state.destination}.")
            state.movableStatus = Finished
            onFinishSpontaneous()
          }

        case None =>
          logError(s"Falha ao calcular rota de ${state.origin} para ${state.destination} para o carro ${getEntityId}.")
          state.movableStatus = Finished // Ou um estado de erro específico
          onFinishSpontaneous()
      }
      redisClientManager.closeConnection()
    } catch {
      case e: Exception =>
        logError(s"Exceção durante a solicitação de rota para ${getEntityId}: ${e.getMessage}", e)
        state.movableStatus = Finished
        onFinishSpontaneous()
    }
  }

  private def requestSignalState(): Unit = {
//    report(data = s"${state.movableStatus} -> $WaitingSignalState", "change status")
    if (
      state.destination == state.currentPath
        .map(
          p => p._2.actorId
        )
        .orNull || state.bestRoute.isEmpty
    ) {
//      report(data = s"${state.movableStatus} -> $Finished", "travel finished")
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
//                  report(data = s"${nodeId} -> ${linkId}", label = "request signal state")
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
//      report(data = s"${state.movableStatus} -> $WaitingSignal", "change status")
      state.movableStatus = WaitingSignal
      onFinishSpontaneous(Some(data.nextTick))
    } else {
      leavingLink()
    }

  override def leavingLink(): Unit = {
//    report(data = s"${state.movableStatus} -> $Ready", "change status")
    state.movableStatus = Ready
    super.leavingLink()
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
//    report(data = state.distance, "traveled distance")
    onFinishSpontaneous(Some(currentTick + 1))
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    val velcoty = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )

    val time = data.linkLength / velcoty
//    report(
//      data = (time, data.linkLength, data.linkFreeSpeed, data.linkLength / time),
//      label = "(time, length, free speed, speed)"
//    )
//    report(data = s"${state.movableStatus} -> $Moving", "change status")
    state.movableStatus = Moving
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
}
