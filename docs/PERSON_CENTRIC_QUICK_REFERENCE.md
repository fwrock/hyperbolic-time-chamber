# Person-Centric Quick Reference

## Core Concept

**Person** makes decisions → **Vehicle** performs physics

## Key Files

| File | Purpose |
|------|---------|
| `Person.scala` | Schedule manager, mode choice, trip orchestration |
| `PrivateVehicle.scala` | Common trait for passive vehicles |
| `PersonState.scala` | Person state with daily schedule |
| `PersonEventData.scala` | StartTrip, TripCompleted messages |

## State Flow

```
Person: At Activity → Time to leave → Mode choice → StartTrip
Vehicle: Parked → Activated → Traveling → Completed → Parked
```

## Quick Setup

### 1. Define Person
```json
{
  "id": "htcaid:person;alice",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "content": {
      "dailySchedule": [
        {"sequence": 0, "activityType": "Home", "nodeId": "node_a", "endTime": "0"},
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "node_b",
          "endTime": "28800",
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
        }
      }
    }
  }
}
```

### 2. Define Vehicle (Parked)
```json
{
  "id": "htcaid:car;alice_car",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "content": {
      "status": "Parked",
      "origin": null,
      "destination": null
    }
  }
}
```

## Driver Attributes

```json
{
  "driverAttributes": {
    "aggressiveness": 0.7,     // 0=calm, 1=very aggressive
    "maxSpeedFactor": 1.1,     // 1.1 = 10% over speed limit
    "reactionTime": 0.9,       // seconds (lower = faster)
    "minGapFactor": 0.8        // 0.8 = 20% smaller gaps
  }
}
```

### Effects
- **High aggressiveness**: Faster acceleration, smaller gaps, lane filtering (motorcycles)
- **High maxSpeedFactor**: Exceeds speed limits
- **Low reactionTime**: Responds faster to leader changes
- **Low minGapFactor**: Follows closer

## Common Patterns

### Pattern 1: Simple Commute
```json
[
  {"activityType": "Home", "endTime": "28800"},
  {"activityType": "Work", "endTime": "61200", "arrivalLogistics": {"mode": "car"}},
  {"activityType": "Home", "endTime": "79200", "arrivalLogistics": {"mode": "car"}}
]
```

### Pattern 2: Multi-Modal
```json
[
  {"activityType": "Home", "endTime": "28800"},
  {"activityType": "Work", "arrivalLogistics": {"mode": "car"}},
  {"activityType": "Gym", "arrivalLogistics": {"mode": "bicycle"}},
  {"activityType": "Home", "arrivalLogistics": {"mode": "bicycle"}}
]
```

### Pattern 3: Aggressive Rider
```json
{
  "mode": "motorcycle",
  "driverAttributes": {
    "aggressiveness": 0.9,
    "maxSpeedFactor": 1.3,
    "reactionTime": 0.7,
    "minGapFactor": 0.6
  }
}
```

## Time Format

### Tick-Based (Recommended)
```json
{"endTime": "28800"}  // Tick 28800 = 8:00 AM (28800 seconds)
```

### Conversions
- 08:00 = 8 × 3600 = 28800 ticks
- 17:00 = 17 × 3600 = 61200 ticks
- 22:00 = 22 × 3600 = 79200 ticks

## Messages

### StartTrip (Person → Vehicle)
```scala
StartTripData(
  personId = "person_1",
  origin = "node_a",
  destination = "node_b",
  driverAttributes = DriverAttributes(0.7, 1.1, 0.9, 0.8),
  startTick = 28800
)
```

### TripCompleted (Vehicle → Person)
```scala
TripCompletedData(
  vehicleId = "car_1",
  personId = "person_1",
  distanceTraveled = 5000.0,
  travelTime = 600,
  finalNode = "node_b",
  completionTick = 29400,
  completionReason = "reached_destination"
)
```

## Status Values

| Status | Meaning |
|--------|---------|
| `Parked` | Vehicle is passive (not in TimeManager) |
| `Start` | Vehicle activated, about to request route |
| `Ready` | Route obtained, ready to enter link |
| `Moving` | Traversing link (MESO or MICRO) |
| `Finished` | Trip complete (vehicle parks) |

## Vehicle Types

| Type | Characteristics |
|------|----------------|
| **Car** | Standard vehicle, length=4.5m, max_accel=2.6 m/s² |
| **Bicycle** | Slow (20 km/h), length=2m, prefers bike lanes |
| **Motorcycle** | Fast, length=2.5m, can filter lanes, aggressive |

## Testing Checklist

- [ ] Person defined with valid schedule
- [ ] Vehicle exists and is `Parked`
- [ ] Person owns vehicle (`ownedVehicles`)
- [ ] Activity logistics specify correct `vehicleId`
- [ ] Node IDs exist in network
- [ ] End times are increasing
- [ ] Driver attributes in valid ranges

## Troubleshooting

### Vehicle doesn't move
- Check vehicle status is `Parked` initially
- Verify Person's `ownedVehicles` includes vehicle
- Confirm `arrivalLogistics.vehicleId` matches

### Person doesn't start trip
- Check `endTime` format (ticks as string)
- Verify `currentActivityIndex` is 0
- Ensure next activity has `arrivalLogistics`

### Trip never completes
- Check route exists (origin → destination)
- Verify network connectivity
- Look for Link or TimeManager errors

## Example Scenario

See [person_centric_scenario.json](examples/person_centric_scenario.json) for complete working example with:
- 4 persons (Alice, Bob, Charlie, Diana)
- Car commuter, cyclist, motorcycle rider, multi-modal traveler
- Various driving behaviors (aggressive, conservative)

## Documentation

- **Full Guide**: [PERSON_CENTRIC_MODEL.md](PERSON_CENTRIC_MODEL.md)
- **Summary**: [PERSON_CENTRIC_REFACTORING_SUMMARY.md](PERSON_CENTRIC_REFACTORING_SUMMARY.md)
- **Architecture**: [ARCHITECTURE.md](ARCHITECTURE.md)
- **Examples**: [examples/](examples/)

## Key Points

1. ✅ Person persists all day
2. ✅ Vehicle is passive asset
3. ✅ Trip driven by activity schedule
4. ✅ Driver attributes configure physics
5. ✅ TimeManager unchanged
6. ✅ Link logic unchanged

---

**Quick Start**: Copy [person_centric_scenario.json](examples/person_centric_scenario.json) and modify for your scenario!
