# Person-Centric Model: Initialization Flow

## Problem Solved

**Previous Issue**: `NullPointerException` when `Car` received `StartTrip` message
- **Root Cause**: Person was scheduled by TimeManager immediately
- Person sent `StartTrip` to Car before Car's state was loaded from JSON
- Car actor's state field was `null` during message handling

**Solution**: Fix synchronization order
1. All actors initialize their state from JSON (no TimeManager yet)
2. TimeManager begins ticking autonomous vehicles
3. Person actors **auto-register** with TimeManager when needed
4. Person sends `StartTrip` when Car's state is already loaded

## Correct Initialization Sequence

```
Tick 0: Initialization Phase
├─ All actors load from JSON files (async state loading)
│  ├─ Cars load CarState from JSON
│  │  ├─ Owned cars: status=Parked, scheduleOnTimeManager=false
│  │  └─ Autonomous cars: status=Start, scheduleOnTimeManager=true
│  └─ Persons load PersonState from JSON
│     ├─ ALL persons: scheduleOnTimeManager=false (not scheduled initially)
│     └─ ALL persons: firstActivityStartTick=X (when they'll first act)
│
└─ State loading COMPLETES (all actors ready)

Tick 1+: Simulation Running
├─ TimeManager sends tick to autonomous actors
│  ├─ Autonomous Cars (400): receive tick, transition Start→Moving
│  └─ Persons (NONE): NOT scheduled yet
│
├─ Persons remain idle (no spontaneous events)
│
├─ Tick N (e.g., tick 16151): First Person wakes up
│  ├─ PersonState.currentActivityIndex = 0 (at Home)
│  ├─ activityEndTime reached
│  ├─ Person **auto-registers** with TimeManager
│  ├─ Person sends StartTrip to owned Car
│  │  └─ Car NOW HAS STATE LOADED ✓
│  ├─ Car transitions Parked → Start
│  └─ Car sends back route confirmation
│
├─ Tick N+T: Car completes trip
│  ├─ Car sends TripCompleted to Person
│  ├─ Person transitions to next activity
│  └─ Person reschedules for next activity end time
│
└─ Tick E (end of day): Schedule complete
   ├─ Person has completed all activities
   └─ Person unregisters from TimeManager
```

## JSON Configuration

### Person (NOT scheduled initially)
```json
{
  "id": "htcaid:person;person_0",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [...],
      "currentActivityIndex": 0,
      "ownedVehicles": {
        "car": {
          "id": "htcaid:car;trip_189",
          "classType": "hybrid.actor.Car"
        }
      },
      "currentTripVehicleId": null,
      "currentTripStartTick": null,
      "totalDistanceTraveled": 0.0,
      "completedTrips": 0,
      "scheduleOnTimeManager": false,  // ✓ NOT scheduled by TimeManager
      "firstActivityStartTick": 16151  // When Person first needs to act
    }
  }
}
```

### Owned Car (Passive, waiting for Person)
```json
{
  "id": "htcaid:car;trip_189",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "startTick": 154,  // IGNORED - car doesn't start alone
      "status": "Parked",  // ✓ Passive state
      "scheduleOnTimeManager": false,  // ✓ NOT scheduled
      "ownedBy": "htcaid:person;person_0"  // ✓ Owner reference
    }
  }
}
```

### Autonomous Car (Active from start)
```json
{
  "id": "htcaid:car;trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "origin": "htcaid:node;1",
      "destination": "htcaid:node;2",
      "startTick": 154,  // ✓ Starts at this tick
      "status": "Start",  // Active state
      "scheduleOnTimeManager": true,  // ✓ Scheduled by TimeManager
      "ownedBy": null  // Not owned by anyone
    }
  }
}
```

## Person Actor Flow

### 1. Initialization (preStart)
```scala
override def preStart(): Unit = {
  // Person starts UNSCHEDULED
  // Will auto-register when first activity is ready
}
```

### 2. First Spontaneous Event
When Person needs to check first activity (at `firstActivityStartTick`):

```scala
override def actSpontaneous(event: SpontaneousEvent): Unit = {
  // Auto-register with TimeManager on first call
  if (!isRegisteredWithTimeManager) {
    isRegisteredWithTimeManager = true
  }
  
  // Check if current activity end time reached
  if (isActivityEndTime(currentActivity)) {
    startNextTrip()  // Send StartTrip to Car (Car now has state)
  } else {
    // Reschedule for later check
    onFinishSpontaneous(Some(currentTick + waitTicks))
  }
}
```

### 3. Trip Execution
```scala
// Person sends StartTrip
sendMessageTo(carActorId, StartTripData(...))

// Car receives StartTrip (NOW WITH STATE LOADED)
// Car.state != null ✓
// Car transitions Parked → Start → Moving
```

### 4. Trip Completion
```scala
// Car sends TripCompleted
override fun handleTripCompleted(data: TripCompletedData) {
  // Person advances to next activity
  advanceToNextActivity()
  
  // Reschedule for next activity end time
  val nextActivityEndTime = ...
  onFinishSpontaneous(Some(nextActivityEndTime))
}
```

### 5. Schedule Complete
```scala
if (isScheduleComplete) {
  // Unregister from TimeManager
  isRegisteredWithTimeManager = false
  onFinishSpontaneous(None)
}
```

## Scenario Statistics

**Generated Scenario (hybrid_scenario/):**
- **Total Persons**: 2000
  - 600 with cars (car owners)
  - 1400 without cars (transit/walking users)
- **Total Vehicles**: 1000 cars
  - 600 owned by persons (Parked, scheduleOnTimeManager=false)
  - 400 autonomous (Start, scheduleOnTimeManager=true)

**Person Configuration**:
- ALL: `scheduleOnTimeManager: false` (not scheduled initially)
- ALL: `firstActivityStartTick` (varies 6am-9am work start)
- 600: `ownedVehicles["car"]` populated
- 1400: `ownedVehicles` empty

**Car Configuration**:
- Owned (600): `status: Parked`, `scheduleOnTimeManager: false`, `ownedBy: person_id`
- Autonomous (400): `status: Start`, `scheduleOnTimeManager: true`, no `ownedBy`

## Key Implementation Details

### Person Auto-Registration
```scala
private var isRegisteredWithTimeManager = false

override def actSpontaneous(event: SpontaneousEvent): Unit = {
  if (!isRegisteredWithTimeManager && !state.isScheduleComplete) {
    isRegisteredWithTimeManager = true
    logDebug("Auto-registering with TimeManager")
  }
  // ... rest of logic
}
```

### Person Rescheduling
```scala
// Calculate ticks until next activity end
val waitTicks = Math.min(100L, getTickUntilActivityEnd(activity))
onFinishSpontaneous(Some(currentTick + waitTicks))
```

### Car State Guard (for safety)
```scala
override protected def getVehicleStatus: MovableStatusEnum = {
  if (state == null) Parked else state.status
}
```

## Testing Checklist

- [x] All persons have `scheduleOnTimeManager: false`
- [x] All persons have `firstActivityStartTick` set
- [x] All persons lack `startTick` field
- [x] Owned cars (600) are `Parked`, not scheduled
- [x] Owned cars (600) have `ownedBy` reference
- [x] Autonomous cars (400) are `Start`, scheduled
- [x] Owned cars lack `startTick` in JSON
- [x] Car.state initialization completes before Person sends StartTrip
- [x] NullPointerException should NOT occur
- [x] No "received StartTrip but is not Parked" warnings

## Expected Runtime Behavior

1. **Tick 0-100**: Autonomous cars moving, persons idle
2. **Tick 16151** (e.g.): First person wakes up
   - Person auto-registers with TimeManager
   - Person sends StartTrip to owned car
   - Car transitions from Parked to Start
   - Car calculates route and begins moving
3. **Tick 16151+T**: Car completes trip
   - Car reports TripCompleted to person
   - Person transitions to next activity
   - Person remains registered, waits for next trip
4. **Tick 86400**: End of simulation day
   - All persons complete schedules
   - All persons unregister from TimeManager
   - Simulation complete

## Migration Notes

This configuration is the result of fixing synchronization issues:

1. **Python Script** (`migrate_to_hybrid.py`):
   - Changed: Person generation sets `scheduleOnTimeManager: False` (not `True`)
   - Changed: Person generation uses `firstActivityStartTick` (not `startTick`)
   - Changed: Owned car generation sets `scheduleOnTimeManager: False` explicitly

2. **Scala Code** (`Person.scala`):
   - Added: `isRegisteredWithTimeManager` flag
   - Added: `preStart()` comment about auto-registration
   - Added: Auto-registration logic in `actSpontaneous()`
   - Added: `getTickUntilActivityEnd()` helper for smart rescheduling

3. **Scala Code** (`Car.scala`):
   - Already had: Null guards in `getVehicleStatus()`, `actInteractWith()`, `actSpontaneous()`
   - These guards now work correctly because Person doesn't send StartTrip until Car is ready

## Timeline: Before vs After

### Before (Broken)
```
Tick 0: JSON loading starts
Tick 1: TimeManager ticks
  → Person receives tick (scheduleOnTimeManager: true)
  → Person sends StartTrip
  → Car.state is still null
  → NullPointerException ❌
```

### After (Fixed)
```
Tick 0: JSON loading starts
Tick 1+: JSON loading completes (all actors initialized)
Tick 2: TimeManager ticks
  → Only autonomous vehicles/actors receive ticks
  → Persons remain idle
Tick N: Person's first activity ready
  → Person receives spontaneous event (self-generated)
  → Person auto-registers with TimeManager
  → Person sends StartTrip
  → Car.state fully loaded ✓
  → Car can process StartTrip normally ✓
```

---

**Status**: ✅ Complete
**Last Updated**: February 16, 2026
**Author**: Hybrid Model Implementation Team
