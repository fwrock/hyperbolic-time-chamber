package org.interscity.htc
package model.mobility.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.mobility.entity.state.CarState

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed
import org.interscity.htc.model.mobility.util.{ CityMapUtil, GPSUtil, SpeedUtil }
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum.{ Finished, Moving, Ready, RouteWaiting, Stopped, WaitingSignal, WaitingSignalState }
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

          // Reporta o início da jornada
//          report(
//            data = Map(
//              "event_type" -> "journey_started",
//              "car_id" -> getEntityId,
//              "origin" -> state.origin,
//              "destination" -> state.destination,
//              "route_cost" -> cost,
//              "route_length" -> pathQueue.size,
//              "tick" -> currentTick
//            ),
//            label = "journey_started"
//          )
          state.eventCount += 1

          // Reporta detalhes completos da rota planejada
//          report(
//            data = Map(
//              "event_type" -> "route_planned",
//              "car_id" -> getEntityId,
//              "origin" -> state.origin,
//              "destination" -> state.destination,
//              "route_cost" -> cost,
//              "route_length" -> pathQueue.size,
//              "route_links" -> pathQueue.map(_._1).mkString(","), // Lista de links do caminho
//              "route_nodes" -> pathQueue.map(_._2).mkString(","), // Lista de nós do caminho
//              "tick" -> currentTick
//            ),
//            label = "route_planned"
//          )
          state.eventCount += 1

          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            // Carro já está no destino
            // Reporta journey_completed para chegada imediata
//            report(
//              data = Map(
//                "event_type" -> "journey_completed",
//                "car_id" -> getEntityId,
//                "origin" -> state.origin,
//                "destination" -> state.destination,
//                "final_node" -> state.origin,
//                "reached_destination" -> (state.destination == state.origin),
//                "completion_reason" -> "already_at_destination",
//                "total_distance" -> state.distance,
//                "best_cost" -> cost,
//                "tick" -> currentTick
//              ),
//              label = "journey_completed"
//            )
            state.eventCount += 1

            // Reporta estatísticas finais de eventos
//            report(
//              data = Map(
//                "event_type" -> "vehicle_event_count",
//                "car_id" -> getEntityId,
//                "total_events" -> state.eventCount,
//                "tick" -> currentTick
//              ),
//              label = "vehicle_event_count"
//            )

            // Não chamar onFinish aqui, implementar diretamente
            state.movableStatus = Finished
            onFinishSpontaneous()
          }
        case None =>
          logError(
            s"Falha ao calcular rota de ${state.origin} para ${state.destination} para o carro ${getEntityId}."
          )

          // Reporta journey_completed para falha de rota
//          report(
//            data = Map(
//              "event_type" -> "journey_completed",
//              "car_id" -> getEntityId,
//              "origin" -> state.origin,
//              "destination" -> state.destination,
//              "final_node" -> state.origin,
//              "reached_destination" -> false,
//              "completion_reason" -> "route_calculation_failed",
//              "total_distance" -> state.distance,
//              "best_cost" -> state.bestCost,
//              "tick" -> currentTick
//            ),
//            label = "journey_completed"
//          )
          state.eventCount += 1

          // Reporta estatísticas finais de eventos
//          report(
//            data = Map(
//              "event_type" -> "vehicle_event_count",
//              "car_id" -> getEntityId,
//              "total_events" -> state.eventCount,
//              "tick" -> currentTick
//            ),
//            label = "vehicle_event_count"
//          )

          // Não chamar onFinish aqui, implementar diretamente
          state.movableStatus = Finished
          onFinishSpontaneous()
      }
    } catch {
      case e: Exception =>
        logError(s"Exceção durante a solicitação de rota para ${getEntityId}: ${e.getMessage}", e)

        // Reporta journey_completed para exceção
//        report(
//          data = Map(
//            "event_type" -> "journey_completed",
//            "car_id" -> getEntityId,
//            "origin" -> state.origin,
//            "destination" -> state.destination,
//            "final_node" -> state.origin,
//            "reached_destination" -> false,
//            "completion_reason" -> "exception_during_route_request",
//            "error_message" -> e.getMessage,
//            "total_distance" -> state.distance,
//            "best_cost" -> state.bestCost,
//            "tick" -> currentTick
//          ),
//          label = "journey_completed"
//        )
        state.eventCount += 1

        // Reporta estatísticas finais de eventos
//        report(
//          data = Map(
//            "event_type" -> "vehicle_event_count",
//            "car_id" -> getEntityId,
//            "total_events" -> state.eventCount,
//            "tick" -> currentTick
//          ),
//          label = "vehicle_event_count"
//        )

        // Não chamar onFinish aqui, implementar diretamente
        state.movableStatus = Finished
        onFinishSpontaneous()
    }
  }

  private def requestSignalState(): Unit =
    if (
      state.destination == state.currentPath
        .map(
          p => p._2.actorId
        )
        .orNull || state.movableBestRoute.isEmpty
    ) {
      val currentNodeId = getCurrentNode
      if (currentNodeId != null) {
        // Reporta journey_completed para chegada ao destino
//        report(
//          data = Map(
//            "event_type" -> "journey_completed",
//            "car_id" -> getEntityId,
//            "origin" -> state.origin,
//            "destination" -> state.destination,
//            "final_node" -> currentNodeId,
//            "reached_destination" -> (state.destination == currentNodeId),
//            "completion_reason" -> "reached_destination_or_end_of_route",
//            "total_distance" -> state.distance,
//            "best_cost" -> state.bestCost,
//            "tick" -> currentTick
//          ),
//          label = "journey_completed"
//        )
        state.eventCount += 1

        // Reporta estatísticas finais de eventos
//        report(
//          data = Map(
//            "event_type" -> "vehicle_event_count",
//            "car_id" -> getEntityId,
//            "total_events" -> state.eventCount,
//            "tick" -> currentTick
//          ),
//          label = "vehicle_event_count"
//        )

        // Não chamar onFinish aqui, implementar diretamente
        state.movableStatus = Finished
        onFinishSpontaneous()
      } else {
        state.movableStatus = Finished

        // Reporta journey_completed para fim sem nó atual
//        report(
//          data = Map(
//            "event_type" -> "journey_completed",
//            "car_id" -> getEntityId,
//            "origin" -> state.origin,
//            "destination" -> state.destination,
//            "final_node" -> "unknown",
//            "reached_destination" -> false,
//            "completion_reason" -> "no_current_node",
//            "total_distance" -> state.distance,
//            "best_cost" -> state.bestCost,
//            "tick" -> currentTick
//          ),
//          label = "journey_completed"
//        )
        state.eventCount += 1

        // Reporta estatísticas finais de eventos
//        report(
//          data = Map(
//            "event_type" -> "vehicle_event_count",
//            "car_id" -> getEntityId,
//            "total_events" -> state.eventCount,
//            "tick" -> currentTick
//          ),
//          label = "vehicle_event_count"
//        )

        onFinishSpontaneous()
      }
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
    // Reporta evento de conclusão da jornada
//    report(
//      data = Map(
//        "event_type" -> "journey_completed",
//        "car_id" -> getEntityId,
//        "origin" -> state.origin,
//        "destination" -> state.destination,
//        "final_node" -> nodeId,
//        "reached_destination" -> (state.destination == nodeId),
//        "total_distance" -> state.distance,
//        "best_cost" -> state.bestCost,
//        "tick" -> currentTick
//      ),
//      label = "journey_completed"
//    )
    state.eventCount += 1

    // Reporta estatísticas finais de eventos para este veículo (como no outro simulador)
//    report(
//      data = Map(
//        "event_type" -> "vehicle_event_count",
//        "car_id" -> getEntityId,
//        "total_events" -> state.eventCount,
//        "tick" -> currentTick
//      ),
//      label = "vehicle_event_count"
//    )

    // Implementar lógica da classe pai sem chamar super
    if (state.destination == nodeId) {
      state.movableReachedDestination = true
      state.movableStatus = Finished
    } else {
      state.movableStatus = Finished
    }

    // Finalizar o ator
    onFinishSpontaneous()
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength

//    report(
//      data = Map(
//        "event_type" -> "leave_link",
//        "car_id" -> getEntityId,
//        "link_id" -> event.actorRefId,
//        "link_length" -> data.linkLength,
//        "total_distance" -> state.distance,
//        "tick" -> currentTick
//      ),
//      label = "leave_link"
//    )
    state.eventCount += 1

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

//    report(
//      data = Map(
//        "event_type" -> "enter_link",
//        "car_id" -> getEntityId,
//        "link_id" -> event.actorRefId,
//        "link_length" -> data.linkLength,
//        "link_capacity" -> data.linkCapacity,
//        "cars_in_link" -> data.linkNumberOfCars,
//        "free_speed" -> data.linkFreeSpeed,
//        "calculated_speed" -> speed,
//        "travel_time" -> time,
//        "lanes" -> data.linkLanes,
//        "tick" -> currentTick
//      ),
//      label = "enter_link"
//    )
    state.eventCount += 1

    if (time.isNaN || time.isInfinite || time < 0) {
      logError(
        s"Invalid time calculated for link ${data} with length ${data.linkLength} and velocity $speed, current tick $currentTick. ${(currentTick + Math.ceil(time).toLong)}"
      )
    }
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
}
