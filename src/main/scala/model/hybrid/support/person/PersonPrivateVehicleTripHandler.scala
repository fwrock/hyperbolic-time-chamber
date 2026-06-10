package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.state.{ArrivalLogistics, PersonState}
import model.hybrid.entity.event.data.person.StartTripData
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

/** Handles private vehicle trips (car, bicycle, motorcycle) for person actors.
  *
  * Responsibilities:
  *   - Initiate private vehicle trips
  *   - Send StartTrip messages to vehicles
  *   - Track vehicle locations after trips
  *   - Handle trip completion from vehicles
  *
  * @param personId Person actor ID
  * @param sendMessageFn Function to send messages to other actors
  * @param logDebug Debug logging function
  * @param logError Error logging function
  */
class PersonPrivateVehicleTripHandler(
  personId: String,
  sendMessageFn: (String, String, Any, String, Any) => Unit,
  logDebug: String => Unit,
  logError: String => Unit
) {

  /** Initiate private vehicle trip.
    *
    * Sends StartTrip message to the specified vehicle actor.
    * Returns true if message sent successfully, false if vehicle ref missing.
    *
    * @param origin Origin node ID
    * @param destination Destination node ID
    * @param logistics Arrival logistics with vehicle reference
    * @param currentTick Current simulation tick
    * @return true if trip initiated successfully
    */
  def initiatePrivateVehicleTrip(
    origin: String,
    destination: String,
    logistics: ArrivalLogistics,
    currentTick: Tick
  ): Boolean =
    logistics.vehicle match {
      case Some(vehicleRef) =>
        val startTripData = StartTripData(
          personId         = personId,
          origin           = origin,
          destination      = destination,
          driverAttributes = logistics.driverAttributes,
          startTick        = currentTick,
          precomputedRoute = logistics.precomputedRoute
        )

        sendMessageFn(
          vehicleRef.id,
          vehicleRef.classType,
          startTripData,
          "StartTrip",
          LoadBalancedDistributed
        )

        logDebug(
          s"$personId sent StartTrip to ${vehicleRef.id} " +
            s"(mode=${logistics.mode}, $origin → $destination)"
        )
        true

      case None =>
        logError(s"$personId no vehicle specified for mode ${logistics.mode}")
        false
    }

  /** Update vehicle location after trip completion.
    *
    * Private vehicles travel with the person, so after a trip they're located at the destination.
    * This enables mode choice to exclude vehicles left at other locations.
    *
    * @param mode Transport mode (car, bicycle, motorcycle)
    * @param destinationNodeId Destination node ID
    * @param state Current person state
    * @return Updated PersonState with vehicle location tracked
    */
  def updateVehicleLocation(
    mode: String,
    destinationNodeId: String,
    state: PersonState
  ): PersonState = {
    val privateVehicleModes = Set("car", "bicycle", "motorcycle")
    if (privateVehicleModes.contains(mode) && destinationNodeId.nonEmpty) {
      state.copy(
        vehicleCurrentNode = state.vehicleCurrentNode + (mode -> destinationNodeId)
      )
    } else {
      state
    }
  }
}
