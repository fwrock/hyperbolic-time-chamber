# Actor Reference Pattern for Distributed Message Passing

## Problem Statement

In a distributed actor system with sharding, actors need **both** the target actor's ID **and** shard ID (classType) to send messages correctly. Using only string IDs is insufficient for proper message routing.

## Solution: Use `Identify` for Actor References

The `Identify` protobuf type contains:
- `id`: The unique actor ID (e.g., `"htcaid:car;car_001"`)
- `classType`: The shard identifier (e.g., `"hybrid.actor.Car"`)

This mirrors the pattern used in the mobility model (e.g., `CityMapUtil.edgeLabelsById`).

## Implementation

### PersonState

**Before (Incorrect):**
```scala
case class PersonState(
  ownedVehicles: Map[String, String],  // mode -> vehicleId (MISSING shard info!)
  // ...
)
```

**After (Correct):**
```scala
import org.htc.protobuf.core.entity.actor.Identify

case class PersonState(
  ownedVehicles: Map[String, Identify],  // mode -> Identify(id, classType)
  // ...
)
```

### ArrivalLogistics

**Before (Incorrect):**
```scala
case class ArrivalLogistics(
  mode: String,
  vehicleId: Option[String],  // MISSING shard info!
  driverAttributes: DriverAttributes
)
```

**After (Correct):**
```scala
case class ArrivalLogistics(
  mode: String,
  vehicle: Option[Identify],  // Complete reference with id + classType
  driverAttributes: DriverAttributes
)
```

### Person Actor Message Sending

**Before (Incorrect):**
```scala
sendMessageTo(
  entityId = vehicleId,           // String only
  shardId = getShardId,           // WRONG! Uses Person's shard, not vehicle's
  data = startTripData,
  eventType = "StartTrip",
  actorType = LoadBalancedDistributed
)
```

**After (Correct):**
```scala
sendMessageTo(
  entityId = vehicleRef.id,       // Extract ID from Identify
  shardId = vehicleRef.classType, // Use vehicle's actual shard
  data = startTripData,
  eventType = "StartTrip",
  actorType = LoadBalancedDistributed
)
```

## JSON Configuration Format

### Person Actor JSON

**Correct Format:**
```json
{
  "id": "htcaid:person;alice",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "content": {
      "dailySchedule": [
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "htcaid:node;work",
          "endTime": "61200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;alice_car",
              "classType": "hybrid.actor.Car"
            }
          }
        }
      ],
      "ownedVehicles": {
        "car": {
          "id": "htcaid:car;alice_car",
          "classType": "hybrid.actor.Car"
        },
        "bicycle": {
          "id": "htcaid:bicycle;alice_bike",
          "classType": "hybrid.actor.Bicycle"
        }
      }
    }
  },
  "dependencies": {
    "car": {
      "id": "htcaid:car;alice_car",
      "classType": "hybrid.actor.Car"
    },
    "bicycle": {
      "id": "htcaid:bicycle;alice_bike",
      "classType": "hybrid.actor.Bicycle"
    }
  }
}
```

## Comparison with Mobility Model

The mobility model handles this correctly:

```scala
// In Movable actor
CityMapUtil.edgeLabelsById.get(linkEdgeGraphId) match {
  case Some(edgeLabel) =>  // edgeLabel is an Identify
    sendMessageTo(
      entityId = edgeLabel.id,        // ✓ Correct ID
      shardId = edgeLabel.classType,  // ✓ Correct shard
      data = enterLinkData,
      eventType = EventTypeEnum.EnterLink.toString,
      actorType = LoadBalancedDistributed
    )
}
```

The person-centric model now follows the same pattern for vehicle references.

## Benefits

1. **Correct Message Routing**: Messages reach the correct shard
2. **Horizontal Scalability**: Supports distributed actor deployment
3. **Consistency**: Same pattern across mobility and person-centric models
4. **Type Safety**: Compiler enforces complete references
5. **Debugging**: Clear distinction between actor identity and shard location

## Migration Checklist

When adding new actor-to-actor communication:

- [ ] Store dependencies as `Identify` objects, not strings
- [ ] Use `vehicle.id` and `vehicle.classType` when sending messages
- [ ] Update JSON configuration to include both `id` and `classType`
- [ ] Add dependencies section in actor JSON definitions
- [ ] Test cross-shard message delivery

## Related Patterns

- **CityMapUtil**: Stores link/node references as `Identify`
- **Dependencies**: Actor JSON includes `dependencies` with `Identify` objects
- **Actor Registration**: System tracks actors with full `Identify` information

## See Also

- [PERSON_CENTRIC_MODEL.md](PERSON_CENTRIC_MODEL.md) - Updated examples
- [ARCHITECTURE.md](ARCHITECTURE.md) - Actor communication patterns
- [API_REFERENCE.md](API_REFERENCE.md) - Message passing APIs
