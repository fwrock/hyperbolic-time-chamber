# Person-Centric Model: Refactoring Summary

## Overview

Successfully refactored the Hyperbolic Time Chamber simulator from **vehicle-centric** to **person-centric (agent-based)** architecture.

## What Changed

### 1. Core Architecture

#### Before (Vehicle-Centric)
```
Vehicle Actor (Car/Bicycle/Motorcycle)
  └─> Created with origin/destination
  └─> Immediately active (Start status)
  └─> Registers with TimeManager at creation
  └─> Travels independently
  └─> Self-destructs when done
```

#### After (Person-Centric)
```
Person Actor
  └─> Manages daily schedule
  └─> Decides when/how to travel
  └─> Sends StartTrip → Vehicle (Parked)
  
Vehicle Asset
  └─> Created in Parked state (passive)
  └─> Activated by Person's StartTrip
  └─> Configured with Person's driving attributes
  └─> Performs physics on links
  └─> Sends TripCompleted → Person
  └─> Returns to Parked state
```

### 2. New Files Created

| File | Purpose |
|------|---------|
| `PersonState.scala` | Person state with daily schedule, activities |
| `PersonEventData.scala` | StartTrip, TripCompleted, ParkVehicle messages |
| `PrivateVehicle.scala` | Common trait for passive vehicle behavior |
| `PERSON_CENTRIC_MODEL.md` | Complete documentation |
| `person_centric_scenario.json` | Example input format |

### 3. Modified Files

| File | Changes |
|------|---------|
| `MovableStatusEnum.scala` | Added `Parked` status |
| `Person.scala` | Complete rewrite: schedule manager |
| `Car.scala` | Added PrivateVehicle trait, passive init |
| `Bicycle.scala` | Added PrivateVehicle trait, passive init |
| `Motorcycle.scala` | Added PrivateVehicle trait, passive init |

### 4. Key Components

#### PersonState
```scala
case class PersonState(
  dailySchedule: List[Activity],
  currentActivityIndex: Int,
  ownedVehicles: Map[String, Identify],  // mode -> Identify(id, classType)
  currentTripVehicleId: Option[String],
  totalDistanceTraveled: Double,
  completedTrips: Int
)
```

#### Activity
```scala
case class Activity(
  sequence: Int,
  activityType: String,  // "Home", "Work", "Shopping"
  nodeId: String,
  endTime: String,
  arrivalLogistics: Option[ArrivalLogistics]
)
```

#### ArrivalLogistics
```scala
case class ArrivalLogistics(
  mode: String,  // "car", "bicycle", "motorcycle"
  vehicle: Option[Identify],  // Identify(id, classType) for proper message routing
  driverAttributes: DriverAttributes
)
```

#### DriverAttributes
```scala
case class DriverAttributes(
  aggressiveness: Double,    // [0.0 - 1.0]
  maxSpeedFactor: Double,    // [0.5 - 1.5]
  reactionTime: Double,      // [0.5 - 2.0] seconds
  minGapFactor: Double       // [0.5 - 2.0]
)
```

### 5. Message Protocol

#### StartTripData (Person → Vehicle)
```scala
case class StartTripData(
  personId: String,
  origin: String,
  destination: String,
  driverAttributes: DriverAttributes,
  startTick: Tick
)
```

#### TripCompletedData (Vehicle → Person)
```scala
case class TripCompletedData(
  vehicleId: String,
  personId: String,
  distanceTraveled: Double,
  travelTime: Long,
  finalNode: String,
  completionTick: Tick,
  completionReason: String
)
```

### 6. Vehicle Lifecycle

```scala
trait PrivateVehicle[T <: MovableState] {
  // Initialization
  protected def initializeAsParked(): Unit
  
  // Activation
  protected def handleStartTrip(event, data: StartTripData): Unit
  
  // Configuration
  protected def applyDriverAttributes(attrs: DriverAttributes): Unit
  
  // Completion
  protected def reportTripCompletion(reason, finalNode): Unit
  protected def deactivateVehicle(): Unit
  
  // Status checks
  protected def isActive: Boolean
  protected def isParked: Boolean
}
```

## Benefits

### 1. Realism
- **Activity-Based Demand**: People perform activities, not just trips
- **Mode Choice**: People decide how to travel
- **Driver Heterogeneity**: Same vehicle behaves differently with different drivers

### 2. Flexibility
- **Multi-Modal**: Person can use car, bicycle, motorcycle in one day
- **Trip Chaining**: Natural representation of activity chains
- **Vehicle Sharing**: (Future) Multiple persons can share vehicles

### 3. Analysis
- **Policy Testing**: Evaluate mode choice policies
- **Demand Modeling**: Activity patterns drive travel demand
- **Behavioral Studies**: Driver aggressiveness, route choice, mode choice

### 4. Scalability
- **Vehicle Reuse**: Vehicles are assets, not single-trip entities
- **Resource Efficiency**: Parked vehicles consume no simulation resources
- **Larger Populations**: Can simulate more persons with fewer vehicle instances

## Example Use Cases

### 1. Morning Commute
```json
{
  "dailySchedule": [
    {"activityType": "Home", "nodeId": "home", "endTime": "28800"},
    {
      "activityType": "Work",
      "nodeId": "office",
      "endTime": "61200",
      "arrivalLogistics": {
        "mode": "car",
        "vehicleId": "my_car",
        "driverAttributes": {"aggressiveness": 0.7}
      }
    }
  ]
}
```

### 2. Multi-Modal Day
```json
{
  "dailySchedule": [
    {"activityType": "Home", "endTime": "28800"},
    {"activityType": "Work", "arrivalLogistics": {"mode": "car"}},
    {"activityType": "Gym", "arrivalLogistics": {"mode": "bicycle"}},
    {"activityType": "Home", "arrivalLogistics": {"mode": "bicycle"}}
  ]
}
```

### 3. Aggressive Motorcycle Rider
```json
{
  "arrivalLogistics": {
    "mode": "motorcycle",
    "driverAttributes": {
      "aggressiveness": 0.9,
      "maxSpeedFactor": 1.3,
      "reactionTime": 0.7
    }
  }
}
```

## Simulation Flow

```
Tick 0:
  Person_1 at Home
  Car_1: Parked
  
Tick 28800 (08:00):
  Person_1: Activity "Home" ends
  Person_1: Read nextActivity.arrivalLogistics → mode="car"
  Person_1: Send StartTrip(origin="home", dest="work") → Car_1
  
Tick 28801:
  Car_1: Receive StartTrip
  Car_1: Apply driverAttributes (aggressiveness=0.7)
  Car_1: Status: Parked → Start
  Car_1: Register with TimeManager
  
Tick 28802:
  Car_1: Request route (home → work)
  Car_1: Status: Start → Ready
  
Tick 28803-29500:
  Car_1: Traverse links (MESO or MICRO)
  Car_1: Status: Ready → Moving
  
Tick 29500:
  Car_1: Reach destination "work"
  Car_1: Send TripCompleted(distance=5000m, time=697) → Person_1
  Car_1: Status: Moving → Parked
  Car_1: Unregister from TimeManager
  
Tick 29501:
  Person_1: Receive TripCompleted
  Person_1: Advance to Activity "Work"
  Person_1: Update totalDistance += 5000
  
Tick 61200 (17:00):
  Person_1: Activity "Work" ends
  Person_1: Repeat cycle (work → home)
```

## Testing Strategy

### Unit Tests
1. **PersonState**: Activity advancement, trip tracking
2. **PrivateVehicle**: Activation, deactivation, attribute application
3. **DriverAttributes**: Validation, effects on physics

### Integration Tests
1. **Person-Vehicle Communication**: StartTrip → TripCompleted flow
2. **Multi-Modal Trips**: Person using different vehicles
3. **Mode Transitions**: MESO ↔ MICRO with driver attributes

### Scenario Tests
1. **Daily Commute**: Simple home-work-home pattern
2. **Multi-Stop Trip Chain**: Home → Work → Shopping → Home
3. **Mixed Fleet**: Cars, bicycles, motorcycles in same scenario

## Migration Path

### Phase 1: Dual Mode (Current)
- Support both vehicle-centric and person-centric input
- Old format: Vehicles with origin/destination
- New format: Persons with schedules + Parked vehicles

### Phase 2: Person-Centric Default
- Person-centric becomes primary input format
- Vehicle-centric maintained for compatibility

### Phase 3: Pure Person-Centric
- Remove vehicle-centric mode
- All simulations are person-based

## Performance Considerations

### Advantages
1. **Parked Vehicles**: No spontaneous events when Parked
2. **Selective Activation**: Only active vehicles in TimeManager
3. **Resource Reuse**: Vehicles persist, no creation/destruction overhead

### Monitoring
- Track active vs. parked vehicle ratios
- Measure Person actor overhead
- Monitor message passing (StartTrip, TripCompleted)

## Future Enhancements

### 1. Advanced Mode Choice
```scala
def executeModeChoice(origin, destination, time): String = {
  val utilities = Map(
    "car" -> calculateUtility(mode, travelTime, cost, weather),
    "bicycle" -> calculateUtility(...),
    "transit" -> calculateUtility(...)
  )
  chooseMaxUtility(utilities)
}
```

### 2. Household Modeling
```json
{
  "household": {
    "id": "household_1",
    "persons": ["person_1", "person_2"],
    "sharedVehicles": {
      "car": "family_car"
    }
  }
}
```

### 3. Ride-Hailing
```json
{
  "arrivalLogistics": {
    "mode": "ridehail",
    "provider": "uber",
    "vehicleType": "sedan"
  }
}
```

### 4. Public Transit
```json
{
  "arrivalLogistics": {
    "mode": "transit",
    "line": "bus_42",
    "access": "walk",
    "egress": "walk"
  }
}
```

### 5. Parking Constraints
```scala
case class ParkingSearch(
  destination: String,
  maxSearchTime: Int,
  parkingCost: Double
)
```

## Documentation

### User Guides
- ✅ [PERSON_CENTRIC_MODEL.md](PERSON_CENTRIC_MODEL.md) - Complete guide
- ✅ [person_centric_scenario.json](examples/person_centric_scenario.json) - Example input

### API Reference
- See existing [API_REFERENCE.md](API_REFERENCE.md) for actor interfaces
- Add PersonActor, PrivateVehicle sections

### Configuration
- See [CONFIGURATION.md](CONFIGURATION.md) for person/vehicle parameters

## Contact

For questions or contributions:
1. Review [PERSON_CENTRIC_MODEL.md](PERSON_CENTRIC_MODEL.md)
2. Check [examples/](examples/) for input formats
3. See [ARCHITECTURE.md](ARCHITECTURE.md) for system design

## Summary

The person-centric refactoring transforms the simulator from a collection of independent vehicle trips to a realistic agent-based model where:

1. **Persons** are the primary agents with daily schedules
2. **Vehicles** are passive assets activated by persons
3. **Trips** are consequences of activity patterns
4. **Mode choice** reflects real-world decision-making
5. **Driver behavior** varies by person, not vehicle

This enables more realistic traffic simulation, policy analysis, and demand modeling while maintaining backward compatibility and preserving existing TimeManager and Link logic.
