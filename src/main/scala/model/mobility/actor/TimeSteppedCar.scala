package org.interscity.htc
package model.mobility.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.mobility.entity.state.CarState
import core.actor.manager.time.protocol.{ AdvanceToTick, TickCompleted }
import core.enumeration.TimePolicyEnum

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.{ CityMapUtil, GPSUtil, SpeedUtil }
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red

import scala.collection.mutable

/**
 * TimeStepped version of Car actor for mobility simulation
 * 
 * This version operates under TimeStepped_LTM management:
 * - Responds to AdvanceToTick events instead of SpontaneousEvent
 * - Processes movement synchronously with other mobility actors
 * - Maintains state between ticks for continuous movement
 */
class TimeSteppedCar(
  private val properties: Properties
) extends TimeSteppedMovable[CarState](
      properties = properties.copy(timePolicy = Some(TimePolicyEnum.TimeSteppedSimulation))
    ) {

  // TimeStepped specific state for car
  private var movementPlan: Option[MovementPlan] = None
  private var signalWaitingUntil: Option[Long] = None
  private var lastProcessedTick: Long = 0

  case class MovementPlan(
    linkId: String,
    startTick: Long,
    estimatedEndTick: Long,
    travelTime: Double
  )

  override def actAdvanceToTick(event: AdvanceToTick): Unit = {
    val targetTick = event.targetTick
    logDebug(s"TimeSteppedCar ${getEntityId} processando tick $targetTick, status: ${state.movableStatus}")
    
    try {
      // Check if we're waiting for a signal
      signalWaitingUntil match {
        case Some(waitUntil) if targetTick < waitUntil =>
          // Still waiting for signal
          logDebug(s"Carro ${getEntityId} aguardando sinal até tick $waitUntil")
          return
        case Some(waitUntil) if targetTick >= waitUntil =>
          // Signal wait is over
          signalWaitingUntil = None
          state.movableStatus = Ready
          logDebug(s"Carro ${getEntityId} liberado do sinal no tick $targetTick")
        case None =>
          // Not waiting for signal
      }

      // Process based on current status
      state.movableStatus match {
        case Start =>
          processStart()
        case RouteWaiting =>
          // Still calculating route, wait
        case Ready =>
          processReady()
        case Moving =>
          processMoving(targetTick)
        case WaitingSignalState =>
          processSignalRequest()
        case WaitingSignal =>
          // Handled above in signal waiting logic
        case Finished =>
          // Car finished, could self-destruct or report
          processFinished()
        case Stopped =>
          // Car is stopped, might restart later
      }
      
      lastProcessedTick = targetTick
      
    } catch {
      case e: Exception =>
        logError(s"Erro durante processamento do tick $targetTick para carro ${getEntityId}: ${e.getMessage}", e)
        state.movableStatus = Finished
    }
    
    // Tick completion is handled by BaseActor
  }

  /**
   * Process start state - request initial route
   */
  private def processStart(): Unit = {
    logInfo(s"Carro ${getEntityId} iniciando jornada de ${state.origin} para ${state.destination}")
    requestRoute()
  }

  /**
   * Process ready state - ready to enter next link
   */
  private def processReady(): Unit = {
    if (state.movableBestRoute.nonEmpty) {
      enterLink()
    } else {
      logWarn(s"Carro ${getEntityId} em estado Ready mas sem rota. Finalizando.")
      state.movableStatus = Finished
    }
  }

  /**
   * Process moving state - check if movement is complete
   */
  private def processMoving(currentTick: Long): Unit = {
    movementPlan match {
      case Some(plan) if currentTick >= plan.estimatedEndTick =>
        // Movement complete, request signal state for next link
        logDebug(s"Carro ${getEntityId} completou movimento no link ${plan.linkId} no tick $currentTick")
        movementPlan = None
        requestSignalState()
      case Some(plan) =>
        // Still moving
        logDebug(s"Carro ${getEntityId} ainda em movimento no link ${plan.linkId}, " +
                 s"estimativa de chegada: tick ${plan.estimatedEndTick}")
      case None =>
        // No movement plan but in moving state, something went wrong
        logWarn(s"Carro ${getEntityId} em estado Moving mas sem plano de movimento")
        state.movableStatus = Ready
    }
  }

  /**
   * Process signal request state
   */
  private def processSignalRequest(): Unit = {
    requestSignalState()
  }

  /**
   * Process finished state
   */
  private def processFinished(): Unit = {
    if (state.movableReachedDestination) {
      logInfo(s"Carro ${getEntityId} chegou ao destino ${state.destination}. Distância total: ${state.distance}")
      report(
        data = Map(
          "carId" -> getEntityId,
          "origin" -> state.origin,
          "destination" -> state.destination,
          "totalDistance" -> state.distance,
          "completionTick" -> lastProcessedTick
        ),
        label = "car_trip_completed"
      )
    } else {
      logInfo(s"Carro ${getEntityId} finalizou sem chegar ao destino")
    }
    
    // Could self-destruct here or wait for simulation to clean up
    // selfDestruct()
  }

  override def requestRoute(): Unit = {
    if (state.movableStatus == Finished) {
      return
    }
    
    try {
      state.movableStatus = RouteWaiting
      logDebug(s"Calculando rota para carro ${getEntityId} de ${state.origin} para ${state.destination}")
      
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.bestCost = cost
          state.movableBestRoute = Some(pathQueue)
          state.movableStatus = Ready
          state.movableCurrentPath = None
          
          logInfo(s"Rota calculada para carro ${getEntityId}: custo ${cost}, ${pathQueue.size} segmentos")
          
          if (pathQueue.nonEmpty) {
            // Will enter link on next tick when status is processed
          } else {
            state.movableStatus = Finished
          }
        case None =>
          logError(s"Falha ao calcular rota de ${state.origin} para ${state.destination} para o carro ${getEntityId}")
          state.movableStatus = Finished
      }
    } catch {
      case e: Exception =>
        logError(s"Exceção durante cálculo de rota para ${getEntityId}: ${e.getMessage}", e)
        state.movableStatus = Finished
    }
  }

  /**
   * Request signal state from traffic light
   */
  private def requestSignalState(): Unit = {
    if (state.destination == state.currentPath.map(_._2.actorId).orNull || state.movableBestRoute.isEmpty) {
      state.movableStatus = Finished
      state.movableReachedDestination = (state.destination == getCurrentNode)
      return
    }

    state.movableStatus = WaitingSignalState
    
    getCurrentNode match {
      case nodeId if nodeId != null =>
        CityMapUtil.nodesById.get(nodeId) match {
          case Some(node) =>
            getNextLink match {
              case linkId if linkId != null =>
                logDebug(s"Carro ${getEntityId} solicitando estado do sinal: ${nodeId} -> ${linkId}")
                sendMessageTo(
                  entityId = node.id,
                  shardId = node.classType,
                  RequestSignalStateData(targetLinkId = linkId),
                  EventTypeEnum.RequestSignalState.toString
                )
              case null =>
                logWarn(s"Próximo link não encontrado para carro ${getEntityId}")
                state.movableStatus = Finished
            }
          case None =>
            logWarn(s"Nó ${nodeId} não encontrado para carro ${getEntityId}")
            state.movableStatus = Finished
        }
      case null =>
        logWarn(s"Nó atual não definido para carro ${getEntityId}")
        state.movableStatus = Finished
    }
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: SignalStateData => handleSignalState(event, d)
      case _ => super.actInteractWith(event)
    }

  /**
   * Handle traffic signal state response
   */
  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    if (data.phase == Red) {
      logDebug(s"Carro ${getEntityId} recebeu sinal vermelho, aguardando até tick ${data.nextTick}")
      state.movableStatus = WaitingSignal
      signalWaitingUntil = Some(data.nextTick)
    } else {
      logDebug(s"Carro ${getEntityId} recebeu sinal verde, saindo do link")
      state.movableStatus = Ready
      leavingLink()
    }
  }

  override def leavingLink(): Unit = {
    logDebug(s"Carro ${getEntityId} saindo do link atual")
    state.movableStatus = Ready
    super.leavingLink()
  }

  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    logDebug(s"Carro ${getEntityId} saiu do link, distância total: ${state.distance}")
    
    // In TimeStepped mode, we don't schedule events, just update state
    // The next movement will be processed in the next tick
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    // Calculate speed and travel time
    val speed = SpeedUtil.linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )

    val travelTime = if (speed > 0) data.linkLength / speed else Double.MaxValue
    
    if (travelTime.isNaN || travelTime.isInfinite || travelTime < 0) {
      logError(s"Tempo de viagem inválido calculado para link ${data}: ${travelTime}")
      state.movableStatus = Finished
      return
    }

    // Create movement plan
    val estimatedEndTick = lastProcessedTick + Math.ceil(travelTime).toLong
    movementPlan = Some(MovementPlan(
      linkId = event.actorRefId,
      startTick = lastProcessedTick,
      estimatedEndTick = estimatedEndTick,
      travelTime = travelTime
    ))
    
    state.movableStatus = Moving
    
    logDebug(s"Carro ${getEntityId} entrou no link, velocidade: ${speed.formatted("%.2f")}, " +
             s"tempo de viagem: ${travelTime.formatted("%.2f")}, chegada estimada: tick ${estimatedEndTick}")
  }

  /**
   * Get current car statistics for reporting
   */
  def getCarStatistics: Map[String, Any] = {
    Map(
      "carId" -> getEntityId,
      "status" -> state.movableStatus.toString,
      "origin" -> state.origin,
      "destination" -> state.destination,
      "currentDistance" -> state.distance,
      "reachedDestination" -> state.movableReachedDestination,
      "lastProcessedTick" -> lastProcessedTick,
      "movementPlan" -> movementPlan.map(p => Map(
        "linkId" -> p.linkId,
        "startTick" -> p.startTick,
        "estimatedEndTick" -> p.estimatedEndTick,
        "travelTime" -> p.travelTime
      )),
      "signalWaitingUntil" -> signalWaitingUntil
    )
  }
}

object TimeSteppedCar {
  def props(properties: Properties): org.apache.pekko.actor.Props =
    org.apache.pekko.actor.Props(classOf[TimeSteppedCar], properties)
}
