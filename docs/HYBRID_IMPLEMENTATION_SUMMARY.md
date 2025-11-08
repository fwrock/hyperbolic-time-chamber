# Hybrid Micro-Meso Simulator - Implementation Summary

**Date:** December 2024  
**Status:** Foundation Complete ✅  
**Framework:** Scala 3.3.5, Apache Pekko

---

## 📋 Overview

Successfully implemented the foundational architecture for a **hybrid micro-meso traffic simulator** that enables seamless transitions between mesoscopic (aggregate) and microscopic (individual) vehicle simulation modes. The key innovation is **link-based mode selection**: each link determines whether vehicles execute in MESO or MICRO mode, allowing for efficient city-wide simulation (MESO) with detailed regions (MICRO).

---

## ✅ Completed Components

### 1. **Directory Structure** (Task 1)
Created complete package hierarchy:
```
src/main/scala/model/hybrid/
├── actor/                          # Hybrid actors
├── entity/
│   ├── state/                      # State models
│   │   ├── enumeration/            # Enums
│   │   └── model/                  # Lane models
│   └── event/data/                 # Event data classes
└── micro/
    ├── model/                      # Car-following models
    ├── lane/                       # Lane-change models
    └── manager/                    # Time managers
```

### 2. **Enumerations & Models** (Task 2)
**Files Created:**
- `SimulationModeEnum.scala` - MESO vs MICRO mode
- `LaneTypeEnum.scala` - Lane types (NORMAL, BUS_LANE, BIKE_LANE, etc.)
- `LaneModels.scala` - LaneConfig and VehicleInLane

**Key Features:**
- Clean mode separation
- Lane type restrictions
- Vehicle-in-lane tracking with gap calculations

### 3. **Microscopic State Interface** (Task 3)
**File:** `MicroMovableState.scala`

**Trait Definition:**
```scala
trait MicroMovableState {
  def positionInLink: Double        // Position in link (m)
  def velocity: Double              // Velocity (m/s)
  def acceleration: Double          // Acceleration (m/s²)
  def currentLane: Int              // Lane number
  def leaderVehicle: Option[String] // Leader ID
  def gapToLeader: Double           // Gap to leader (m)
  def leaderVelocity: Double        // Leader velocity (m/s)
  
  // Vehicle capabilities
  def maxAcceleration: Double       // m/s²
  def maxDeceleration: Double       // m/s²
  def minGap: Double                // m
  def desiredVelocity: Double       // m/s
  def reactionTime: Double          // s
  def vehicleLength: Double         // m
}
```

### 4. **Microscopic Vehicle States** (Task 4)
**Files Created:**
- `MicroCarState.scala` - Standard car (4.5m, 2.6 m/s² accel)
- `MicroBusState.scala` - Bus (12m, 1.2 m/s² accel, capacity tracking)
- `MicroBicycleState.scala` - Bicycle (2m, 1.0 m/s² accel, bike lane preference)
- `MicroMotorcycleState.scala` - Motorcycle (2.5m, 3.5 m/s² accel, lane filtering)

**Vehicle-Specific Features:**

| Vehicle    | Length | Max Accel | Desired Vel | Special Features               |
|------------|--------|-----------|-------------|--------------------------------|
| Car        | 4.5m   | 2.6 m/s²  | 13.89 m/s   | Standard parameters            |
| Bus        | 12m    | 1.2 m/s²  | 11.11 m/s   | Capacity, bus stops, lane restrictions |
| Bicycle    | 2m     | 1.0 m/s²  | 5.56 m/s    | Bike lane preference           |
| Motorcycle | 2.5m   | 3.5 m/s²  | 16.67 m/s   | Lane filtering, aggressiveness |

**Helper Methods:**
- `withUpdatedKinematics()` - Update position/velocity/acceleration
- `withUpdatedLeader()` - Update leader information
- `initiatingLaneChange()` - Start lane change
- `progressingLaneChange()` - Update lane change progress

### 5. **Hybrid Wrapper States** (Task 5)
**Files Created:**
- `HybridLinkState.scala` - Link with MESO/MICRO mode flag
- `HybridNodeState.scala` - Node with conflict zones for intersections
- `HybridCarState.scala` - Car with mode switching
- `HybridBusState.scala` - Bus with mode switching
- `HybridBicycleMotorcycleState.scala` - Bicycle and Motorcycle wrappers

**Key Pattern:**
```scala
case class HybridCarState(
  // Meso fields (inherited from CarState)
  startTick: Tick,
  origin: String,
  destination: String,
  bestRoute: Option[Queue[(String, String)]],
  currentNode: String,
  distance: Double,
  
  // Hybrid control
  currentSimulationMode: SimulationModeEnum = MESO,
  
  // Micro state (activated in MICRO links)
  microState: Option[MicroCarState] = None
) extends MovableState {
  
  def activateMicroMode(initialMicroState: MicroCarState): Unit
  def deactivateMicroMode(): Unit
  def updateMicroState(newMicroState: MicroCarState): Unit
}
```

**HybridLinkState Features:**
- Mode flag (MESO/MICRO)
- Multi-lane management (`vehiclesByLane`)
- Micro time step configuration (default: 0.1s, 10 sub-ticks/tick)
- Factory methods (`fromLinkState()`)
- Meso compatibility (`registered` set)

**HybridNodeState Features:**
- Conflict zone management for micro intersections
- `ConflictZone` case class with occupancy tracking
- Methods: `isMicroIntersection()`, `canEnterConflictZone()`, `updateConflictZone()`

### 6. **Event Data Classes** (Task 6)
**File:** `MicroEventData.scala`

**Classes Created:**
- `MicroEnterLinkData` - Vehicle enters MICRO link (initial state)
- `MicroLeaveLinkData` - Vehicle exits MICRO link (final statistics)
- `MicroUpdateData` - Sub-tick kinematic update
- `MicroStepData` - Vehicle requests micro step
- `LaneChangeData` - Lane change request/progress
- `FollowingUpdateData` - Car-following calculation details
- `IntersectionMicroData` - Conflict zone coordination
- `MicroTicksCompleted` - Global tick completion signal

**Example:**
```scala
case class MicroUpdateData(
  subTick: Int,
  position: Double,
  velocity: Double,
  acceleration: Double,
  currentLane: Int,
  leaderVehicle: Option[String],
  gapToLeader: Double,
  leaderVelocity: Double,
  safeVelocity: Double
)
```

### 7. **Car-Following Models** (Task 7)
**Files Created:**
- `CarFollowingModel.scala` - Interface trait
- `KraussModel.scala` - Default implementation

**Krauss Model Formula:**
```
v_safe = -τ·b + √((τ·b)² + v_leader² + 2·b·gap)

where:
  τ = reaction time (1.0s)
  b = max deceleration (4.5 m/s²)
  gap = distance to leader - min gap
  v_leader = leader velocity
```

**Interface Methods:**
- `calculateSafeVelocity()` - Safe velocity considering leader
- `calculateAcceleration()` - Acceleration for next step
- `updateState()` - Full state update
- `modelName` - Model identifier

**Krauss Features:**
- Randomness factor (default 0.2) for driver variability
- Acceleration constraints
- Emergency braking handling
- Factory methods: `default`, `withRandomness()`, `withSeed()`, `deterministic`

### 8. **Lane-Change Models** (Task 8)
**Files Created:**
- `LaneChangeModel.scala` - Interface trait with `SimpleLaneChange`
- `MobilLaneChange.scala` - MOBIL implementation

**MOBIL Model (Kesting et al. 2007):**

**Incentive Criterion:**
```
Δa = a_target - a_current + p·(Δa_follower_current + Δa_follower_target) + a_bias

Change if: Δa > a_threshold
```

**Safety Criterion:**
```
Required deceleration for follower ≤ b_safe
```

**Parameters:**
- `politeness` (p) - Weight of other drivers [0.0-1.0]
- `safeDeceleration` (b_safe) - Max safe decel for followers
- `accelerationThreshold` (a_th) - Min advantage to change
- `biasToRight` - Preference for right lanes

**Factory Variants:**
- `apply()` - Default (politeness=0.5)
- `aggressive` - Low politeness (0.1)
- `polite` - High politeness (0.8)

**SimpleLaneChange:**
- Basic rule-based model
- Minimum gap checking
- Faster lane preference
- Keep-right rule

### 9. **LinkMicroTimeManager** (Task 9)
**File:** `LinkMicroTimeManager.scala`

**Actor Responsibilities:**
- Manage sub-tick execution (e.g., 10 × 0.1s per global tick)
- Maintain lane-sorted vehicle queues
- Apply car-following model per sub-tick
- Process lane changes
- Send updates to vehicles
- Avoid global time manager bottleneck

**Command Messages:**
- `RegisterVehicle` - Vehicle enters link
- `UnregisterVehicle` - Vehicle exits link
- `ExecuteGlobalTick` - Run all sub-ticks
- `UpdateVehicleState` - External state update
- `RequestLaneChange` - Lane change request

**Execution Flow:**
```
ExecuteGlobalTick(tick)
  └─> for each sub-tick (0..9):
      ├─> processLane(0)
      │   └─> for each vehicle:
      │       ├─> find leader
      │       ├─> calculate gap
      │       ├─> apply car-following
      │       ├─> update position/velocity
      │       └─> send MicroUpdateData
      ├─> processLane(1)
      └─> ...
  └─> notify tick completed
```

**Data Structures:**
- `vehiclesByLane: Map[Int, Queue[VehicleInLane]]` - Lane-sorted vehicles
- `vehicleActors: Map[String, ActorRef]` - Vehicle actor references

### 10. **HybridLink Actor** (Task 10)
**File:** `HybridLink.scala`

**Key Features:**
- Extends `SimulationBaseActor[HybridLinkState]`
- Mode detection: `state.isMicroMode`
- Spawns `LinkMicroTimeManager` for MICRO links
- Handles both MESO and MICRO events
- Backward compatible with existing mesoscopic actors

**MESO Mode Behavior:**
```scala
handleEnterLinkMeso():
  1. Register vehicle
  2. Send LinkInfoData (standard response)
  3. Single-tick traversal
```

**MICRO Mode Behavior:**
```scala
handleEnterLinkMicro():
  1. Register vehicle
  2. Assign lane (least occupied)
  3. Send MicroEnterLinkData
  4. Register with LinkMicroTimeManager
  5. Multi-tick traversal with sub-ticks
```

**Event Handlers:**
- `handleEnterLink()` - Route to MESO/MICRO handler
- `handleLeaveLink()` - Unregister and send exit data
- `handleMicroStep()` - Forward to time manager
- `handleLaneChange()` - Forward to time manager
- `onGlobalTick()` - Trigger time manager execution

**Helper Methods:**
- `initializeMicroMode()` - Spawn time manager
- `findLeastOccupiedLane()` - Lane assignment strategy

---

## 🔄 Execution Flow: MESO → MICRO → MESO

### Vehicle Perspective

```
1. Vehicle in MESO link
   └─> Standard mesoscopic calculation
   └─> Single tick traversal

2. Vehicle enters MICRO link
   └─> Receives MicroEnterLinkData
   ├─> Activates microState
   ├─> Position = 0, Lane = assigned
   └─> Registers with LinkMicroTimeManager

3. Microscopic Execution
   └─> For each global tick:
       ├─> LinkMicroTimeManager executes 10 sub-ticks
       ├─> Each sub-tick:
       │   ├─> Find leader vehicle
       │   ├─> Calculate gap, leader velocity
       │   ├─> Apply Krauss model → safe velocity
       │   ├─> Calculate acceleration
       │   ├─> Update position, velocity
       │   ├─> Check lane change (MOBIL)
       │   └─> Send MicroUpdateData to vehicle
       └─> Vehicle updates microState

4. Vehicle exits MICRO link
   └─> Position ≥ linkLength
   ├─> Receives MicroLeaveLinkData
   ├─> Deactivates microState
   └─> Returns to MESO mode

5. Vehicle continues in MESO link
   └─> Normal mesoscopic behavior resumes
```

### Link Perspective

```
HybridLink (MICRO mode):
  ├─> onInitialize()
  │   └─> Spawns LinkMicroTimeManager
  │
  ├─> Vehicle enters
  │   ├─> Assign lane
  │   ├─> Send MicroEnterLinkData
  │   └─> LinkMicroTimeManager.RegisterVehicle
  │
  ├─> onGlobalTick(tick)
  │   └─> LinkMicroTimeManager.ExecuteGlobalTick
  │       └─> 10 sub-ticks executed internally
  │
  └─> Vehicle exits
      ├─> LinkMicroTimeManager.UnregisterVehicle
      └─> Send MicroLeaveLinkData
```

---

## 📊 Statistics

### Files Created
- **Total:** 16 files
- **State models:** 8 files
- **Event data:** 1 file (8 classes)
- **Car-following:** 2 files
- **Lane-change:** 2 files
- **Actors/Managers:** 2 files
- **Enumerations:** 3 files

### Lines of Code (Approximate)
- **States:** ~1,200 lines
- **Models:** ~800 lines
- **Actors:** ~700 lines
- **Events:** ~200 lines
- **Total:** ~2,900 lines

### Vehicle Types Supported
- ✅ Car
- ✅ Bus
- ✅ Bicycle
- ✅ Motorcycle
- 🔄 Subway (partial)
- 🔄 Person (partial)

---

## 🎯 Design Decisions

### 1. **Link-Based Mode Selection**
**Decision:** Links determine simulation mode for ALL vehicles entering.

**Rationale:**
- Cleaner than vehicle-type-based selection
- Enables spatial granularity (downtown MICRO, suburbs MESO)
- Simpler configuration

**Impact:**
- Buses, bicycles, motorcycles all adapt to link mode
- Seamless transitions between modes

### 2. **Local Time Managers**
**Decision:** Each MICRO link spawns own `LinkMicroTimeManager`.

**Rationale:**
- Avoid global time manager bottleneck
- Parallel sub-tick execution across links
- Scalability for large networks

**Impact:**
- More actor overhead
- Better performance for many MICRO links

### 3. **Krauss as Default Model**
**Decision:** Use Krauss car-following model over IDM/Gipps.

**Rationale:**
- Simpler formula
- Proven in SUMO and other simulators
- Good balance of realism and computation

**Impact:**
- Fast computation
- Extensible to other models via interface

### 4. **MOBIL Lane-Change Model**
**Decision:** Implement full MOBIL with politeness factor.

**Rationale:**
- State-of-the-art model
- Considers impact on other drivers
- Configurable behavior (aggressive/polite)

**Impact:**
- More realistic lane changes
- Computationally more expensive than simple model

### 5. **Optional Micro State**
**Decision:** Hybrid states use `Option[MicroState]`.

**Rationale:**
- Memory efficient (only allocate when needed)
- Clear activation/deactivation
- Type-safe mode checking

**Impact:**
- Clean API
- No state waste in MESO mode

---

## 🚀 Next Steps

### Phase 2: Vehicle Actors (Priority)
**Remaining Work:**
1. Create `HybridCar` actor extending `Car`
2. Create `HybridBus` actor extending `Bus`
3. Create `HybridBicycle` actor (new)
4. Create `HybridMotorcycle` actor (new)
5. Implement mode switching logic in vehicle actors
6. Handle `MicroUpdateData` messages
7. Send `MicroStepData` to links

**Estimated Effort:** 4-6 files, ~1,500 lines

### Phase 3: Node & Intersection (Medium Priority)
**Tasks:**
1. Create `HybridNode` actor
2. Implement `MicroIntersectionController`
3. Conflict zone management
4. Priority handling at intersections
5. Signal coordination for MICRO mode

**Estimated Effort:** 3-4 files, ~1,000 lines

### Phase 4: Integration & Testing (High Priority)
**Tasks:**
1. Update actor factory to recognize hybrid actors
2. Create test scenarios (MESO-only, MICRO-only, hybrid)
3. Integration tests for mode transitions
4. Performance benchmarks
5. Validate physics (no negative gaps, realistic speeds)

**Estimated Effort:** Test files, benchmarks, ~800 lines

### Phase 5: Configuration & Deployment
**Tasks:**
1. JSON schema for hybrid link configuration
2. Update `application.conf` for micro parameters
3. Documentation for scenario creation
4. Example scenarios (BRT corridor, downtown grid)

---

## 📚 References

1. **Krauss (1998):** "Microscopic Modeling of Traffic Flow: Investigation of Collision Free Vehicle Dynamics"
2. **Kesting, Treiber, Helbing (2007):** "General Lane-Changing Model MOBIL for Car-Following Models"
3. **Treiber, IDM (2000):** "Congested Traffic States in Empirical Observations and Microscopic Simulations"
4. **Bourrel & Lesort (2003):** "Mixing Micro and Macro Representations of Traffic Flow: A Hybrid Model Based on the LWR Theory"

---

## 🎓 Academic Usage

This implementation provides:
- **Extensible framework** for traffic simulation research
- **Hybrid approach** for scalability studies
- **Multi-modal support** (cars, buses, bicycles, motorcycles)
- **Calibration-ready** models (Krauss parameters, MOBIL politeness)
- **Baseline for comparison** with pure microscopic/mesoscopic simulators

Potential research directions:
- Calibration of hybrid transition points
- Performance analysis (micro vs. meso accuracy trade-offs)
- Multi-modal interaction studies (bicycle-car, bus-car)
- Scalability limits of hybrid approach

---

## ✅ Completion Status

**Phase 1 (Foundation): COMPLETE ✅**
- [x] Directory structure
- [x] State models (micro + hybrid)
- [x] Event data classes
- [x] Car-following models (Krauss)
- [x] Lane-change models (MOBIL)
- [x] LinkMicroTimeManager actor
- [x] HybridLink actor

**Phase 2 (Vehicles): NOT STARTED**
**Phase 3 (Intersections): NOT STARTED**
**Phase 4 (Testing): NOT STARTED**
**Phase 5 (Deployment): NOT STARTED**

---

**End of Implementation Summary**
