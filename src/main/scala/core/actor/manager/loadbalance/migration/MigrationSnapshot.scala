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
  currentPTVehicleRefClassType: String = ""
)
