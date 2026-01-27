package org.interscity.htc
package model.hybrid.entity.state

import core.entity.state.BaseState
import core.types.Tick

/** Person state representing a person agent in the simulation.
  * 
  * Person-centric model where the Person actor persists throughout the day
  * and manages their daily schedule, making mode choices and activating
  * private vehicles (Car, Bicycle, Motorcycle) as needed.
  * 
  * @param dailySchedule List of activities scheduled for the day
  * @param currentActivityIndex Current activity being performed
  * @param ownedVehicles Map of vehicle IDs owned by this person (mode -> vehicleId)
  * @param currentTripVehicleId Vehicle currently being used (if any)
  * @param currentTripStartTick When current trip started
  * @param totalDistanceTraveled Total distance traveled today (meters)
  * @param completedTrips Number of trips completed today
  */
case class PersonState(
  dailySchedule: List[Activity],
  currentActivityIndex: Int = 0,
  ownedVehicles: Map[String, String] = Map.empty, // mode -> vehicleId
  currentTripVehicleId: Option[String] = None,
  currentTripStartTick: Option[Tick] = None,
  totalDistanceTraveled: Double = 0.0,
  completedTrips: Int = 0
) extends BaseState {
  
  /** Get current activity.
    */
  def currentActivity: Option[Activity] = {
    if (currentActivityIndex >= 0 && currentActivityIndex < dailySchedule.length) {
      Some(dailySchedule(currentActivityIndex))
    } else {
      None
    }
  }
  
  /** Get next activity.
    */
  def nextActivity: Option[Activity] = {
    val nextIndex = currentActivityIndex + 1
    if (nextIndex < dailySchedule.length) {
      Some(dailySchedule(nextIndex))
    } else {
      None
    }
  }
  
  /** Advance to next activity.
    */
  def advanceActivity(): PersonState = {
    copy(currentActivityIndex = currentActivityIndex + 1)
  }
  
  /** Check if person has completed all activities.
    */
  def isScheduleComplete: Boolean = {
    currentActivityIndex >= dailySchedule.length
  }
  
  /** Start a trip.
    */
  def startTrip(vehicleId: String, tick: Tick): PersonState = {
    copy(
      currentTripVehicleId = Some(vehicleId),
      currentTripStartTick = Some(tick)
    )
  }
  
  /** Complete a trip.
    */
  def completeTrip(distanceTraveled: Double): PersonState = {
    copy(
      currentTripVehicleId = None,
      currentTripStartTick = None,
      totalDistanceTraveled = totalDistanceTraveled + distanceTraveled,
      completedTrips = completedTrips + 1
    )
  }
}

/** Activity in a person's daily schedule.
  * 
  * @param sequence Order in the schedule (0-based)
  * @param activityType Type of activity ("Home", "Work", "School", "Shopping", etc.)
  * @param nodeId Location node ID
  * @param endTime When this activity ends (format: "HH:MM" or tick number)
  * @param arrivalLogistics How to arrive at this location (None for first activity)
  */
case class Activity(
  sequence: Int,
  activityType: String,
  nodeId: String,
  endTime: String, // Could be "08:00" or tick number as string
  arrivalLogistics: Option[ArrivalLogistics] = None
)

/** Logistics for arriving at an activity location.
  * 
  * @param mode Transportation mode ("car", "bicycle", "motorcycle", "walk", "transit")
  * @param vehicleId ID of the vehicle to use (for private modes)
  * @param driverAttributes Attributes affecting driving behavior
  */
case class ArrivalLogistics(
  mode: String, // "car", "bicycle", "motorcycle", "walk", "transit"
  vehicleId: Option[String] = None, // Required for private vehicles
  driverAttributes: DriverAttributes = DriverAttributes()
)

/** Driver attributes affecting vehicle behavior.
  * 
  * These parameters override default vehicle physics when a person activates a vehicle.
  * 
  * @param aggressiveness How aggressive the driver is [0.0 - 1.0]
  * @param maxSpeedFactor Multiplier for speed limit adherence [0.5 - 1.5]
  * @param reactionTime Reaction time in seconds [0.5 - 2.0]
  * @param minGapFactor Multiplier for minimum safe gap [0.5 - 2.0]
  */
case class DriverAttributes(
  aggressiveness: Double = 0.5,
  maxSpeedFactor: Double = 1.0,
  reactionTime: Double = 1.0,
  minGapFactor: Double = 1.0
) {
  /** Validate attributes are within acceptable ranges.
    */
  def validate(): DriverAttributes = {
    copy(
      aggressiveness = math.max(0.0, math.min(1.0, aggressiveness)),
      maxSpeedFactor = math.max(0.5, math.min(1.5, maxSpeedFactor)),
      reactionTime = math.max(0.5, math.min(2.0, reactionTime)),
      minGapFactor = math.max(0.5, math.min(2.0, minGapFactor))
    )
  }
}
