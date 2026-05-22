package org.interscity.htc
package model.hybrid.entity.event.data.person

import core.entity.event.data.BaseEventData
import core.types.Tick
import model.hybrid.entity.state.DriverAttributes

/** Message from Person to Vehicle to start a trip.
  *
  * This activates a passive vehicle asset and configures it with the person's driving attributes.
  *
  * @param personId
  *   ID of the person starting the trip
  * @param origin
  *   Starting node ID
  * @param destination
  *   Destination node ID
  * @param driverAttributes
  *   Person's driving characteristics
  * @param startTick
  *   Tick when trip starts
  */
case class StartTripData(
  personId: String,
  origin: String,
  destination: String,
  driverAttributes: DriverAttributes,
  startTick: Tick,
  precomputedRoute: Option[List[(String, String)]] = None  // route pre-computed by ModeChoiceStrategy; avoids double A*
) extends BaseEventData

/** Message from Vehicle to Person when trip is completed.
  *
  * Reports trip statistics back to the Person actor.
  *
  * @param vehicleId
  *   ID of the vehicle that completed the trip
  * @param personId
  *   ID of the person
  * @param distanceTraveled
  *   Distance traveled in meters
  * @param travelTime
  *   Time taken in ticks
  * @param finalNode
  *   Node ID where trip ended
  * @param completionTick
  *   Tick when trip completed
  * @param completionReason
  *   Why the trip ended ("reached_destination", "error", etc.)
  */
case class TripCompletedData(
  vehicleId: String,
  personId: String,
  distanceTraveled: Double,
  travelTime: Long,
  finalNode: String,
  completionTick: Tick,
  completionReason: String
) extends BaseEventData

/** Message from Person to Vehicle to park (deactivate).
  *
  * Optional message if Person needs to explicitly park a vehicle before it completes its trip
  * naturally.
  *
  * @param personId
  *   ID of the person
  * @param parkingNodeId
  *   Node where vehicle should park
  */
case class ParkVehicleData(
  personId: String,
  parkingNodeId: String
) extends BaseEventData

/** Internal Person state for mode choice decision.
  *
  * Not a message, but a data structure to represent mode choice logic.
  *
  * @param availableModes
  *   Modes available to person
  * @param chosenMode
  *   Mode selected by person
  * @param decisionFactors
  *   Factors influencing decision (for analysis)
  */
case class ModeChoiceDecision(
  availableModes: List[String],
  chosenMode: String,
  decisionFactors: Map[String, Double] = Map.empty
) extends BaseEventData

/** Message from Person to owned Vehicle when the person's daily schedule is complete.
  *
  * Signals the vehicle to self-destruct and free memory:
  *   - If vehicle is Parked → selfDestruct() immediately.
  *   - If vehicle is active (mid-trip) → flag to destruct on next deactivation.
  *
  * @param personId
  *   ID of the person whose schedule is complete
  */
case class PersonScheduleCompleteData(personId: String) extends BaseEventData
