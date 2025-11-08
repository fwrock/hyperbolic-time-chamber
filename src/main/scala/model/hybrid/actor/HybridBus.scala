package org.interscity.htc
package model.hybrid.actor

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.types.Tick

import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.model.mobility.actor.Movable
import org.interscity.htc.model.mobility.entity.event.data.bus.{BusLoadPassengerData, BusRequestPassengerData, BusRequestUnloadPassengerData, BusUnloadPassengerData}
import org.interscity.htc.model.mobility.entity.event.data.link.LinkInfoData
import org.interscity.htc.model.mobility.entity.event.data.vehicle.RequestSignalStateData
import org.interscity.htc.model.mobility.entity.event.node.SignalStateData
import org.interscity.htc.model.mobility.entity.state.enumeration.EventTypeEnum
import org.interscity.htc.model.mobility.entity.state.enumeration.MovableStatusEnum._
import org.interscity.htc.model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Red
import org.interscity.htc.model.mobility.util.{BusUtil, SpeedUtil}
import org.interscity.htc.model.mobility.util.BusUtil.loadPersonTime
import org.interscity.htc.model.mobility.util.SpeedUtil.linkDensitySpeed

import org.interscity.htc.model.hybrid.entity.state.{HybridBusState, MicroBusState}
import org.interscity.htc.model.hybrid.entity.state.enumeration.SimulationModeEnum
import org.interscity.htc.model.hybrid.entity.event.data._
import org.interscity.htc.model.hybrid.micro.model.{CarFollowingModel, KraussModel}

/** HybridBus actor supporting both MESO and MICRO simulation modes.
  * 
  * Extends the base Bus actor with microscopic simulation capabilities.
  * Buses have unique characteristics:
  * - Larger vehicle length (12m vs 4.5m for cars)
  * - Slower acceleration (1.2 m/s² vs 2.6 m/s²)
  * - Passenger capacity tracking
  * - Bus stop interactions
  * - Lane restrictions (bus lanes)
  * 
  * MESO mode:
  * - Standard mesoscopic behavior with passenger loading/unloading
  * - Aggregate speed calculation
  * 
  * MICRO mode:
  * - Individual positioning with bus-specific parameters
  * - Bus stop interactions at microscopic precision
  * - Lane restrictions enforced
  * - Passenger management continues
  * 
  * @param properties Actor properties
  */
class HybridBus(
  private val properties: Properties
) extends Movable[HybridBusState](
      properties = properties
    ) {
  
  /** Car-following model (same as car, but with bus parameters).
    */
  private val carFollowingModel: CarFollowingModel = KraussModel()
  
  /** Current link being traversed.
    */
  private var currentLinkId: Option[String] = None
  
  /** Link entry tick.
    */
  private var linkEntryTick: Option[Tick] = None
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    state.status match {
      case Start =>
        state.status = Ready
        enterLink()
      
      case Ready =>
        enterLink()
      
      case Moving =>
        if (state.isMicroMode) {
          // MICRO mode: check position and handle bus stops
          state.microState.foreach { micro =>
            // Check if at bus stop
            checkBusStopAtPosition(micro.positionInLink)
            
            // Check if reached end of link
            if (micro.positionInLink >= getCurrentLinkLength) {
              leavingLink()
            }
          }
        } else {
          // MESO mode: standard behavior
//           requestLoadPassenger()
//           requestUnloadPeopleData()
        }
      
      case WaitingSignal | WaitingLoadPassenger | WaitingUnloadPassenger =>
//         if (isEndNodeState && nodeStateMaxTime == event.tick) {
          state.status = Moving
          leavingLink()
//         }
      
      case _ =>
        logInfo(s"Event current status not handled ${state.status}")
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case d: BusLoadPassengerData => handleBusLoadPeople(event, d)
      case d: BusUnloadPassengerData => handleUnloadPassenger(event, d)
      case d: SignalStateData => handleSignalState(event, d)
      case d: MicroEnterLinkData => handleMicroEnterLink(event, d)
      case d: MicroUpdateData => handleMicroUpdate(event, d)
      case d: MicroLeaveLinkData => handleMicroLeaveLink(event, d)
      case _ => super.actInteractWith(event)
    }
  }
  
  /** Handle entering MICRO link.
    */
  private def handleMicroEnterLink(event: ActorInteractionEvent, data: MicroEnterLinkData): Unit = {
    logDebug(s"Bus entering MICRO link ${data.linkId}, lane ${data.assignedLane}")
    
    currentLinkId = Some(data.linkId)
    linkEntryTick = Some(currentTick)
    
    // Initialize microscopic state with bus parameters
    val initialMicroState = MicroBusState(
      positionInLink = 0.0,
      velocity = state.microState.map(_.velocity).getOrElse(data.speedLimit * 0.7), // Buses slower
      acceleration = 0.0,
      currentLane = data.assignedLane,
      leaderVehicle = None,
      gapToLeader = data.linkLength,
      leaderVelocity = data.speedLimit,
      maxAcceleration = 1.2, // Bus-specific (slower than car)
      maxDeceleration = 3.5,
      minGap = 3.0, // Larger gap
      desiredVelocity = math.min(data.speedLimit, 11.11), // 40 km/h max for bus
      reactionTime = 1.5, // Longer reaction time
      vehicleLength = 12.0, // Bus length
      capacity = state.capacity,
      currentPassengers = state.people.size,
      nextBusStop = findNextBusStop(),
      busLaneRestricted = true, // Prefer bus lanes
      desiredLane = if (data.assignedLane == 2) Some(2) else None, // Prefer lane 2 if bus lane
      laneChangeProgress = 0.0,
      canChangeLane = false // Buses typically stay in lane
    )
    
    // Activate MICRO mode
    state.activateMicroMode(initialMicroState)
    state.status = Moving
    
    // Report micro enter
    report(
      data = Map(
        "event_type" -> "enter_micro_link",
        "bus_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "lane" -> data.assignedLane,
        "passengers" -> state.people.size,
        "capacity" -> state.capacity,
        "occupancy" -> state.occupancyPercentage,
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
      // Update microscopic state
      val updatedMicro = micro.copy(
        positionInLink = data.position,
        velocity = data.velocity,
        acceleration = data.acceleration,
        currentLane = data.currentLane,
        leaderVehicle = data.leaderVehicle,
        gapToLeader = data.gapToLeader,
        leaderVelocity = data.leaderVelocity,
        currentPassengers = state.people.size // Update passenger count
      )
      
      state.updateMicroState(updatedMicro)
      
      log.debug(s"Bus micro update: pos=${data.position}, vel=${data.velocity}, passengers=${state.people.size}")
      
      // Check for bus stop proximity
      checkBusStopAtPosition(data.position)
    }
  }
  
  /** Handle leaving MICRO link.
    */
  private def handleMicroLeaveLink(event: ActorInteractionEvent, data: MicroLeaveLinkData): Unit = {
    logDebug(s"Bus leaving MICRO link ${data.linkId}")
    
    val travelTime = linkEntryTick.map(entryTick => currentTick - entryTick).getOrElse(0L)
    
    state.distance += data.distanceTraveled
    
    // Report micro leave
    report(
      data = Map(
        "event_type" -> "leave_micro_link",
        "bus_id" -> getEntityId,
        "link_id" -> data.linkId,
        "mode" -> "MICRO",
        "passengers" -> state.people.size,
        "occupancy" -> state.occupancyPercentage,
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
    logDebug(s"Bus entering MESO link ${event.actorRefId}")
    
    val time = linkDensitySpeed(
      length = data.linkLength,
      capacity = data.linkCapacity,
      numberOfCars = data.linkNumberOfCars,
      freeSpeed = data.linkFreeSpeed,
      lanes = data.linkLanes
    )
    
    state.status = Moving
    
    // Report meso enter
    report(
      data = Map(
        "event_type" -> "enter_link",
        "bus_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "passengers" -> state.people.size,
        "capacity" -> state.capacity,
        "occupancy" -> state.occupancyPercentage,
        "travel_time" -> time,
        "tick" -> currentTick
      ),
      label = "enter_link"
    )
    
    onFinishSpontaneous(Some(currentTick + time.toLong))
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
        "bus_id" -> getEntityId,
        "link_id" -> event.actorRefId,
        "mode" -> "MESO",
        "passengers" -> state.people.size,
        "total_distance" -> state.distance,
        "tick" -> currentTick
      ),
      label = "leave_link"
    )
    
    onFinishSpontaneous(Some(currentTick + 1))
  }
  
  /** Handle passenger loading.
    */
  private def handleBusLoadPeople(event: ActorInteractionEvent, data: BusLoadPassengerData): Unit = {
    if (data.people.nonEmpty) {
      val nextTickTime = currentTick + loadPersonTime(
        numberOfPorts = state.numberOfPorts,
        numberOfPassengers = data.people.size
      )
      scheduleEvent(nextTickTime)
      
      for (person <- data.people) {
        state.people.put(person.id, person)
      }
      
      
      // Report passenger loading
      report(
        data = Map(
          "event_type" -> "bus_load_passengers",
          "bus_id" -> getEntityId,
          "passengers_loaded" -> data.people.size,
          "total_passengers" -> state.people.size,
          "occupancy" -> state.occupancyPercentage,
          "tick" -> currentTick
        ),
        label = "bus_load_passengers"
      )
      
    } else {
    }
  }
  
  /** Handle passenger unloading.
    */
  private def handleUnloadPassenger(event: ActorInteractionEvent, data: BusUnloadPassengerData): Unit = {
    state.countUnloadReceived += 1
    
    if (state.countUnloadReceived == state.people.size) {
      val nextTickTime = currentTick + BusUtil.unloadPersonTime(
        numberOfPassengers = state.people.size,
        numberOfPorts = state.numberOfPorts
      )
      scheduleEvent(nextTickTime)
      
      // Report passenger unloading
      report(
        data = Map(
          "event_type" -> "bus_unload_passengers",
          "bus_id" -> getEntityId,
          "tick" -> currentTick
        ),
        label = "bus_unload_passengers"
      )
      
    }
  }
  
  /** Handle signal state.
    */
  private def handleSignalState(event: ActorInteractionEvent, data: SignalStateData): Unit = {
    if (data.phase == Red) {
      state.status = WaitingSignal
      scheduleEvent(data.nextTick)
    } else {
    }
  }
  
  override def getNextPath: Option[(String, String)] = {
    state.bestRoute match {
      case Some(path) =>
        if (state.currentPathPosition < path.size) {
          val nextPath = path(state.currentPathPosition)
          state.currentPathPosition += 1
          Some(nextPath)
        } else {
          state.currentPathPosition = 0
          Some(path(state.currentPathPosition))
        }
      case None =>
        None
    }
  }
  
  /** Check if bus is at a bus stop (for MICRO mode).
    */
  private def checkBusStopAtPosition(position: Double): Unit = {
    state.microState.foreach { micro =>
      micro.nextBusStop.foreach { stopId =>
        // Check if close to bus stop (within 10m)
        // This is simplified - actual implementation would query bus stop positions
        log.debug(s"Bus at position $position, next stop: $stopId")
      }
    }
  }
  
  /** Find next bus stop on route.
    */
  private def findNextBusStop(): Option[String] = {
    // Simplified - would actually query route for next bus stop
    state.busStops.headOption.map(_._1)
  }
  
  /** Get current link length.
    */
  private def getCurrentLinkLength: Double = {
    currentLinkId.flatMap { linkId =>
      org.interscity.htc.model.mobility.util.CityMapUtil.edgeLabelsById.get(linkId).map(_.length)
    }.getOrElse(1000.0)
  }
}

/** HybridBus companion object.
  */
object HybridBus {
  def apply(properties: Properties): HybridBus = {
    new HybridBus(properties)
  }
}
