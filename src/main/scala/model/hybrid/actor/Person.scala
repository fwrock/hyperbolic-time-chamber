package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.actor.properties.Properties
import model.hybrid.entity.state.{PersonState, Activity, ArrivalLogistics, DriverAttributes}
import model.hybrid.entity.event.data.person.{StartTripData, TripCompletedData}
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

/** Person actor - Agent-based person in the simulation.
  * 
  * In the person-centric model, Person actors:
  * - Persist throughout the simulation day
  * - Manage their daily schedule (activities)
  * - Make mode choices for trips
  * - Activate private vehicles (Car, Bicycle, Motorcycle) as needed
  * - Receive trip completion notifications
  * 
  * Lifecycle:
  * 1. Person starts at first activity (Home)
  * 2. Wait until activity endTime
  * 3. Read nextActivity.arrivalLogistics
  * 4. Execute mode choice (decide transport mode)
  * 5. Send StartTrip to chosen vehicle
  * 6. Wait for TripCompleted
  * 7. Advance to next activity
  * 8. Repeat until schedule complete
  * 
  * @param properties Actor properties
  */
class Person(
  private val properties: Properties
) extends SimulationBaseActor[PersonState](
      properties = properties
    ) {
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state.isScheduleComplete) {
      logInfo(s"${getEntityId} completed daily schedule (${state.completedTrips} trips, ${state.totalDistanceTraveled}m)")
      onFinishSpontaneous(None) // Unregister from TimeManager
      return
    }
    
    state.currentActivity match {
      case Some(activity) =>
        // Check if it's time to leave this activity
        if (isActivityEndTime(activity)) {
          logDebug(s"${getEntityId} completing activity ${activity.activityType} at ${activity.nodeId}")
          startNextTrip()
        } else {
          // Still at activity, wait
          onFinishSpontaneous(Some(currentTick + 1))
        }
      
      case None =>
        // No current activity, advance
        advanceToNextActivity()
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: TripCompletedData => handleTripCompleted(event, d)
      case _ =>
        logWarn(s"Person event not handled: ${event.eventType}")
    }
  }
  
  /** Check if current activity's end time has been reached.
    */
  private def isActivityEndTime(activity: Activity): Boolean = {
    // Simple implementation: parse endTime as tick number
    // Production would parse "HH:MM" format and convert to ticks
    try {
      val endTick = activity.endTime.toLong
      currentTick >= endTick
    } catch {
      case _: NumberFormatException =>
        // Could implement time string parsing here ("08:00" -> tick)
        logWarn(s"Cannot parse endTime: ${activity.endTime}, assuming time reached")
        true
    }
  }
  
  /** Start trip to next activity.
    */
  private def startNextTrip(): Unit = {
    state.nextActivity match {
      case Some(nextActivity) =>
        nextActivity.arrivalLogistics match {
          case Some(logistics) =>
            // Execute mode choice and initiate trip
            val chosenMode = executeModeChoice(logistics)
            initiateTrip(nextActivity, logistics)
          
          case None =>
            // No logistics specified, assume instant arrival
            logInfo(s"${getEntityId} instant arrival at ${nextActivity.nodeId} (no logistics)")
            advanceToNextActivity()
        }
      
      case None =>
        // No next activity, schedule complete
        logInfo(s"${getEntityId} has no more activities")
        onFinishSpontaneous(None)
    }
  }
  
  /** Execute mode choice logic.
    * 
    * For now, uses the specified mode from logistics.
    * Production implementation could have utility-based mode choice model.
    */
  private def executeModeChoice(logistics: ArrivalLogistics): String = {
    // Simple: use specified mode
    // Advanced: utility-based mode choice considering:
    // - Travel time
    // - Cost
    // - Weather
    // - Vehicle availability
    logDebug(s"${getEntityId} chose mode: ${logistics.mode}")
    logistics.mode
  }
  
  /** Initiate trip to next activity.
    */
  private def initiateTrip(nextActivity: Activity, logistics: ArrivalLogistics): Unit = {
    state.currentActivity match {
      case Some(currentActivity) =>
        logistics.mode match {
          case "car" | "bicycle" | "motorcycle" =>
            // Private vehicle trip
            initiatePrivateVehicleTrip(currentActivity.nodeId, nextActivity.nodeId, logistics)
          
          case "walk" =>
            // Walking trip (simplified: instant for now)
            logInfo(s"${getEntityId} walking to ${nextActivity.nodeId}")
            advanceToNextActivity()
          
          case "transit" =>
            // Transit trip (future implementation)
            logInfo(s"${getEntityId} taking transit to ${nextActivity.nodeId}")
            advanceToNextActivity()
          
          case _ =>
            logWarn(s"Unknown mode: ${logistics.mode}, assuming instant arrival")
            advanceToNextActivity()
        }
      
      case None =>
        logWarn(s"${getEntityId} has no current activity")
        advanceToNextActivity()
    }
  }
  
  /** Initiate private vehicle trip.
    */
  private def initiatePrivateVehicleTrip(origin: String, destination: String, logistics: ArrivalLogistics): Unit = {
    logistics.vehicleId match {
      case Some(vehicleId) =>
        // Check if person owns this vehicle
        state.ownedVehicles.get(logistics.mode) match {
          case Some(ownedVehicleId) if ownedVehicleId == vehicleId =>
            // Send StartTrip to vehicle
            val startTripData = StartTripData(
              personId = getEntityId,
              origin = origin,
              destination = destination,
              driverAttributes = logistics.driverAttributes,
              startTick = currentTick
            )
            
            sendMessageTo(
              entityId = vehicleId,
              shardId = getShardId, // Assume vehicle on same shard (or lookup)
              data = startTripData,
              eventType = "StartTrip",
              actorType = LoadBalancedDistributed
            )
            
            // Update state
            state.copy(
              currentTripVehicleId = Some(vehicleId),
              currentTripStartTick = Some(currentTick)
            )
            
            logInfo(s"${getEntityId} started trip with $vehicleId: $origin -> $destination")
            
            // Wait for TripCompleted
            onFinishSpontaneous(Some(currentTick + 1))
          
          case _ =>
            logError(s"${getEntityId} does not own vehicle $vehicleId for mode ${logistics.mode}")
            advanceToNextActivity() // Skip trip
        }
      
      case None =>
        logError(s"${getEntityId} no vehicleId specified for mode ${logistics.mode}")
        advanceToNextActivity() // Skip trip
    }
  }
  
  /** Handle trip completion from vehicle.
    */
  private def handleTripCompleted(event: ActorInteractionEvent, data: TripCompletedData): Unit = {
    logInfo(s"${getEntityId} received trip completion from ${data.vehicleId}: " +
      s"${data.distanceTraveled}m in ${data.travelTime} ticks, reason: ${data.completionReason}")
    
    // Update state
    val newState = state.completeTrip(data.distanceTraveled)
    
    // Report trip statistics
    report(
      data = Map(
        "event_type" -> "trip_completed",
        "person_id" -> getEntityId,
        "vehicle_id" -> data.vehicleId,
        "distance_traveled" -> data.distanceTraveled,
        "travel_time" -> data.travelTime,
        "completion_reason" -> data.completionReason,
        "total_distance" -> newState.totalDistanceTraveled,
        "completed_trips" -> newState.completedTrips,
        "tick" -> currentTick
      ),
      label = "person_trip_completed"
    )
    
    // Advance to next activity
    advanceToNextActivity()
  }
  
  /** Advance to next activity in schedule.
    */
  private def advanceToNextActivity(): Unit = {
    val newState = state.advanceActivity()
    
    newState.currentActivity match {
      case Some(activity) =>
        logInfo(s"${getEntityId} arrived at ${activity.activityType} (${activity.nodeId})")
        
        // Report activity start
        report(
          data = Map(
            "event_type" -> "activity_start",
            "person_id" -> getEntityId,
            "activity_type" -> activity.activityType,
            "activity_sequence" -> activity.sequence,
            "node_id" -> activity.nodeId,
            "end_time" -> activity.endTime,
            "tick" -> currentTick
          ),
          label = "person_activity_start"
        )
        
        // Schedule next spontaneous event
        onFinishSpontaneous(Some(currentTick + 1))
      
      case None =>
        // Schedule complete
        logInfo(s"${getEntityId} completed all activities")
        
        report(
          data = Map(
            "event_type" -> "schedule_complete",
            "person_id" -> getEntityId,
            "total_trips" -> state.completedTrips,
            "total_distance" -> state.totalDistanceTraveled,
            "tick" -> currentTick
          ),
          label = "person_schedule_complete"
        )
        
        onFinishSpontaneous(None) // Unregister
    }
  }
}

/** Person companion object.
  */
object Person {
  def apply(properties: Properties): Person = {
    new Person(properties)
  }
}
