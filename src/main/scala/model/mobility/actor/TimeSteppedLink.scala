package org.interscity.htc
package model.mobility.actor

import core.actor.BaseActor
import model.mobility.entity.state.LinkState
import core.actor.manager.time.protocol.{ AdvanceToTick, TickCompleted }
import core.enumeration.TimePolicyEnum

import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.model.LinkRegister
import model.mobility.entity.event.data.{ EnterLinkData, LeaveLinkData }

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData

import scala.collection.mutable

/**
 * TimeStepped version of LinkActor for mobility simulation
 * 
 * This version operates under TimeStepped_LTM management:
 * - Responds to AdvanceToTick events
 * - Updates link state synchronously with other mobility actors
 * - Maintains traffic density and speed calculations
 * - Reports tick completion for barrier synchronization
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

  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    logInfo(s"TimeSteppedLink ${getEntityId} inicializado com política TimeStepped")
  }

  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: EnterLinkData => queueEnterLink(event, d)
      case d: LeaveLinkData => queueLeaveLink(event, d)
      case _ => logWarn(s"Evento não tratado: ${event.data}")
    }

  /**
   * TimeStepped behavior - processes all queued events at each tick
   */
  override def actAdvanceToTick(event: AdvanceToTick): Unit = {
    val targetTick = event.targetTick
    logDebug(s"TimeSteppedLink ${getEntityId} processando tick $targetTick")
    
    try {
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
      
      // Update link state for this tick
      updateLinkState(targetTick)
      
      lastProcessedTick = targetTick
      
    } catch {
      case e: Exception =>
        logError(s"Erro durante processamento do tick $targetTick: ${e.getMessage}", e)
    }
    
    // Tick completion is handled by BaseActor
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
      logDebug(s"Veículo ${data.actorId} entrou no link ${getEntityId}. Total veículos: ${state.registered.size}")
    }
  }

  /**
   * Actually process leave link during tick processing
   */
  private def processLeaveLink(data: LeaveLinkData): Unit = {
    if (state != null) {
      val beforeSize = state.registered.size
      state.registered.filterInPlace(_.actorId != data.actorId)
      val afterSize = state.registered.size
      
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
    
    Map(
      "linkId" -> getEntityId,
      "vehicleCount" -> state.registered.size,
      "capacity" -> state.capacity,
      "utilization" -> (state.registered.size.toDouble / state.capacity * 100),
      "currentSpeed" -> state.currentSpeed,
      "freeSpeed" -> state.freeSpeed,
      "congestionFactor" -> state.congestionFactor,
      "length" -> state.length,
      "lastProcessedTick" -> lastProcessedTick
    )
  }
}

object TimeSteppedLink {
  def props(properties: Properties): org.apache.pekko.actor.Props =
    org.apache.pekko.actor.Props(classOf[TimeSteppedLink], properties)
}
