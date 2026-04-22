package org.interscity.htc
package model.hybrid.actor

import core.actor.SimulationBaseActor
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.actor.properties.Properties
import model.hybrid.entity.state.{PersonState, Activity, ArrivalLogistics, DriverAttributes}
import model.hybrid.entity.event.data.person.{StartTripData, TripCompletedData}
import model.hybrid.entity.event.data.bus.{RegisterPassengerData, BusRequestUnloadPassengerData, BusUnloadPassengerData}
import model.hybrid.entity.event.data.subway.{RegisterSubwayPassengerData, SubwayRequestUnloadPassengerData, SubwayUnloadPassengerData}
import model.hybrid.util.{GPSUtil, CityMapUtil}
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

import scala.collection.mutable

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

  /** Person should only re-register on the TM after migration if it was actually
    * registered at migration time.  During vehicle trips (and PT trips), Person calls
    * onFinishSpontaneous(None) and yields TM ownership to the vehicle.  Walking trips
    * keep Person on TM (scheduled to wake at arrival tick), so those are allowed.
    */
  override protected def shouldRegisterOnTimeManagerAfterMigration(): Boolean =
    state != null &&
      state.isSetScheduleOnTimeManager &&
      state.currentTripVehicleId.forall(_ == "walking")

  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (state == null) {
      logWarn(s"${getEntityId} actSpontaneous called with null state at tick=$currentTick — unscheduling")
      onFinishSpontaneous(None)
      return
    }
    if (state.isScheduleComplete) {
      logDebug(s"${getEntityId} completed daily schedule (${state.completedTrips} trips, ${state.totalDistanceTraveled}m)")
      onFinishSpontaneous(None) // Unregister from TimeManager
      return
    }
    
    // Check if currently in a trip
    if (state.currentTripVehicleId.isDefined) {
      // Walking trips complete when scheduled arrival time is reached
      if (state.currentTripVehicleId.contains("walking")) {
        // Walking trip arrived — complete it and advance
        advanceToNextActivity()
        return
      }

      // Vehicle trip in progress — Person should NOT be on the TimeManager.
      // The vehicle (Car/Bicycle/Motorcycle) owns TM communication during trips.
      // Person resumes TM interaction only when TripCompleted arrives via
      // actInteractWith → handleTripCompleted → advanceToNextActivity.
      // This branch is a defensive guard; it should not normally execute.
      logDebug(s"${getEntityId} unexpected spontaneous event during vehicle trip with ${state.currentTripVehicleId.get}")
      onFinishSpontaneous(None) // Re-unregister from TM
      return
    }
    
    state.currentActivity match {
      case Some(activity) =>
        // Check if it's time to leave this activity
        if (isActivityEndTime(activity)) {
          logDebug(s"${getEntityId} completing activity ${activity.activityType} at ${activity.nodeId}")
          startNextTrip()
        } else {
          // Sleep directly until activity end time.
          // No polling needed — nothing can interrupt an activity.
          val endTick = currentTick + getTickUntilActivityEnd(activity)
          onFinishSpontaneous(Some(endTick))
        }
      
      case None =>
        // No current activity, advance
        advanceToNextActivity()
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: TripCompletedData              => handleTripCompleted(event, d)
      case d: BusRequestUnloadPassengerData  => handlePTUnloadRequest(event, d.nodeId, "bus")
      case d: SubwayRequestUnloadPassengerData => handlePTUnloadRequest(event, d.nodeId, "subway")
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
  
  /** Calculate ticks until activity end time.
    */
  private def getTickUntilActivityEnd(activity: Activity): Long = {
    try {
      val endTick = activity.endTime.toLong
      Math.max(1L, endTick - currentTick)
    } catch {
      case _: NumberFormatException =>
        1L  // Cannot parse — assume time reached on next tick
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
            // If marked instant (same-node after PT snapping, etc.) skip routing entirely
            if (logistics.instant) {
              logDebug(s"${getEntityId} instant transition to ${nextActivity.nodeId} (instant=true)")
              advanceToNextActivity()
            } else {
              initiateTrip(nextActivity, logistics)
            }
          
          case None =>
            // No logistics specified, assume instant arrival
            logDebug(s"${getEntityId} instant arrival at ${nextActivity.nodeId} (no logistics)")
            advanceToNextActivity()
        }
      
      case None =>
        // No next activity, schedule complete
        logDebug(s"${getEntityId} has no more activities")
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
        logistics.mode.toLowerCase match {
          case "car" | "bicycle" | "motorcycle" =>
            // Private vehicle trip
            initiatePrivateVehicleTrip(currentActivity.nodeId, nextActivity.nodeId, logistics)
          
          case "walk" =>
            // Mesoscopic walking trip
            initiateWalkingTrip(currentActivity.nodeId, nextActivity.nodeId)
          
          case "transit" | "bus" | "subway" | "pt" | "mixed" =>
            // Public transport trip — register at boarding stop and wait for vehicle
            initiatePTTrip(currentActivity.nodeId, nextActivity.nodeId, logistics)
          
          case _ =>
            logWarn(s"Unknown mode: ${logistics.mode}, assuming instant arrival")
            advanceToNextActivity()
        }
      
      case None =>
        logWarn(s"${getEntityId} has no current activity")
        advanceToNextActivity()
    }
  }
  
  /** Initiate walking trip (mesoscopic).
    * 
    * Calculates route using road network, computes walking time based on
    * distance and walking speed (1.4 m/s typical), and schedules arrival.
    */
  private def initiateWalkingTrip(origin: String, destination: String): Unit = {
    // Calculate route using road network
    GPSUtil.calcRoute(originId = origin, destinationId = destination) match {
      case Some((routeCost, routeQueue)) =>
        // Calculate total distance by summing link lengths
        val totalDistance = calculateRouteDistance(routeQueue)
        
        // Walking speed: 1.4 m/s (5.04 km/h) typical pedestrian speed
        val walkingSpeed = 1.4 // m/s
        
        // Calculate walking time in seconds, then convert to ticks (1 tick = 1 second)
        val walkingTimeSeconds = totalDistance / walkingSpeed
        val walkingTimeTicks = math.ceil(walkingTimeSeconds).toLong
        
        // Calculate arrival tick
        val arrivalTick = currentTick + walkingTimeTicks
        
        // Update state with walking trip info
        state = state.copy(
          currentTripVehicleId = Some("walking"),
          currentTripStartTick = Some(currentTick)
        )
        
        logDebug(s"${getEntityId} walking from $origin to $destination: " +
          s"${totalDistance.toInt}m, ${walkingTimeTicks}s, arriving at tick $arrivalTick")
        
        // Report walking trip start
        report(
          data = Map(
            "event_type" -> "walking_trip_start",
            "person_id" -> getEntityId,
            "origin" -> origin,
            "destination" -> destination,
            "distance" -> totalDistance,
            "walking_time_ticks" -> walkingTimeTicks,
            "arrival_tick" -> arrivalTick,
            "walking_speed" -> walkingSpeed,
            "tick" -> currentTick
          ),
          label = "person_walking_start"
        )
        
        // Schedule arrival at destination
        onFinishSpontaneous(Some(arrivalTick))
      
      case None =>
        logError(s"${getEntityId} cannot find walking route from $origin to $destination")
        // No route found, skip trip and advance to next activity
        advanceToNextActivity()
    }
  }
  
  /** Initiate public transport trip (Bus or Subway).
    *
    * Person registers at the boarding stop (BusStop/SubwayStation) for the specified line,
    * then unregisters from the TimeManager. The PT vehicle carries the Person and periodically
    * asks "do you want to alight here?" via BusRequestUnloadPassengerData / SubwayRequestUnloadPassengerData.
    * Person responds and, when at the alighting node, advances to next activity.
    *
    * Required ArrivalLogistics fields for PT:
    *   - line: bus/subway line label
    *   - boardingStopId: BusStop/SubwayStation actor ID
    *   - boardingStopClassType: actor class type for shard routing
    *   - alightingNodeId: node where Person should alight
    */
  private def initiatePTTrip(origin: String, destination: String, logistics: ArrivalLogistics): Unit = {
    (logistics.line, logistics.boardingStopId, logistics.boardingStopClassType, logistics.alightingNodeId) match {
      case (Some(line), Some(stopId), Some(stopClassType), Some(alightingNode)) =>
        // Register at the boarding stop for the specified PT line
        val registrationData = logistics.mode.toLowerCase match {
          case "subway" => RegisterSubwayPassengerData(line = line)
          case _        => RegisterPassengerData(label = line)
        }
        
        sendMessageTo(
          entityId = stopId,
          shardId = stopClassType,
          data = registrationData,
          eventType = "RegisterPassenger",
          actorType = LoadBalancedDistributed
        )
        
        // Update state: mark as PT trip in progress
        state = state.copy(
          currentTripVehicleId = Some(s"pt:${logistics.mode}:$line"),
          currentTripStartTick = Some(currentTick),
          ptAlightingNodeId = Some(alightingNode),
          ptLine = Some(line)
        )
        
        logDebug(s"${getEntityId} registered at $stopId for $line, alighting at $alightingNode")
        
        // Report PT trip start
        report(
          data = Map(
            "event_type" -> "pt_trip_start",
            "person_id" -> getEntityId,
            "mode" -> logistics.mode,
            "line" -> line,
            "origin" -> origin,
            "destination" -> destination,
            "boarding_stop" -> stopId,
            "alighting_node" -> alightingNode,
            "tick" -> currentTick
          ),
          label = "person_pt_trip_start"
        )
        
        // Unregister from TimeManager — the PT vehicle owns TM communication.
        // Person will re-register when alighting via handlePTUnloadRequest → advanceToNextActivity.
        onFinishSpontaneous(None)
        
      case _ =>
        // Missing PT routing info — fall back to instant arrival
        logWarn(s"${getEntityId} PT trip missing routing info (line=${logistics.line}, " +
          s"boardingStop=${logistics.boardingStopId}, alightingNode=${logistics.alightingNodeId}). " +
          s"Falling back to instant arrival.")
        advanceToNextActivity()
    }
  }
  
  /** Handle unload request from PT vehicle (Bus or Subway).
    *
    * The vehicle asks "are you getting off at this node?" Person checks
    * if the node matches its alighting destination and responds accordingly.
    * If alighting, Person completes the trip and re-registers with the TimeManager.
    *
    * @param event the interaction event from the vehicle
    * @param nodeId the node ID the vehicle is currently at
    * @param ptType "bus" or "subway" for logging and response routing
    */
  private def handlePTUnloadRequest(event: ActorInteractionEvent, nodeId: String, ptType: String): Unit = {
    val isArrival = state.ptAlightingNodeId.contains(nodeId)
    
    // Send response back to the vehicle
    val responseData = ptType match {
      case "subway" => SubwayUnloadPassengerData(isArrival = isArrival)
      case _        => BusUnloadPassengerData(isArrival = isArrival)
    }
    
    sendMessageTo(
      entityId = event.actorRefId,
      shardId = event.actorClassType,
      data = responseData,
      eventType = "UnloadPassengerResponse"
    )
    
    if (isArrival) {
      val travelTime = state.currentTripStartTick.map(start => currentTick - start).getOrElse(0L)
      
      logDebug(s"${getEntityId} alighting from $ptType at node $nodeId after ${travelTime}s")
      
      // Report PT trip completion
      report(
        data = Map(
          "event_type" -> "pt_trip_completed",
          "person_id" -> getEntityId,
          "pt_type" -> ptType,
          "vehicle_id" -> event.actorRefId,
          "line" -> state.ptLine.getOrElse("unknown"),
          "alighting_node" -> nodeId,
          "travel_time" -> travelTime,
          "tick" -> currentTick
        ),
        label = "person_pt_trip_completed"
      )
      
      // Complete the trip and advance to next activity
      state = state.completeTrip(0.0)
      advanceToNextActivity()
    } else {
      logDebug(s"${getEntityId} staying on $ptType at node $nodeId (alighting at ${state.ptAlightingNodeId.getOrElse("?")})")
    }
  }
  
  /** Calculate total route distance by summing link lengths.
    */
  private def calculateRouteDistance(routeQueue: mutable.Queue[(String, String)]): Double = {
    var totalDistance = 0.0
    
    // Create a copy to iterate without modifying original
    val routeCopy = routeQueue.clone()
    
    while (routeCopy.nonEmpty) {
      val (linkEdgeGraphId, _) = routeCopy.dequeue()
      
      // Get EdgeGraph which contains the link length
      CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
        case Some(edgeLabel) =>
          // EdgeGraph has a length property
          totalDistance += edgeLabel.length
        case None =>
          logWarn(s"Edge label $linkEdgeGraphId not found")
      }
    }
    
    totalDistance
  }
  
  /** Initiate private vehicle trip.
    */
  private def initiatePrivateVehicleTrip(origin: String, destination: String, logistics: ArrivalLogistics): Unit = {
    logistics.vehicle match {
      case Some(vehicleRef) =>
        // Normalize mode key to lowercase — converter may output "Bicycle" or "bicycle"
        // depending on whether the scenario was generated before/after the mode_canonical fix.
        state.ownedVehicles.get(logistics.mode.toLowerCase) match {
          case Some(ownedVehicleRef) if ownedVehicleRef.id == vehicleRef.id =>
            // Send StartTrip to vehicle using complete reference (id + classType)
            val startTripData = StartTripData(
              personId = getEntityId,
              origin = origin,
              destination = destination,
              driverAttributes = logistics.driverAttributes,
              startTick = currentTick
            )
            
            sendMessageTo(
              entityId = vehicleRef.id,
              shardId = vehicleRef.classType, // Use vehicle's actual shard
              data = startTripData,
              eventType = "StartTrip",
              actorType = LoadBalancedDistributed
            )
            
            // Update state
            state = state.copy(
              currentTripVehicleId = Some(vehicleRef.id),
              currentTripStartTick = Some(currentTick)
            )
            
            logDebug(s"${getEntityId} started trip with ${vehicleRef.id}: $origin -> $destination")
            
            // Unregister from TimeManager — the vehicle now owns TM communication.
            // Person will re-register when TripCompleted arrives via actInteractWith.
            onFinishSpontaneous(None)
          
          case _ =>
            logError(s"${getEntityId} does not own vehicle ${vehicleRef.id} for mode ${logistics.mode}")
            advanceToNextActivity() // Skip trip
        }
      
      case None =>
        logError(s"${getEntityId} no vehicle specified for mode ${logistics.mode}")
        advanceToNextActivity() // Skip trip
    }
  }
  
  /** Handle trip completion from vehicle.
    */
  private def handleTripCompleted(event: ActorInteractionEvent, data: TripCompletedData): Unit = {
    logDebug(s"${getEntityId} received trip completion from ${data.vehicleId}: " +
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
    // If completing a walking trip, report it
    if (state.currentTripVehicleId.contains("walking")) {
      val walkingDistance = 0.0 // Distance already tracked during trip start
      state.currentTripStartTick match {
        case Some(startTick) =>
          val travelTime = currentTick - startTick
          
          report(
            data = Map(
              "event_type" -> "walking_trip_completed",
              "person_id" -> getEntityId,
              "travel_time" -> travelTime,
              "arrival_tick" -> currentTick,
              "tick" -> currentTick
            ),
            label = "person_walking_completed"
          )
          
          logDebug(s"${getEntityId} completed walking trip in ${travelTime}s")
        case None =>
          // No start tick recorded
      }
      
      // Complete the walking trip (distance is 0 since we already logged it at start)
      state = state.completeTrip(0.0)
    }
    
    state = state.advanceActivity()
    
    state.currentActivity match {
      case Some(activity) =>
        logDebug(s"${getEntityId} arrived at ${activity.activityType} (${activity.nodeId})")
        
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
        
        // Sleep directly until this activity's end time.
        // No polling — the TM will wake us at the exact tick.
        val endTick = try {
          Math.max(currentTick + 1, activity.endTime.toLong)
        } catch {
          case _: NumberFormatException => currentTick + 1
        }
        onFinishSpontaneous(Some(endTick))
      
      case None =>
        // Schedule complete
        logDebug(s"${getEntityId} completed all activities")
        
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
