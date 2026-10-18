package org.interscity.htc
package model.hybrid.support.person

import core.types.Tick
import model.hybrid.entity.event.data.person.StartTripData
import model.hybrid.entity.state.PersonState
import model.hybrid.entity.state.plan.{ ConcreteMode, PrivateVehicleLeg }
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

/** Handles private vehicle legs (car, bicycle, motorcycle) for person actors.
  *
  * Responsibilities:
  *   - Initiate private vehicle legs
  *   - Send StartTrip messages to vehicles
  *   - Track vehicle locations after legs complete
  *
  * Unlike the pre-redesign handler, [[PrivateVehicleLeg.vehicle]] is a non-optional [[org.htc.protobuf.core.entity.actor.Identify]]
  * — there is no "vehicle missing" failure mode left to handle here; a `PrivateVehicleLeg` cannot
  * exist without one (enforced by the type, not a runtime check).
  *
  * @param personId Person actor ID
  * @param sendMessageFn Function to send messages to other actors
  * @param logDebug Debug logging function
  * @param registerAwaitedReplyFn Registers this Person's outstanding `StartTrip` -> `TripCompleted`
  *   exchange for the leg's vehicle, so `Person.actInteractWith`'s `TripCompletedData` guard can
  *   tell a genuine reply apart from a rollback-orphaned one to an earlier, since-retracted
  *   `StartTrip` (see `TripCompletedData.requestSeq`'s doc). No-op outside Time Warp.
  */
class PersonPrivateVehicleTripHandler(
  personId: String,
  sendMessageFn: (Long, String, Any, String, Any) => Unit,
  logDebug: String => Unit,
  registerAwaitedReplyFn: (String, String) => Unit = (_, _) => ()
) {

  /** Correlation key for the one outstanding `StartTrip` -> `TripCompletedData` exchange with a
    * given vehicle. Keyed on `vehicleId` (not e.g. `personId`, which is constant per Person) so a
    * trip started with a *different* owned vehicle never supersedes/consumes this one, mirroring
    * `CarSignalHandler.linkAccessAwaitKey`.
    */
  def vehicleTripAwaitKey(vehicleId: Long): String = s"vehicle-trip:$vehicleId"

  /** Sends StartTrip to the leg's vehicle.
    *
    * `registerAwaitedReplyFn` is called after `sendMessageFn`, not before -- see
    * `Movable.enterLink`'s identical ordering, whose doc explains why registering before the send
    * would capture the *previous* send's sequence number instead of this one's.
    *
    * @param origin Origin node ID (person's current physical position)
    * @param destination Destination node ID (the next Activity's node, or origin if none found)
    * @param leg Private vehicle leg with vehicle reference and driver attributes
    * @param currentTick Current simulation tick
    */
  def initiatePrivateVehicleTrip(
    origin: Long,
    destination: Long,
    leg: PrivateVehicleLeg,
    currentTick: Tick
  ): Unit = {
    val startTripData = StartTripData(
      personId = personId.toLong,
      origin = origin,
      destination = destination,
      driverAttributes = leg.driverAttributes,
      startTick = currentTick
    )

    sendMessageFn(
      leg.vehicle.id,
      leg.vehicle.classType,
      startTripData,
      "StartTrip",
      LoadBalancedDistributed
    )
    registerAwaitedReplyFn(vehicleTripAwaitKey(leg.vehicle.id), "TripCompletedData")

    logDebug(
      s"$personId sent StartTrip to ${leg.vehicle.id} (mode=${leg.mode}, $origin -> $destination)"
    )
  }

  /** Key private vehicles are tracked under in [[PersonState.ownedVehicles]]/[[PersonState.vehicleCurrentNode]]. */
  def vehicleModeKey(mode: ConcreteMode): String = mode match {
    case ConcreteMode.Car        => "car"
    case ConcreteMode.Bicycle    => "bicycle"
    case ConcreteMode.Motorcycle => "motorcycle"
    case other                   => other.toString.toLowerCase
  }

  /** Update vehicle location after a private vehicle leg completes.
    *
    * Private vehicles travel with the person, so after a leg they're located at the destination.
    * This enables the next mode decision to exclude vehicles left at other locations.
    *
    * @param mode Concrete private-vehicle mode
    * @param destinationNodeId Destination node ID
    * @param state Current person state
    * @return Updated PersonState with vehicle location tracked
    */
  def updateVehicleLocation(
    mode: ConcreteMode,
    destinationNodeId: Long,
    state: PersonState
  ): PersonState =
    state.copy(vehicleCurrentNode = state.vehicleCurrentNode + (vehicleModeKey(mode) -> destinationNodeId))
}
