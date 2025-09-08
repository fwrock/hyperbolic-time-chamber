package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.LinkState
import core.entity.event.control.simulation.{ AdvanceToTick, TickCompleted }
import core.enumeration.TimePolicyEnum

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
import model.mobility.entity.event.data.{ EnterLinkData, LeaveLinkData }
import model.mobility.entity.state.micro.{MicroVehicleState, MicroVehicleContext, MicroVehicleIntention}
import model.mobility.entity.event.micro.{ProvideMicroContext, MyMicroIntention}
import model.mobility.util.TrafficModels

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Success, Failure}

/**
 * TimeStepped version of LinkActor for mobility simulation with microscopic support
 * 
 * This version operates under TimeStepped_LTM management and supports both:
 * - Mesoscopic simulation (original behavior)
 * - Microscopic simulation with IDM and MOBIL models
 */
class TimeSteppedLink(
  private val properties: Properties
) extends BaseActor[LinkState](
      properties = properties.copy(timePolicy = Some(TimePolicyEnum.TimeSteppedSimulation))
    ) {

  // TimeStepped specific state
  private val pendingEnters: mutable.Queue[EnterLinkData] = mutable.Queue()
  private val pendingLeaves: mutable.Queue[LeaveLinkData] = mutable.Queue()
  private var lastProcessedTick: Long = 0
  
  // Microscopic simulation state
  private val microIntentions: mutable.Map[String, MicroVehicleIntention] = mutable.Map()
  private var currentSubTick: Int = 0
  private var expectedIntentions: Int = 0

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    logInfo(s"TimeSteppedLink ${getEntityId} inicializado com política TimeStepped, tipo: ${state.simulationType}")
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: EnterLinkData => queueEnterLink(event, d)
      case d: LeaveLinkData => queueLeaveLink(event, d)
      case d: MyMicroIntention => handleMicroIntention(d)
      case _ => logWarn(s"Evento não tratado: ${event.data}")
    }

  /**
   * TimeStepped behavior - processes all queued events at each tick
   * Supports both mesoscopic and microscopic simulation
   */
  override def actAdvanceToTick(event: AdvanceToTick): Unit = {
    val targetTick = event.targetTick
    logDebug(s"TimeSteppedLink ${getEntityId} processando tick $targetTick (${state.simulationType})")
    
    try {
      // Process pending enters and leaves first
      processPendingEvents()
      
      // Choose simulation mode
      state.simulationType match {
        case "micro" => processMicroscopicSimulation(targetTick)
        case "meso" | _ => processMesoscopicSimulation(targetTick)
      }
      
      lastProcessedTick = targetTick
      
    } catch {
      case e: Exception =>
        logError(s"Erro durante processamento do tick $targetTick: ${e.getMessage}", e)
    }
    
    // Tick completion is handled by BaseActor
  }

  /**
   * Process all pending enters and leaves
   */
  private def processPendingEvents(): Unit = {
    // Process all pending enters
    while (pendingEnters.nonEmpty) {
      val enterData = pendingEnters.dequeue()
      processEnterLink(enterData)
    }
    
    // Process all pending leaves
    while (pendingLeaves.nonEmpty) {
      val leaveData = pendingLeaves.dequeue()
      processLeaveLink(leaveData)
    }
  }

  /**
   * Execute microscopic simulation for the current tick
   */
  private def processMicroscopicSimulation(tick: Long): Unit = {
    if (state.microVehicles.isEmpty) return
    
    val numSubTicks = (state.globalTickDuration / state.microTimestep).toInt
    logDebug(s"Iniciando simulação microscópica: $numSubTicks sub-ticks")
    
    // Execute sub-tick loop
    for (subTick <- 1 to numSubTicks) {
      currentSubTick = subTick
      executeMicroSubTick(subTick, numSubTicks)
    }
    
    // Handle vehicles that reached the end of the link
    handleVehicleExits()
    
    logDebug(s"Simulação microscópica concluída para tick $tick")
  }

  /**
   * Execute a single microscopic sub-tick
   */
  private def executeMicroSubTick(subTick: Int, totalSubTicks: Int): Unit = {
    microIntentions.clear()
    expectedIntentions = state.microVehicles.size
    
    if (expectedIntentions == 0) return
    
    // Send context to all vehicles and collect intentions
    for ((vehicleId, vehicle) <- state.microVehicles) {
      val context = buildMicroContext(vehicle)
      
      // Send context to vehicle actor
      sendMessageTo(
        entityId = vehicleId,
        shardId = "vehicles", // Assuming vehicles are in "vehicles" shard
        data = ProvideMicroContext(context, subTick, totalSubTicks),
        eventType = "ProvideMicroContext",
        actorType = LoadBalancedDistributed
      )
    }
    
    // Wait for all intentions (simplified - in real implementation use async/await pattern)
    waitForMicroIntentions()
    
    // Resolve conflicts and update vehicle states
    resolveMicroMovements()
  }

  /**
   * Build context for a vehicle in microscopic simulation
   */
  private def buildMicroContext(vehicle: MicroVehicleState): MicroVehicleContext = {
    val vehicles = state.microVehicles.values
    
    MicroVehicleContext(
      vehicleId = vehicle.vehicleId,
      currentLane = vehicle.lane,
      leader = TrafficModels.findLeader(vehicle.position, vehicle.lane, vehicles),
      follower = TrafficModels.findFollower(vehicle.position, vehicle.lane, vehicles),
      leftLeader = if (vehicle.lane > 0) TrafficModels.findLeader(vehicle.position, vehicle.lane - 1, vehicles) else None,
      leftFollower = if (vehicle.lane > 0) TrafficModels.findFollower(vehicle.position, vehicle.lane - 1, vehicles) else None,
      rightLeader = if (vehicle.lane < state.lanes - 1) TrafficModels.findLeader(vehicle.position, vehicle.lane + 1, vehicles) else None,
      rightFollower = if (vehicle.lane < state.lanes - 1) TrafficModels.findFollower(vehicle.position, vehicle.lane + 1, vehicles) else None,
      linkLength = state.length,
      speedLimit = state.speedLimit,
      microTimestep = state.microTimestep
    )
  }

  /**
   * Handle micro intention from vehicle
   */
  private def handleMicroIntention(intention: MyMicroIntention): Unit = {
    microIntentions(intention.intention.vehicleId) = intention.intention
    
    // Check if we have all intentions for current sub-tick
    if (microIntentions.size >= expectedIntentions) {
      // All intentions received, can proceed with movement resolution
      logDebug(s"Todas as intenções coletadas para sub-tick $currentSubTick")
    }
  }

  /**
   * Wait for all micro intentions (simplified implementation)
   */
  private def waitForMicroIntentions(): Unit = {
    // In a real implementation, this would use proper async/await mechanisms
    // For now, we assume intentions are processed synchronously
    var attempts = 0
    val maxAttempts = 10
    
    while (microIntentions.size < expectedIntentions && attempts < maxAttempts) {
      Thread.sleep(10) // Small delay
      attempts += 1
    }
    
    if (microIntentions.size < expectedIntentions) {
      logWarn(s"Nem todas as intenções foram recebidas: ${microIntentions.size}/$expectedIntentions")
    }
  }

  /**
   * Resolve conflicts and update vehicle states based on intentions
   */
  private def resolveMicroMovements(): Unit = {
    val laneChangeConflicts = mutable.Map[Int, mutable.ListBuffer[String]]()
    
    // First pass: identify lane change conflicts
    for ((vehicleId, intention) <- microIntentions) {
      intention.desiredLaneChange match {
        case Some(targetLane) =>
          laneChangeConflicts.getOrElseUpdate(targetLane, mutable.ListBuffer()) += vehicleId
        case None => // No lane change
      }
    }
    
    // Resolve lane change conflicts (first-come-first-served or by priority)
    val approvedLaneChanges = resolveLaneChangeConflicts(laneChangeConflicts)
    
    // Update all vehicle states
    for ((vehicleId, vehicle) <- state.microVehicles) {
      microIntentions.get(vehicleId) match {
        case Some(intention) =>
          // Apply longitudinal movement (IDM)
          val updatedVehicle = TrafficModels.updateVehicleKinematics(
            vehicle, 
            intention.desiredAcceleration, 
            state.microTimestep
          )
          
          // Apply lane change if approved
          val finalVehicle = if (approvedLaneChanges.contains(vehicleId)) {
            updatedVehicle.copy(lane = intention.desiredLaneChange.get)
          } else {
            updatedVehicle
          }
          
          state.microVehicles(vehicleId) = finalVehicle
          
        case None =>
          logWarn(s"Nenhuma intenção recebida para veículo $vehicleId")
          // Apply default behavior (maintain current speed)
          val defaultAccel = 0.0
          val updatedVehicle = TrafficModels.updateVehicleKinematics(
            vehicle, 
            defaultAccel, 
            state.microTimestep
          )
          state.microVehicles(vehicleId) = updatedVehicle
      }
    }
  }

  /**
   * Resolve lane change conflicts
   */
  private def resolveLaneChangeConflicts(
    conflicts: mutable.Map[Int, mutable.ListBuffer[String]]
  ): Set[String] = {
    val approved = mutable.Set[String]()
    
    for ((lane, candidates) <- conflicts) {
      if (candidates.size == 1) {
        // No conflict, approve the lane change
        approved += candidates.head
      } else if (candidates.size > 1) {
        // Conflict: choose based on priority (e.g., vehicle closest to lane)
        val vehicleStates = candidates.map(id => (id, state.microVehicles(id)))
        val winner = vehicleStates.minBy(_._2.position) // Choose the one in front
        approved += winner._1
        
        logDebug(s"Conflito de troca de faixa resolvido na faixa $lane: vencedor ${winner._1}")
      }
    }
    
    approved.toSet
  }

  /**
   * Handle vehicles that have reached the end of the link
   */
  private def handleVehicleExits(): Unit = {
    val exitingVehicles = state.microVehicles.filter(_._2.position >= state.length)
    
    for ((vehicleId, vehicle) <- exitingVehicles) {
      logDebug(s"Veículo $vehicleId saindo do link (posição: ${vehicle.position})")
      
      // Remove from micro simulation
      state.microVehicles.remove(vehicleId)
      
      // Notify the vehicle actor that it has completed the link
      // (This would trigger the normal mesoscopic transition to next link)
      sendMessageTo(
        entityId = vehicleId,
        shardId = "vehicles",
        data = LinkInfoData(
          linkLength = state.length,
          linkCapacity = state.capacity,
          linkNumberOfCars = state.microVehicles.size,
          linkFreeSpeed = state.freeSpeed,
          linkLanes = state.lanes
        ),
        eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
        actorType = LoadBalancedDistributed
      )
    }
  }

  /**
   * Execute mesoscopic simulation (original behavior)
   */
  private def processMesoscopicSimulation(tick: Long): Unit = {
    updateLinkState(tick)
  }

  /**
   * Queue enter link request for processing during next tick
   */
  private def queueEnterLink(event: ActorInteractionEvent, data: EnterLinkData): Unit = {
    logDebug(s"Enfileirando entrada do veículo ${data.actorId} no link ${getEntityId}")
    pendingEnters.enqueue(data)
    
    // Send immediate response with current link info for vehicle decision making
    sendLinkInfo(event.actorRefId, event.shardRefId, EventTypeEnum.ReceiveEnterLinkInfo.toString)
  }

  /**
   * Queue leave link request for processing during next tick
   */
  private def queueLeaveLink(event: ActorInteractionEvent, data: LeaveLinkData): Unit = {
    logDebug(s"Enfileirando saída do veículo ${data.actorId} do link ${getEntityId}")
    pendingLeaves.enqueue(data)
    
    // Send immediate response with updated link info
    sendLinkInfo(event.actorRefId, event.shardRefId, EventTypeEnum.ReceiveLeaveLinkInfo.toString)
  }

  /**
   * Actually process enter link during tick processing
   */
  private def processEnterLink(data: EnterLinkData): Unit = {
    if (state != null) {
      val linkRegister = LinkRegister(
        actorId = data.actorId,
        shardId = data.shardId,
        actorType = data.actorType,
        actorSize = data.actorSize,
        actorCreationType = data.actorCreationType
      )
      
      state.registered.add(linkRegister)
      
      // If microscopic simulation, also create MicroVehicleState
      if (state.simulationType == "micro") {
        createMicroVehicleState(data)
      }
      
      logDebug(s"Veículo ${data.actorId} entrou no link ${getEntityId}. Total veículos: ${state.registered.size}")
    }
  }

  /**
   * Create MicroVehicleState for a vehicle entering the link in micro mode
   */
  private def createMicroVehicleState(data: EnterLinkData): Unit = {
    // Find least congested lane for initial placement
    val laneOccupancy = (0 until state.lanes).map { lane =>
      lane -> state.microVehicles.values.count(_.lane == lane)
    }.toMap
    
    val targetLane = laneOccupancy.minBy(_._2)._1
    
    // Determine safe initial speed
    val leadersInLane = state.microVehicles.values.filter(_.lane == targetLane).toSeq
    val initialSpeed = if (leadersInLane.nonEmpty) {
      val avgSpeed = leadersInLane.map(_.speed).sum / leadersInLane.size
      math.min(avgSpeed, state.freeSpeed * 0.8) // Start slightly below average or free speed
    } else {
      state.freeSpeed * 0.8 // Start at 80% of free speed
    }
    
    val microVehicle = MicroVehicleState(
      vehicleId = data.actorId,
      speed = math.max(initialSpeed, 5.0), // Minimum 5 m/s
      position = 0.0, // Start at beginning of link
      acceleration = 0.0,
      lane = targetLane,
      actorRef = null, // Would be set in real implementation
      
      // Default IDM parameters (could be customized per vehicle)
      maxAcceleration = 2.0 + scala.util.Random.nextGaussian() * 0.2,
      desiredDeceleration = 3.0 + scala.util.Random.nextGaussian() * 0.3,
      desiredSpeed = state.speedLimit * (0.9 + scala.util.Random.nextGaussian() * 0.1),
      timeHeadway = 1.5 + scala.util.Random.nextGaussian() * 0.2,
      minimumGap = 2.0 + scala.util.Random.nextGaussian() * 0.3,
      
      // Default MOBIL parameters
      politenessFactor = 0.2 + scala.util.Random.nextGaussian() * 0.05,
      laneChangeThreshold = 0.1 + scala.util.Random.nextGaussian() * 0.02,
      maxSafeDeceleration = 4.0 + scala.util.Random.nextGaussian() * 0.5
    )
    
    state.microVehicles(data.actorId) = microVehicle
    logDebug(s"Criado estado microscópico para veículo ${data.actorId} na faixa $targetLane")
  }

  /**
   * Actually process leave link during tick processing
   */
  private def processLeaveLink(data: LeaveLinkData): Unit = {
    if (state != null) {
      val beforeSize = state.registered.size
      state.registered.filterInPlace(_.actorId != data.actorId)
      val afterSize = state.registered.size
      
      // Remove from microscopic simulation if applicable
      if (state.simulationType == "micro") {
        state.microVehicles.remove(data.actorId)
      }
      
      if (beforeSize != afterSize) {
        logDebug(s"Veículo ${data.actorId} saiu do link ${getEntityId}. Total veículos: ${afterSize}")
      } else {
        logWarn(s"Tentativa de remover veículo ${data.actorId} que não estava no link ${getEntityId}")
      }
    }
  }

  /**
   * Update link state calculations for the current tick
   */
  private def updateLinkState(tick: Long): Unit = {
    if (state == null) return
    
    // Calculate current density (vehicles per unit length)
    val density = if (state.length > 0) state.registered.size.toDouble / state.length else 0.0
    
    // Update current speed based on density using basic traffic flow model
    state.currentSpeed = calculateSpeed(density)
    
    // Update congestion factor
    state.congestionFactor = calculateCongestionFactor(density)
    
    logDebug(s"Link ${getEntityId} tick $tick: ${state.registered.size} veículos, " +
             s"densidade: ${density.formatted("%.2f")}, velocidade: ${state.currentSpeed.formatted("%.2f")}")
  }

  /**
   * Calculate speed based on density using fundamental diagram
   */
  private def calculateSpeed(density: Double): Double = {
    if (state == null) return 0.0
    
    val maxDensity = state.capacity.toDouble / state.length // vehicles per unit length at capacity
    val densityRatio = if (maxDensity > 0) density / maxDensity else 0.0
    
    // Simple linear speed-density relationship
    // Speed decreases linearly from free speed to 0 as density approaches capacity
    val speed = state.freeSpeed * math.max(0.0, 1.0 - densityRatio)
    
    math.max(speed, 5.0) // Minimum speed of 5 units to prevent complete stops
  }

  /**
   * Calculate congestion factor based on density
   */
  private def calculateCongestionFactor(density: Double): Double = {
    if (state == null) return 1.0
    
    val maxDensity = state.capacity.toDouble / state.length
    val densityRatio = if (maxDensity > 0) density / maxDensity else 0.0
    
    // Congestion factor increases exponentially with density
    1.0 + math.pow(densityRatio, 2) * 3.0 // Factor ranges from 1.0 to 4.0
  }

  /**
   * Send link information to requesting actor
   */
  private def sendLinkInfo(actorId: String, shardId: String, eventType: String): Unit = {
    val linkInfo = if (state == null) {
      LinkInfoData(
        linkCapacity = Int.MaxValue,
        linkFreeSpeed = 50,
        linkLanes = 1
      )
    } else {
      LinkInfoData(
        linkLength = state.length,
        linkCapacity = state.capacity,
        linkNumberOfCars = state.registered.size,
        linkFreeSpeed = state.freeSpeed,
        linkLanes = state.lanes,
        linkCurrentSpeed = Some(state.currentSpeed),
        linkCongestionFactor = Some(state.congestionFactor)
      )
    }
    
    sendMessageTo(
      entityId = actorId,
      shardId = shardId,
      data = linkInfo,
      eventType = eventType,
      actorType = LoadBalancedDistributed
    )
  }

  /**
   * Get current cost of traversing this link
   */
  def getCurrentCost: Double = {
    if (state == null) return Double.MaxValue
    
    val speedFactor = if (state.currentSpeed > 0) state.length / state.currentSpeed else Double.MaxValue
    state.length * state.congestionFactor + speedFactor
  }

  /**
   * Get link statistics for reporting
   */
  def getLinkStatistics: Map[String, Any] = {
    if (state == null) return Map.empty
    
    val baseStats = Map(
      "linkId" -> getEntityId,
      "vehicleCount" -> state.registered.size,
      "capacity" -> state.capacity,
      "utilization" -> (state.registered.size.toDouble / state.capacity * 100),
      "currentSpeed" -> state.currentSpeed,
      "freeSpeed" -> state.freeSpeed,
      "congestionFactor" -> state.congestionFactor,
      "length" -> state.length,
      "lastProcessedTick" -> lastProcessedTick,
      "simulationType" -> state.simulationType
    )
    
    // Add microscopic statistics if in micro mode
    if (state.simulationType == "micro" && state.microVehicles.nonEmpty) {
      val microStats = Map(
        "microVehicleCount" -> state.microVehicles.size,
        "avgSpeed" -> (state.microVehicles.values.map(_.speed).sum / state.microVehicles.size),
        "avgPosition" -> (state.microVehicles.values.map(_.position).sum / state.microVehicles.size),
        "laneDistribution" -> state.microVehicles.values.groupBy(_.lane).mapValues(_.size)
      )
      baseStats ++ microStats
    } else {
      baseStats
    }
  }
}

object TimeSteppedLink {
  def props(properties: Properties): org.apache.pekko.actor.Props =
    org.apache.pekko.actor.Props(classOf[TimeSteppedLink], properties)
}
