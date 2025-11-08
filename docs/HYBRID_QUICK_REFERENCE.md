# Hybrid Simulator - Quick Reference

## 📂 File Locations

### States
```
model/hybrid/entity/state/
├── enumeration/
│   ├── SimulationModeEnum.scala          # MESO vs MICRO
│   └── LaneTypeEnum.scala                # Lane types
├── model/
│   └── LaneModels.scala                  # LaneConfig, VehicleInLane
├── MicroMovableState.scala               # Base trait for micro states
├── MicroCarState.scala                   # Car micro state
├── MicroBusState.scala                   # Bus micro state
├── MicroBicycleState.scala               # Bicycle micro state
├── MicroMotorcycleState.scala            # Motorcycle micro state
├── HybridLinkState.scala                 # Link with mode flag
├── HybridNodeState.scala                 # Node with conflict zones
├── HybridCarState.scala                  # Car wrapper
├── HybridBusState.scala                  # Bus wrapper
└── HybridBicycleMotorcycleState.scala    # Bicycle + Motorcycle wrappers
```

### Events
```
model/hybrid/entity/event/data/
└── MicroEventData.scala                  # All micro event data classes
```

### Models
```
model/hybrid/micro/
├── model/
│   ├── CarFollowingModel.scala           # Interface + factory
│   └── KraussModel.scala                 # Krauss implementation
├── lane/
│   ├── LaneChangeModel.scala             # Interface + SimpleLaneChange
│   └── MobilLaneChange.scala             # MOBIL implementation
└── manager/
    └── LinkMicroTimeManager.scala        # Sub-tick time manager
```

### Actors
```
model/hybrid/actor/
└── HybridLink.scala                      # Hybrid link actor
```

---

## 🔧 Key Classes

### SimulationModeEnum
```scala
enum SimulationModeEnum:
  case MESO  // Aggregate mesoscopic simulation
  case MICRO // Individual microscopic simulation
```

### MicroMovableState (Trait)
```scala
trait MicroMovableState {
  def positionInLink: Double        // m
  def velocity: Double              // m/s
  def acceleration: Double          // m/s²
  def currentLane: Int              // Lane number
  def maxAcceleration: Double       // m/s²
  def maxDeceleration: Double       // m/s²
  def minGap: Double                // m
  def desiredVelocity: Double       // m/s
  def vehicleLength: Double         // m
}
```

### HybridLinkState
```scala
case class HybridLinkState(
  // Meso fields
  from: String, to: String, length: Double, lanes: Int,
  speedLimit: Double, capacity: Double, freeSpeed: Double,
  
  // Hybrid fields
  simulationMode: SimulationModeEnum,       // MESO or MICRO
  microTimeStep: Double = 0.1,              // Sub-tick duration (s)
  microTicksPerGlobalTick: Int = 10,
  
  // Micro fields
  vehiclesByLane: Map[Int, Queue[VehicleInLane]],
  
  // Meso compatibility
  registered: mutable.Set[LinkRegister]
) {
  def isMicroMode: Boolean
  def isMesoMode: Boolean
}
```

### HybridCarState
```scala
case class HybridCarState(
  // Meso fields
  startTick: Tick, origin: String, destination: String,
  bestRoute: Option[Queue[(String, String)]],
  currentNode: String, distance: Double,
  
  // Hybrid control
  currentSimulationMode: SimulationModeEnum = MESO,
  microState: Option[MicroCarState] = None
) {
  def activateMicroMode(initialMicroState: MicroCarState): Unit
  def deactivateMicroMode(): Unit
  def updateMicroState(newMicroState: MicroCarState): Unit
}
```

---

## 📨 Event Flow

### Vehicle Enters MICRO Link
```
Vehicle → Link: EnterLinkData
Link → Vehicle: MicroEnterLinkData
Link → LinkMicroTimeManager: RegisterVehicle
```

### Microscopic Execution (per global tick)
```
Link → LinkMicroTimeManager: ExecuteGlobalTick(tick)
LinkMicroTimeManager (internally):
  └─> For each sub-tick:
      ├─> Process lane 0, 1, 2...
      ├─> Apply car-following model
      ├─> Update positions/velocities
      └─> Send MicroUpdateData to vehicles
```

### Vehicle Exits MICRO Link
```
Vehicle → Link: LeaveLinkData
Link → LinkMicroTimeManager: UnregisterVehicle
Link → Vehicle: MicroLeaveLinkData
```

---

## 🚗 Vehicle Parameters

| Vehicle    | Length | Max Accel | Max Decel | Desired Vel | Min Gap |
|------------|--------|-----------|-----------|-------------|---------|
| Car        | 4.5m   | 2.6 m/s²  | 4.5 m/s²  | 13.89 m/s   | 2.0m    |
| Bus        | 12.0m  | 1.2 m/s²  | 3.5 m/s²  | 11.11 m/s   | 3.0m    |
| Bicycle    | 2.0m   | 1.0 m/s²  | 3.0 m/s²  | 5.56 m/s    | 1.5m    |
| Motorcycle | 2.5m   | 3.5 m/s²  | 5.0 m/s²  | 16.67 m/s   | 1.5m    |

---

## 🧮 Krauss Model

### Safe Velocity Formula
```
v_safe = -τ·b + √((τ·b)² + v_leader² + 2·b·gap)

where:
  τ = reaction time (1.0s)
  b = max deceleration (4.5 m/s²)
  gap = distance to leader - min gap
  v_leader = leader velocity
```

### Usage
```scala
val model = KraussModel()
val (newPos, newVel, accel) = model.updateState(
  state = microState,
  gap = gapToLeader,
  leaderVelocity = leaderVel,
  deltaT = 0.1
)
```

---

## 🛣️ MOBIL Lane-Change Model

### Incentive Criterion
```
Δa = a_target - a_current 
     + p·(Δa_follower_current + Δa_follower_target) 
     + a_bias

Change if: Δa > a_threshold
```

### Parameters
- **Politeness (p):** 0.0 (aggressive) to 1.0 (polite)
- **Safe deceleration (b_safe):** 4.0 m/s²
- **Acceleration threshold (a_th):** 0.1 m/s²
- **Bias to right:** 0.2 m/s²

### Usage
```scala
val model = MobilLaneChange.polite  // or .aggressive, .apply()
val decision = model.evaluateLaneChange(
  vehicleState, currentLane, 
  leaderInCurrentLane, followerInCurrentLane,
  leaderInTargetLane, followerInTargetLane,
  targetLane, numberOfLanes, laneRestrictions
)

if (decision.shouldChange) {
  // Execute lane change to decision.targetLane
}
```

---

## 🔄 Mode Transition Example

```scala
// Vehicle in MESO mode
val vehicle = HybridCarState(
  startTick = 0,
  origin = "node1",
  destination = "node2",
  currentSimulationMode = MESO,
  microState = None
)

// Vehicle enters MICRO link
val initialMicro = MicroCarState(
  positionInLink = 0.0,
  velocity = 10.0,
  acceleration = 0.0,
  currentLane = 0,
  leaderVehicle = None,
  gapToLeader = 100.0,
  leaderVelocity = 10.0
)

vehicle.activateMicroMode(initialMicro)
// Now: vehicle.currentSimulationMode == MICRO
// Now: vehicle.microState == Some(initialMicro)

// ... micro simulation ...

// Vehicle exits MICRO link
vehicle.deactivateMicroMode()
// Now: vehicle.currentSimulationMode == MESO
// Now: vehicle.microState == None
```

---

## 🏗️ LinkMicroTimeManager Commands

```scala
// Register vehicle entering link
RegisterVehicle(
  vehicleId = "car_1",
  lane = 0,
  position = 0.0,
  velocity = 10.0,
  vehicleLength = 4.5,
  actor = vehicleActorRef
)

// Execute global tick (runs all sub-ticks internally)
ExecuteGlobalTick(globalTick = 42)

// Update vehicle state
UpdateVehicleState(
  vehicleId = "car_1",
  position = 15.5,
  velocity = 12.0,
  lane = 0
)

// Request lane change
RequestLaneChange(
  vehicleId = "car_1",
  fromLane = 0,
  toLane = 1
)

// Unregister vehicle leaving link
UnregisterVehicle(vehicleId = "car_1")
```

---

## 📊 Configuration Example

### JSON: MICRO Link Configuration
```json
{
  "id": "htcaid:link;downtown_main",
  "typeActor": "hybrid.actor.HybridLink",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "from": "htcaid:node;intersection_01",
      "to": "htcaid:node;intersection_02",
      "length": 500.0,
      "lanes": 3,
      "speedLimit": 50.0,
      "freeSpeed": 50.0,
      "capacity": 2000,
      
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      
      "laneConfigurations": [
        {"laneId": 0, "type": "NORMAL"},
        {"laneId": 1, "type": "NORMAL"},
        {"laneId": 2, "type": "BUS_LANE"}
      ]
    }
  }
}
```

### JSON: MESO Link Configuration
```json
{
  "id": "htcaid:link;suburb_road",
  "typeActor": "hybrid.actor.HybridLink",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "from": "htcaid:node;suburb_01",
      "to": "htcaid:node;suburb_02",
      "length": 1000.0,
      "lanes": 2,
      "speedLimit": 60.0,
      "freeSpeed": 60.0,
      "capacity": 1500,
      
      "simulationMode": "MESO"
    }
  }
}
```

---

## 🐛 Debugging Tips

### Check if Link is in MICRO Mode
```scala
if (linkState.isMicroMode) {
  println("Link is in MICRO mode")
}
```

### Check Vehicle Mode
```scala
if (carState.isMicroMode) {
  println(s"Car is in MICRO mode at position ${carState.microState.map(_.positionInLink)}")
}
```

### Verify Gap Calculations
```scala
val vehicle = vehicleInLane
vehicle.leader match {
  case Some(leader) =>
    val gap = vehicle.gapTo(leader)
    assert(gap >= 0, s"Negative gap! $gap")
  case None =>
    println("Vehicle has no leader (free road)")
}
```

### Log Sub-Tick Execution
```scala
context.log.trace(s"Sub-tick $subTick: vehicle $id at pos=$pos, vel=$vel, accel=$accel")
```

---

## 📚 Further Reading

- **Full Documentation:** `docs/HYBRID_IMPLEMENTATION_SUMMARY.md`
- **Architecture:** `docs/ARCHITECTURE.md`
- **Copilot Instructions:** `.github/copilot-instructions.md`
- **API Reference:** `docs/API_REFERENCE.md`

---

**Quick Reference v1.0 - December 2024**
