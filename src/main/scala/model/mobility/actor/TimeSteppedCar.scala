package org.interscity.htc
package model.mobility.actor

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import model.mobility.entity.state.CarState
import core.actor.manager.time.protocol.{ AdvanceToTick, TickCompleted }
import core.enumeration.TimePolicyEnum

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.util.{ CityMapUtil, GPSUtil, SpeedUtil, TrafficModels }
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.micro.{ProvideMicroContext, MyMicroIntention}
import org.interscity.htc.model.mobility.entity.state.micro.{MicroVehicleContext, MicroVehicleIntention}
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red

import scala.collection.mutable

/**
 * TimeStepped version of Car actor for mobility simulation with microscopic support
 * 
 * This version operates under TimeStepped_LTM management and supports both:
 * - Mesoscopic simulation (original TimeStepped behavior)
 * - Microscopic simulation with IDM and MOBIL models
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
  
  // Microscopic simulation state
  private var inMicroSimulation: Boolean = false
  private var currentMicroContext: Option[MicroVehicleContext] = None

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
      case d: ProvideMicroContext => handleMicroContext(event, d)
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
    
    // Exit microscopic simulation if we were in it
    if (inMicroSimulation) {
      exitMicroSimulation()
    }
    
    // In TimeStepped mode, we don't schedule events, just update state
    // The next movement will be processed in the next tick
  }

  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    // Check if this link uses microscopic simulation
    val linkUsesMS = checkIfLinkUsesMicroscopicSimulation(data)
    
    if (linkUsesMS) {
      // Enter microscopic simulation mode
      inMicroSimulation = true
      state.movableStatus = Moving
      logDebug(s"Car ${getEntityId} entrando em modo de simulação microscópica no link ${event.actorRefId}")
      
      // In microscopic simulation, movement is handled by sub-ticks
      // Don't create movement plan or schedule end time
      movementPlan = None
    } else {
      // Standard mesoscopic simulation
      inMicroSimulation = false
      
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
      
      logDebug(s"Carro ${getEntityId} entrou no link (meso), velocidade: ${speed.formatted("%.2f")}, " +
               s"tempo de viagem: ${travelTime.formatted("%.2f")}, chegada estimada: tick ${estimatedEndTick}")
    }
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
      "inMicroSimulation" -> inMicroSimulation,
      "movementPlan" -> movementPlan.map(p => Map(
        "linkId" -> p.linkId,
        "startTick" -> p.startTick,
        "estimatedEndTick" -> p.estimatedEndTick,
        "travelTime" -> p.travelTime
      )),
      "signalWaitingUntil" -> signalWaitingUntil
    )
  }

  /**
   * Handle microscopic simulation context from LinkActor
   */
  private def handleMicroContext(event: ActorInteractionEvent, data: ProvideMicroContext): Unit = {
    currentMicroContext = Some(data.context)
    inMicroSimulation = true
    
    logDebug(s"Car ${getEntityId} recebeu contexto micro para sub-tick ${data.subTick}")
    
    // Calculate intentions using IDM and MOBIL models
    val intention = calculateMicroIntention(data.context)
    
    // Send intention back to LinkActor
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.shardRefId,
      data = MyMicroIntention(intention, data.subTick),
      eventType = "MyMicroIntention",
      actorType = org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
    )
  }

  /**
   * Calculate micro intentions using IDM and MOBIL models
   */
  private def calculateMicroIntention(context: MicroVehicleContext): MicroVehicleIntention = {
    // Create a temporary MicroVehicleState from CarState for calculations
    val tempVehicleState = createTempMicroVehicleState(context)
    
    // Calculate longitudinal acceleration using IDM
    val idmAcceleration = TrafficModels.calculateIDMAcceleration(tempVehicleState, context.leader)
    
    // Evaluate lane change opportunities using MOBIL
    val desiredLaneChange = evaluateLaneChangeDesire(tempVehicleState, context)
    
    MicroVehicleIntention(
      vehicleId = getEntityId,
      desiredAcceleration = idmAcceleration,
      desiredLaneChange = desiredLaneChange
    )
  }

  /**
   * Create temporary MicroVehicleState from CarState for model calculations
   */
  private def createTempMicroVehicleState(context: MicroVehicleContext): org.interscity.htc.model.mobility.entity.state.micro.MicroVehicleState = {
    org.interscity.htc.model.mobility.entity.state.micro.MicroVehicleState(
      vehicleId = getEntityId,
      speed = estimateCurrentSpeed(context),
      position = estimateCurrentPosition(context),
      acceleration = 0.0, // Will be calculated
      lane = context.currentLane,
      actorRef = self,
      
      // Use CarState parameters
      maxAcceleration = state.maxAcceleration,
      desiredDeceleration = state.desiredDeceleration,
      desiredSpeed = state.desiredSpeed,
      timeHeadway = state.timeHeadway,
      minimumGap = state.minimumGap,
      politenessFactor = state.politenessFactor,
      laneChangeThreshold = state.laneChangeThreshold,
      maxSafeDeceleration = state.maxSafeDeceleration
    )
  }

  /**
   * Estimate current speed based on context
   */
  private def estimateCurrentSpeed(context: MicroVehicleContext): Double = {
    // In a real implementation, this would be maintained as part of the micro state
    // For now, estimate based on leader or use desired speed
    context.leader match {
      case Some(leader) =>
        // Adjust speed based on leader
        math.min(leader.speed * 0.9, state.desiredSpeed)
      case None =>
        // No leader, can drive at desired speed (limited by speed limit)
        math.min(state.desiredSpeed, context.speedLimit)
    }
  }

  /**
   * Estimate current position based on context
   */
  private def estimateCurrentPosition(context: MicroVehicleContext): Double = {
    // In a real implementation, this would be maintained as part of the micro state
    // For now, estimate based on follower or assume middle of link
    context.follower match {
      case Some(follower) =>
        follower.position + 20.0 // Assume 20m ahead of follower
      case None =>
        10.0 // Near beginning of link
    }
  }

  /**
   * Evaluate lane change desire using MOBIL model
   */
  private def evaluateLaneChangeDesire(
    vehicle: org.interscity.htc.model.mobility.entity.state.micro.MicroVehicleState,
    context: MicroVehicleContext
  ): Option[Int] = {
    
    val currentLane = context.currentLane
    val possibleLanes = List(
      if (currentLane > 0) Some(currentLane - 1) else None, // Left lane
      if (currentLane < 3) Some(currentLane + 1) else None // Right lane (assuming max 4 lanes)
    ).flatten
    
    // Evaluate each possible lane change
    for (targetLane <- possibleLanes) {
      val (targetLeader, targetFollower) = targetLane match {
        case lane if lane == currentLane - 1 => (context.leftLeader, context.leftFollower)
        case lane if lane == currentLane + 1 => (context.rightLeader, context.rightFollower)
        case _ => (None, None)
      }
      
      // Check if lane change is beneficial and safe
      if (TrafficModels.evaluateLaneChange(
        vehicle = vehicle,
        targetLane = targetLane,
        currentLeader = context.leader,
        targetLeader = targetLeader,
        targetFollower = targetFollower
      ) && TrafficModels.hasAdequateGap(vehicle, targetLeader, targetFollower)) {
        return Some(targetLane)
      }
    }
    
    None // No beneficial lane change found
  }

  /**
   * Check if the link uses microscopic simulation
   * This could be based on link properties, configuration, or other criteria
   */
  private def checkIfLinkUsesMicroscopicSimulation(linkData: LinkInfoData): Boolean = {
    // For now, this is a placeholder
    // In a real implementation, this could be:
    // - Based on link ID patterns (e.g., links starting with "micro_")
    // - Configuration parameter
    // - Link metadata
    // - Traffic density thresholds
    false // Default to mesoscopic for backward compatibility
  }

  /**
   * Exit microscopic simulation mode
   */
  private def exitMicroSimulation(): Unit = {
    inMicroSimulation = false
    currentMicroContext = None
    logDebug(s"Car ${getEntityId} saindo do modo de simulação microscópica")
  }
}

object TimeSteppedCar {
  def props(properties: Properties): org.apache.pekko.actor.Props =
    org.apache.pekko.actor.Props(classOf[TimeSteppedCar], properties)
}
