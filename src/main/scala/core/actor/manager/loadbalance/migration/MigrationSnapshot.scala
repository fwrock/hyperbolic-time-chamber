package org.interscity.htc
package core.actor.manager.loadbalance.migration

/** Encapsulates all actor data that must survive shard migration.
  *
  * This case class is serializable by Jackson (all primitive types and simple maps) without
  * type-erasure ambiguity. The domain state is kept as a pre-serialized JSON string so that
  * deserialization can use the correct concrete class via `stateClassName`.
  *
  * Produced by [[core.actor.BaseActor.saveMigrationState]] and consumed by
  * [[core.actor.BaseActor.restoreMigrationState]].
  *
  * @param stateJson
  *   The actor's typed state (e.g., CarState) serialized as a JSON string
  * @param stateClassName
  *   Fully qualified class name of the state type, used for deserialization on the target node
  * @param currentTick
  *   The simulation tick at the time of migration
  * @param startTick
  *   The entity's start tick
  * @param lamportClock
  *   The Lamport clock value for causal ordering
  * @param currentTimeManagerType
  *   The time manager type the entity was using (e.g., "discrete-event")
  * @param dependencyIds
  *   Map of dependency key → entity ID (e.g., "from_node" → "htcaid:node;123")
  * @param dependencyTypes
  *   Map of dependency key → actor class type (e.g., "from_node" → "mobility.actor.Node")
  * @param dependencyResourceIds
  *   Map of dependency key → resource ID
  * @param dependencyActorTypes
  *   Map of dependency key → actor type (e.g., "LoadBalancedDistributed")
  * @param ownerPersonRefId
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: the owning Person's entity id,
  *   empty when the vehicle has no pending reply obligation (never activated / already parked).
  * @param ownerPersonRefClassType
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: the owning Person's actor
  *   class type, paired with [[ownerPersonRefId]].
  * @param personCentric
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: whether this vehicle has ever
  *   been activated by a Person (governs selfDestruct-on-finish vs. return-to-Parked).
  * @param tripOrigin
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: current trip origin override,
  *   empty when not mid-trip.
  * @param tripDestination
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: current trip destination
  *   override, empty when not mid-trip.
  * @param tripStartTick
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: tick the current trip started,
  *   `Long.MinValue` when not mid-trip.
  * @param tripStartDistance
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: odometer distance at trip
  *   start, used to compute distance traveled on completion.
  * @param destroyAfterNextPark
  *   [[org.interscity.htc.model.hybrid.actor.PrivateVehicle]] only: set when the owning Person
  *   completed its schedule while this vehicle was still mid-trip; the vehicle must self-destruct
  *   on its next park instead of waiting for another StartTrip.
  * @param currentPTVehicleRefId
  *   [[org.interscity.htc.model.hybrid.actor.Person]] only: the boarded Bus/Subway's entity id,
  *   empty when not currently boarded on a PT vehicle.
  * @param currentPTVehicleRefClassType
  *   [[org.interscity.htc.model.hybrid.actor.Person]] only: the boarded Bus/Subway's actor class
  *   type, paired with [[currentPTVehicleRefId]].
  * @param vehicleCurrentLinkId
  *   [[org.interscity.htc.model.hybrid.actor.Car]]/`Motorcycle`/`Bicycle`/`Bus` only: the link this
  *   vehicle currently occupies, empty when not on a link (e.g. still routing). Found uncaptured by
  *   `docs/TIME_WARP_DESIGN.md`'s checkpoint-completeness audit (2026-08-07) — without this, a Time
  *   Warp rollback restores `state.status` correctly but leaves this actor-local field at whatever
  *   value live execution left it at, desynchronizing replay from the original run.
  * @param vehicleCurrentLinkLength
  *   Same audit, `Car` only (`Motorcycle`/`Bicycle`/`Bus` don't track this separately): the occupied
  *   link's length, paired with [[vehicleCurrentLinkId]].
  * @param vehicleLinkEntryTick
  *   Same audit: the tick this vehicle entered [[vehicleCurrentLinkId]], `Long.MinValue` when not
  *   on a link. Feeds travel-time/emission math on leave.
  * @param vehicleMesoExitTick
  *   Same audit: the tick a MESO-mode vehicle is scheduled to exit its current link,
  *   `Long.MinValue` when not mid-MESO-traversal. Gates `actSpontaneous`'s `Moving` branch — see
  *   `Car.scala`'s `mesoExitTick` match.
  * @param vehicleSignalWaitUntilTick
  *   Same audit: the tick a vehicle in `WaitingSignal` is scheduled to re-check, `Long.MinValue`
  *   when not waiting on a signal.
  * @param vehicleSignalWaitNeedsReverify
  *   Same audit: whether the vehicle's signal wait must re-request signal state (vs. proceeding
  *   directly) once its wait tick arrives.
  * @param expectedUnloadResponses
  *   [[org.interscity.htc.model.hybrid.actor.Bus]]/[[org.interscity.htc.model.hybrid.actor.Subway]]
  *   only: how many onboard-passenger unload responses this vehicle is still waiting for before a
  *   stop cycle completes (a reply-count barrier), `0` when no unload round is in progress. Same
  *   audit as the `vehicle*` fields above — losing this on a Time Warp rollback desyncs the barrier
  *   from `state.countUnloadReceived`, which IS captured (it's a `BusState`/`SubwayState` field).
  * @param currentStopNode
  *   [[org.interscity.htc.model.hybrid.actor.Bus]] only: the node ID of the stop this bus is
  *   currently at, saved before `leavingLink` clears the current path; empty when not at a stop.
  * @param linkVehicleEntryTick
  *   [[org.interscity.htc.model.hybrid.actor.Link]] only: tick each currently-registered vehicle
  *   (keyed by entity id) entered this link, feeding travel-time math sent to the vehicle on leave
  *   (`MicroLeaveLinkData`'s `avgSpeed`, `LinkVehicleFlowHandler`'s `travelTicks`). Same audit as
  *   the `vehicle*` fields above — uncaptured, this desyncs from `LinkState.registered` (which IS
  *   captured) after a Time Warp rollback, computing a different travel time for the same logical
  *   leave event than the original live run did.
  * @param linkVehicleWaitingSeconds
  *   [[org.interscity.htc.model.hybrid.actor.Link]] only: accumulated waiting time per
  *   currently-registered vehicle (keyed by entity id), same audit and same reasoning as
  *   [[linkVehicleEntryTick]].
  */
case class MigrationSnapshot(
  stateJson: String,
  stateClassName: String,
  entityId: String = "",
  currentTick: Long = 0L,
  startTick: Long = Long.MinValue,
  lamportClock: Long = 0L,
  currentTimeManagerType: String = "discrete-event",
  dependencyIds: Map[String, String] = Map.empty,
  dependencyTypes: Map[String, String] = Map.empty,
  dependencyResourceIds: Map[String, String] = Map.empty,
  dependencyActorTypes: Map[String, String] = Map.empty,
  ownerPersonRefId: String = "",
  ownerPersonRefClassType: String = "",
  personCentric: Boolean = false,
  tripOrigin: String = "",
  tripDestination: String = "",
  tripStartTick: Long = Long.MinValue,
  tripStartDistance: Double = 0.0,
  destroyAfterNextPark: Boolean = false,
  currentPTVehicleRefId: String = "",
  currentPTVehicleRefClassType: String = "",
  vehicleCurrentLinkId: String = "",
  vehicleCurrentLinkLength: Double = 0.0,
  vehicleLinkEntryTick: Long = Long.MinValue,
  vehicleMesoExitTick: Long = Long.MinValue,
  vehicleSignalWaitUntilTick: Long = Long.MinValue,
  vehicleSignalWaitNeedsReverify: Boolean = false,
  expectedUnloadResponses: Int = 0,
  currentStopNode: String = "",
  linkVehicleEntryTick: Map[String, Long] = Map.empty,
  linkVehicleWaitingSeconds: Map[String, Double] = Map.empty
)
