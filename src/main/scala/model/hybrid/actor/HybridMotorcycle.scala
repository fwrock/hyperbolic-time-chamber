package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.actor.Movable
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.mobility.util.{GPSUtil, SpeedUtil}
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed

import org.interscity.htc.model.hybrid.entity.state.{HybridMotorcycleState, MicroMotorcycleState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}

/** HybridMotorcycle actor - NEW vehicle type for hybrid simulator.
  * 
  * Motorcycles have unique advantages in traffic:
  * - Higher acceleration than cars (3.5 m/s² vs 2.6 m/s²)
  * - Can filter between lanes (lane splitting)
  * - Smaller vehicle, more maneuverable (2.5m)
  * - Higher speeds possible
  * - Accepts smaller gaps
  * 
  * MESO mode:
  * - Aggregate motorcycle flow
  * - Higher speeds than cars
  * 
  * MICRO mode:
  * - Detailed positioning with motorcycle-specific parameters
  * - Aggressive behavior (configurable)
  * - Lane filtering capability
  * - Gap acceptance modeling
  * - Faster acceleration/deceleration
  * 
  * @param properties Actor properties
  */
class HybridMotorcycle(
  private val properties: Properties
) extends Movable[HybridMotorcycleState](
      properties = properties
    ) {
  
  /** Car-following model with lower randomness (more predictable).
    */
  private val carFollowingModel: CarFollowingModel = KraussModel.withRandomness(0.15) // More aggressive
  
  /** Current link being traversed.
    */
  private var currentLinkId: Option[String] = None
  
  /** Link entry tick.
    */
  private var linkEntryTick: Option[Tick] = None
  
  /** Aggressiveness factor [0.0 - 1.0] for motorcycle behavior.
    */
  private val aggressiveness: Double = 0.7 // Default aggressive behavior
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    state.status match {
      case Start =>
        requestRoute()
      
      case Ready =>
        enterLink()
      
      case Moving =>
        if (state.isMicroMode) {
          // MICRO mode: check position and possibly filter lanes
          state.microState.foreach { micro =>
            // Check if can filter between lanes (if traffic is slow)
            if (shouldAttemptLaneFiltering(micro)) {
              log.debug(s"Motorcycle attempting lane filtering")
            }
            
            if (micro.positionInLink >= getCurrentLinkLength) {
              leavingLink()
            }
          }
        } else {
          // MESO mode: faster progression
          onFinishSpontaneous(Some(currentTick + 1))
        }
      
      case Finished =>
        onFinishSpontaneous()
      
      case _ =>
        logWarn(s"Motorcycle status not handled: ${state.status}")
        onFinishSpontaneous(Some(currentTick + 1))
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: MicroEnterLinkData => handleMicroEnterLink(event, d)
      case d: MicroUpdateData => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData => handleMicroLeaveLink(event, d)
      case _ => super.actInteractWith(event)
    }
  }
  
  override def requestRoute(): Unit = {
    if (state.status == Finished) {
      return
    }
    try {
      GPSUtil.calcRoute(originId = state.origin, destinationId = state.destination) match {
        case Some((cost, pathQueue)) =>
          state.bestRoute = Some(pathQueue)
          state.status = Ready
          state.updateCurrentPath(None)
          
          // Report journey started
          report(
            data = Map(
              "event_type" -> "journey_started",
              "motorcycle_id" -> getEntityId,
              "origin" -> state.origin,
              "destination" -> state.destination,
              "route_length" -> pathQueue.size,
              "aggressiveness" -> aggressiveness,
              "tick" -> currentTick
            ),
            label = "journey_started"
          )
          
          if (pathQueue.nonEmpty) {
            enterLink()
          } else {
            finishJourney("already_at_destination", state.origin)
          }
        
        case None =>
          logError(s"Failed to calculate route for motorcycle ${getEntityId}")
          finishJourney("route_calculation_failed", state.origin)
      }
    } catch {
      case e: Exception =>
        logError(s"Exception during motorcycle route request: ${e.getMessage}", e)
        finishJourney("exception", state.origin)
    }
  }
  
  /** Handle entering MICRO link.
    */
  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit = {
    logDebug(s"Motorcycle entering MICRO link ${data.linkId}, lane ${data.assignedLane}")
    
    currentLinkId = Some(data.linkId)
    linkEntryTick = Some(currentTick)
    
    // Initialize microscopic state with motorcycle parameters
    val initialMicroState = MicroMotorcycleState(
      positionInLink = 0.0,
      velocity = data.speedLimit * 0.9, // Start at 90% speed limit (aggressive)
      acceleration = 0.0,
      currentLane = data.assignedLane,
      leaderVehicle = None,
      gapToLeader = data.linkLength,
      leaderVelocity = data.speedLimit,
      maxAcceleration = 3.5, // Motorcycle-specific (HIGHER than car)
      maxDeceleration = 5.0, // Better braking
      minGap = 1.5, // Smaller gap (more aggressive)
      desiredVelocity = math.min(data.speedLimit, 16.67), // 60 km/h max
      reactionTime = 0.9, // Faster reaction
      vehicleLength = 2.5, // Motorcycle length
      canFilterLanes = true, // Lane splitting capability
      aggressiveness = this.aggressiveness,
      desiredLane = None, // Will change lanes aggressively
      laneChangeProgress = 0.0,
      filteringBetweenLanes = false
    )
    
    // Activate MICRO mode
    state.activateMicroMode(initialMicroState)
    state.status = Moving
    
    // Report micro enter
    report(
      data = Map(
        "event_type" -> "enter_micro_link",
        "motorcycle_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "lane" -> initialMicroState.currentLane,
        "can_filter_lanes" -> initialMicroState.canFilterLanes,
        "aggressiveness" -> initialMicroState.aggressiveness,
        "link_length" -> data.linkLength,
        "initial_velocity" -> initialMicroState.velocity,
        "tick" -> currentTick
      ),
      label = "enter_micro_link"
    )
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Handle microscopic update.
    */
  private def handleMicroUpdate(event: ActorInteractionEvent, data: MicroUpdateData): Unit = {
    state.microState.foreach { micro =>
      // Check if should start/stop filtering
      val isFiltering = shouldAttemptLaneFiltering(micro) && data.velocity < 5.0 // Slow traffic
      
      // Update microscopic state
      val updatedMicro = micro.copy(
        positionInLink = data.position,
        velocity = data.velocity,
        acceleration = data.acceleration,
        currentLane = data.currentLane,
        leaderVehicle = data.leaderVehicle,
        gapToLeader = data.gapToLeader,
        leaderVelocity = data.leaderVelocity,
        filteringBetweenLanes = isFiltering
      )
      
      state.updateMicroState(updatedMicro)
      
      if (isFiltering) {
        log.debug(s"Motorcycle filtering between lanes at pos=${data.position}, vel=${data.velocity}")
      } else {
        log.debug(s"Motorcycle micro update: pos=${data.position}, vel=${data.velocity}, accel=${data.acceleration}")
      }
    }
  }
  
  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    logDebug(s"Motorcycle leaving MICRO link ${data.linkId}")
    
    val travelTime = linkEntryTick.map(entryTick => currentTick - entryTick).getOrElse(0L)
    
    state.distance += data.distanceTraveled
    
    // Report micro leave
    report(
      data = Map(
        "event_type" -> "leave_micro_link",
        "motorcycle_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "travel_time_ticks" -> travelTime,
        "distance_traveled" -> data.distanceTraveled,
        "average_speed" -> data.averageSpeed,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_micro_link"
    )
    
    // Deactivate MICRO mode
    state.deactivateMicroMode()
    currentLinkId = None
    linkEntryTick = None
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Handle entering MESO link.
    */
  override def actHandleReceiveEnterLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    logDebug(s"Motorcycle entering MESO link ${event.actorRefId}")
    
    // Motorcycles can navigate through traffic faster (assume 1.2x car speed)
    val baseSpeed = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )
    
    val motorcycleSpeed = baseSpeed * 1.2 // Faster than cars in traffic
    val time = data.linkLength / motorcycleSpeed
    
    state.status = Moving
    
    // Report meso enter
    report(
      data = Map(
        "event_type" -> "enter_link",
        "motorcycle_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "link_length" -> data.linkLength,
        "travel_time" -> time,
        "speed" -> motorcycleSpeed,
        "speed_multiplier" -> 1.2,
        "tick" -> currentTick
      ),
      label = "enter_link"
    )
    
    onFinishSpontaneous(Some(currentTick + Math.ceil(time).toLong))
  }
  
  /** Handle leaving MESO link.
    */
  override def actHandleReceiveLeaveLinkInfo(
    event: ActorInteractionEvent,
    data: LinkInfoData
  ): Unit = {
    state.distance += data.linkLength
    
    // Report meso leave
    report(
      data = Map(
        "event_type" -> "leave_link",
        "motorcycle_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_link"
    )
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Finish motorcycle journey.
    */
  private def finishJourney(reason: String, finalNode: String): Unit = {
    report(
      data = Map(
        "event_type" -> "journey_completed",
        "motorcycle_id" -> getEntityId,
        "origin" -> state.origin,
        "destination" -> state.destination,
        "final_node" -> finalNode,
        "reached_destination" -> (state.destination == finalNode),
        "completion_reason" -> reason,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "journey_completed"
    )
    
    state.status = Finished
  }
  
  /** Check if motorcycle should attempt lane filtering.
    * 
    * Lane filtering (lane splitting) is when motorcycle rides between lanes of slow/stopped traffic.
    * Criteria:
    * - Traffic is slow (< 30 km/h)
    * - Gap to leader is small
    * - Aggressiveness factor is high
    */
  private def shouldAttemptLaneFiltering(micro: MicroMotorcycleState): Boolean = {
    if (!micro.canFilterLanes) return false
    
    val trafficIsSlow = micro.leaderVelocity < 8.33 // < 30 km/h
    val gapIsSmall = micro.gapToLeader < 20.0 // < 20m
    val isAggressive = micro.aggressiveness > 0.5
    
    trafficIsSlow && gapIsSmall && isAggressive
  }
  
  /** Get current link length.
    */
  private def getCurrentLinkLength: Double = {
    currentLinkId.flatMap { linkId =>
      org.interscity.htc.model.mobility.util.CityMapUtil.edgeLabelsById.get(linkId).map(_.length)
    }.getOrElse(500.0)
  }
}

/** HybridMotorcycle companion object.
  */
object HybridMotorcycle {
  def apply(properties: Properties): HybridMotorcycle = {
    new HybridMotorcycle(properties)
  }
  
  /** Create motorcycle with custom aggressiveness.
    * 
    * @param properties Actor properties
    * @param aggressiveness Aggressiveness factor [0.0 - 1.0]
    */
  def withAggressiveness(properties: Properties, aggressiveness: Double): HybridMotorcycle = {
    new HybridMotorcycle(properties) {
      val customAggressiveness: Double = math.max(0.0, math.min(1.0, aggressiveness))
    }
  }
}
