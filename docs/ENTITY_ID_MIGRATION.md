# Entity ID Migration: String → Long

Status: DONE (full-stack, Long end-to-end — no String entity ids, no `.toString`/`.toLong`
in business logic outside the sanctioned Pekko-sharding boundary). `sbt compile` and `sbt test`
(280/280) both green.

## Conventions (final design — no String, no toString)

- **Entity ids are `Long` everywhere in the data model**: state classes, routing/graph layer,
  event-data classes, `sendMessageTo`/`sendMessageFn`, and the protobuf wire types.
- **Protobuf ids are `int64`**: `actor.proto` `Identify.id`/`Dependency.id` and
  `execution.proto` `RegisterActorEvent.actorId` are `int64` (regenerated to Scala `Long`).
- **Routing/graph layer is `Long`-keyed**: `CityMapUtil.nodesById/edgeLabelsById: Map[Long, …]`,
  `Graph` loaders, `CompactGraph` indexes, `GPSUtil.calcRoute*` (`Long` ids, `(Long, Long)` routes,
  `(Long, Long, Boolean)` cache keys).
- **The only unavoidable String boundary is Pekko Cluster Sharding** (`ShardRegion.ExtractEntityId`
  returns `(String, Any)`; `BaseActor.entityId`/`getEntityId` remain the Pekko-facing String id, and
  the `sendMessageTo`/`Identify` wire boundary does `.toLong`/`.toString` in the message machinery
  only — not in business logic).

## Change surface

### DONE
- State classes → `Long` (origin/destination/currentNode/from/to/nodeId/links/route tuples/model types).
- Routing layer → `Long` (CityMapUtil, Graph, CompactGraph, GPSUtil, DynamicLinkCost).
- Event data → `Long` (StartTripData, TripCompletedData, ParkVehicleData, link/vehicle/subway/micro data).
- `sendMessageTo`/`sendMessageFn`/`sendVehicleMessage` → `Long`; `MessageId`/`SentMessage` receiver → `Long`.
- Protobuf `Identify`/`Dependency`/`RegisterActorEvent` → `int64`.
- Time Manager maps → `Long`-keyed (`registeredActors`, `registeredIdentities`, `dispatchGeneration`,
  `highestProcessedTick`, `pendingDestructAcks`, `identitiesToDestruct`).
- Reporters, handler function fields, signal handlers, `internStateStrings`.

- Micro simulation layer (`LinkMicroTimeManager`, `DefaultMicroSimulationStrategy`,
  `LinkVehicleFlowHandler`, `LinkMicroSimulationHandler`, micro handlers) → `Long`.
  Signal-blocker sentinel replaced with a named constant (`DefaultMicroSimulationStrategy.SignalBlockerId
  = -1L`) instead of the old `"__signal__"` string hack.
- `Bus`/`Subway`/`BusStation`/`BusStopHandler`/`BusStationBusCreator`/`BusStationRouteCalculator`
  navigation → `Long`; dead `suffixNumber` String-parsing helper removed from
  `BusStationRouteCalculator` now that `busStops` is natively `Long`-keyed.
- `PrivateVehicle` migration snapshot (`ownerPersonRefId`), `Person`, `Node`, `Link`, `SubwayStation`,
  `CompactGraph` `edgeLabelIds`, `NearestStopUtilityEngine`/`TravelTimeChoiceResolution`/`PrivateVehicleCandidates`
  → `Long`.
- `ActorInteractionEvent.actorRefId` → `Long` (was the last String receive-boundary field; converted
  once at Pekko entityId construction and once on the wire — `communication.proto`'s
  `ActorInteraction.actorRefId` is now `int64`, and the obsolete `actorRefIdPrefixStripped` field
  was removed since a raw int64 has no string prefix to strip).
- "Unset" sentinel convention: `0L` (never a real generated entity id — verified against
  `IdUtil`/`ActorSimulation.id` generation), used consistently for `ownerPersonRefId`,
  `currentPTVehicleRef`, `vehicleCurrentLinkId`, `tripOrigin`/`tripDestination`, `currentStopNode`.
- Exceptions kept `String` on purpose (not ids): `NodeState.subwayStations`/`busStops` (keyed by
  route/line label), `TransitRoute.RouteStop.stopId` (external GTFS-style label), the legacy
  `model.mobility.*` package (untouched per CLAUDE.md), and `MessageId.senderId` (Time Warp rollback
  bookkeeping, out of scope for this migration).

### Generators (Python)
- **sao-paulo DONE** (ScenarioIdRegistry). toulouse/interscsimulator/sumo NOT DONE.

## Validation
- `sbt compile` ✓
- `sbt test` ✓ (280/280 tests, 57 suites, 0 failures)
- `python3 -m py_compile tools/sao-paulo/generate_hybrid_input.py` ✓
