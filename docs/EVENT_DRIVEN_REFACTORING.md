# Event-Driven Refactoring for Real Lookahead

## Executive Summary

**Current Problem:** Tick-driven model → lookahead ≈ 0 → no parallelism
**Solution:** Event-driven model → lookahead = travelTime → 10-100x speedup potential

---

## Part 1: Anti-Patterns That Destroy Lookahead

### Anti-Pattern #1: Generic Next-Tick Scheduling

**NEVER do this:**
```scala
// ❌ BAD: No causal reason to wake up
onFinishSpontaneous(Some(currentTick + 1))
```

**Why it's wrong:**
- Creates artificial dependency every tick
- Forces global synchronization
- Lookahead = 0 guaranteed

**What qualifies as valid event:**
- ✅ ArriveAtNode(tick = currentTick + travelTime)
- ✅ PhaseChange(tick = greenStart)
- ✅ PassengerLoadComplete(tick = currentTick + loadTime)

### Anti-Pattern #2: Synchronous Queries

**NEVER do this:**
```scala
// ❌ BAD: Blocking query every tick
requestSignalState()
waitForResponse()
```

**Why it's wrong:**
- Round-trip message blocks actor
- Other actors can't advance
- Creates synchronous dependency chain

**Correct approach:**
```scala
// ✅ GOOD: Time-bounded state snapshot
val signalState = link.getSignalStateValidUntil(currentTick + travelTime)
if (signalState.isGreen) {
  scheduleArrival(currentTick + travelTime)
} else {
  scheduleArrival(signalState.greenStartTick + travelTime)
}
```

### Anti-Pattern #3: Status Polling

**NEVER do this:**
```scala
// ❌ BAD: Wake up every tick to check status
override def actSpontaneous(event: SpontaneousEvent): Unit =
  state.status match
    case Moving => checkIfArrived()  // Wasteful polling
```

**Why it's wrong:**
- Actor wakes unnecessarily
- Simulates continuous time with discrete checks
- Creates message storm

**Correct approach:**
```scala
// ✅ GOOD: Schedule arrival once
case Ready =>
  val travelTime = link.calculateTravelTime(currentSpeed)
  scheduleEvent(ArriveAtNode, currentTick + travelTime)
```

---

## Part 2: Event-Driven Car Actor

### Current Implementation (Tick-Driven)

```scala
// ❌ BAD: Car.scala current
override def actSpontaneous(event: SpontaneousEvent): Unit =
  state.movableStatus match {
    case Moving =>
      state.distance += state.speed
      if (state.distance >= link.length) {
        arriveAtNode()
      } else {
        onFinishSpontaneous(Some(currentTick + 1))  // ❌ Poll next tick
      }
    case WaitingSignal =>
      requestSignalState()  // ❌ Synchronous query
      onFinishSpontaneous(Some(currentTick + 1))
  }
```

**Problems:**
- Wakes every tick regardless of events
- Synchronous signal queries
- No lookahead calculation

### Refactored Implementation (Event-Driven)

```scala
// ✅ GOOD: Event-driven Car
package model.mobility.actor.eventdriven

import core.types.Tick
import model.mobility.entity.event.vehicle._

class EventDrivenCar extends Movable[CarState] {
  
  // RULE: Only schedule when you KNOW something will happen
  override def actSpontaneous(event: SpontaneousEvent): Unit =
    state.movableStatus match {
      case Start =>
        requestRoute()  // One-time route calculation
        // No rescheduling - will be woken by route response
        
      case Ready =>
        enterLinkWithPrediction()
        // No rescheduling - will be woken by ArriveAtNode
        
      case Finished =>
        onFinishSpontaneous()  // Done forever
    }
  
  /** Event-driven link entry with travel time prediction */
  private def enterLinkWithPrediction(): Unit = {
    val (linkId, nodeId) = state.movableBestRoute.get.head
    val link = getDependency(linkId)
    
    // Request entry with travel time calculation
    sendMessageTo(
      link.id,
      link.classType,
      data = RequestEnterLinkData(
        vehicleId = getEntityId,
        entryTick = currentTick,
        destinationNode = nodeId
      )
    )
    
    state.movableStatus = WaitingLinkEntry
    // Actor sleeps until EnterLinkResponse arrives
  }
  
  /** Handle link entry confirmation with calculated travel time */
  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: EnterLinkConfirmData =>
        handleEnterLinkConfirm(event, d)
      
      case d: SignalStatePredictionData =>
        handleSignalPrediction(event, d)
        
      case _ => super.actInteractWith(event)
    }
  
  private def handleEnterLinkConfirm(
    event: ActorInteractionEvent,
    data: EnterLinkConfirmData
  ): Unit = {
    state.currentLink = data.linkId
    state.movableStatus = Moving
    
    // Calculate safe horizon based on physical travel time
    val travelTime = data.baseTravelTime  // From link's density model
    val arrivalTick = currentTick + travelTime
    
    // Check if signal will interfere
    data.signalState match {
      case Some(signal) if signal.willBeRed(arrivalTick) =>
        // Signal will be red when we arrive - wait for green
        val greenTick = signal.nextGreenTick
        val totalTime = (greenTick - currentTick) + travelTime
        
        // Schedule arrival after signal clears
        scheduleEvent(
          eventType = ArriveAtNode,
          tick = greenTick + travelTime,
          data = ArriveAtNodeData(
            nodeId = data.destinationNode,
            linkId = data.linkId
          )
        )
        
        // Report safe horizon for lookahead
        reportSafeHorizon(greenTick + travelTime)
        
      case _ =>
        // Clear path - schedule direct arrival
        scheduleEvent(
          eventType = ArriveAtNode,
          tick = arrivalTick,
          data = ArriveAtNodeData(
            nodeId = data.destinationNode,
            linkId = data.linkId
          )
        )
        
        // Report safe horizon for lookahead
        reportSafeHorizon(arrivalTick)
    }
    
    // Actor sleeps until arrivalTick
    onFinishSpontaneous(Some(arrivalTick))
  }
  
  /** Calculate safe horizon for TimeManager lookahead */
  override def calculateSafeHorizon(): Option[Tick] = {
    state.movableStatus match {
      case Moving =>
        // We know exactly when we'll arrive
        state.scheduledArrivalTick
        
      case WaitingLinkEntry =>
        // Conservative: wait for link response
        None  // Lookahead = 0 during coordination
        
      case Finished =>
        // No future events
        Some(Long.MaxValue)
        
      case _ =>
        None
    }
  }
}
```

**Key Changes:**
1. **No generic `currentTick + 1`** - only schedule arrival time
2. **Single event per link** - not continuous polling
3. **Travel time calculated once** - by link based on density
4. **Signal state predicted** - time-bounded snapshots
5. **Safe horizon reported** - enables lookahead

---

## Part 3: Event-Driven TrafficSignal

### Current Implementation (Tick-Driven)

```scala
// ❌ BAD: TrafficSignal.scala current
override def actSpontaneous(event: SpontaneousEvent): Unit =
  handlePhaseTransition(event.tick)  // Checks EVERY tick
  
  state.phases.foreach { phase =>
    val currentCycleTick = (currentTick + offset) % cycleDuration
    if (needsTransition(currentCycleTick, phase)) {
      notifyNodes(newState)
    }
    onFinishSpontaneous(Some(nextTickTime))  // ❌ Generic schedule
  }
```

**Problems:**
- Evaluates phase every tick (wasteful)
- Notifies nodes even if no change
- No prediction API for vehicles

### Refactored Implementation (Event-Driven)

```scala
// ✅ GOOD: Event-driven TrafficSignal
class EventDrivenTrafficSignal extends BaseActor[TrafficSignalState] {
  
  /** Initialize: schedule all phase transitions upfront */
  override def onInitialize(event: InitializeEvent): Unit = {
    super.onInitialize(event)
    
    // Pre-calculate all phase transitions for entire simulation
    scheduleAllPhaseTransitions()
  }
  
  /** Schedule phase changes for entire cycle */
  private def scheduleAllPhaseTransitions(): Unit = {
    val simulationEnd = getSimulationDuration
    var cycleTick = 0L
    
    while (cycleTick < simulationEnd) {
      state.phases.foreach { phase =>
        // Schedule green start
        val greenStartTick = cycleTick + phase.greenStart
        scheduleEvent(
          eventType = PhaseChange,
          tick = greenStartTick,
          data = PhaseChangeData(
            phaseId = phase.id,
            newState = Green,
            validUntil = greenStartTick + phase.greenDuration
          )
        )
        
        // Schedule green end (red start)
        val redStartTick = greenStartTick + phase.greenDuration
        scheduleEvent(
          eventType = PhaseChange,
          tick = redStartTick,
          data = PhaseChangeData(
            phaseId = phase.id,
            newState = Red,
            validUntil = cycleTick + state.cycleDuration
          )
        )
      }
      
      cycleTick += state.cycleDuration
    }
    
    // First event triggers first transition
    onFinishSpontaneous(Some(0L))
  }
  
  /** Execute phase transition (only when scheduled) */
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    // Retrieve scheduled phase change
    val phaseChange = state.scheduledPhases.dequeue()
    
    // Update internal state
    state.signalStates(phaseChange.phaseId) = SignalState(
      state = phaseChange.newState,
      validUntil = phaseChange.validUntil
    )
    
    // Notify nodes about change
    notifyNodes(phaseChange)
    
    // Schedule next phase transition (already queued)
    val nextTransition = state.scheduledPhases.headOption
    nextTransition.foreach { next =>
      onFinishSpontaneous(Some(next.tick))
    }
  }
  
  /** Provide time-bounded state snapshot for vehicle queries */
  def getSignalStatePrediction(
    phaseOrigin: String,
    queryTick: Tick,
    horizonTick: Tick
  ): SignalStatePrediction = {
    
    val currentState = state.signalStates(phaseOrigin)
    
    // Find next transition within horizon
    val nextTransition = state.scheduledPhases
      .find(t => t.phaseId == phaseOrigin && t.tick > queryTick && t.tick <= horizonTick)
    
    SignalStatePrediction(
      currentState = currentState.state,
      validUntil = nextTransition.map(_.tick).getOrElse(horizonTick),
      nextState = nextTransition.map(_.newState),
      nextTransitionTick = nextTransition.map(_.tick)
    )
  }
}

/** Time-bounded signal state for vehicle planning */
case class SignalStatePrediction(
  currentState: TrafficSignalPhaseStateEnum,
  validUntil: Tick,  // State guaranteed stable until this tick
  nextState: Option[TrafficSignalPhaseStateEnum],
  nextTransitionTick: Option[Tick]
) {
  def isGreenAt(tick: Tick): Boolean = {
    if (tick < validUntil) {
      currentState == Green
    } else {
      nextState.contains(Green)
    }
  }
  
  def nextGreenTick: Tick = {
    if (currentState == Green) validUntil
    else nextTransitionTick.getOrElse(Long.MaxValue)
  }
}
```

**Key Changes:**
1. **Schedule all transitions upfront** - O(1) per phase change
2. **No continuous checking** - only wake on scheduled events
3. **Predictive API** - vehicles can plan ahead
4. **Time-bounded guarantees** - enables safe speculation

---

## Part 4: Event-Driven Link

### Current Implementation (Tick-Driven)

```scala
// ❌ BAD: Link processes every vehicle every tick
override def actInteractWith(event: ActorInteractionEvent): Unit =
  event.data match {
    case d: EnterLinkData =>
      state.registered.add(vehicle)
      sendMessageTo(vehicle, LinkInfoData(...))
      // No travel time calculation - vehicle polls
```

**Problems:**
- No travel time provided
- Vehicle must poll for updates
- No density prediction

### Refactored Implementation (Event-Driven)

```scala
// ✅ GOOD: Event-driven Link
class EventDrivenLink extends BaseActor[LinkState] {
  
  override def actInteractWith(event: ActorInteractionEvent): Unit =
    event.data match {
      case d: RequestEnterLinkData =>
        handleEnterRequest(event, d)
      
      case d: LeaveLinkData =>
        handleVehicleLeave(event, d)
    }
  
  /** Calculate travel time and confirm entry */
  private def handleEnterRequest(
    event: ActorInteractionEvent,
    data: RequestEnterLinkData
  ): Unit = {
    val vehicle = event.toIdentity
    
    // Calculate travel time based on current density
    val density = state.registered.size.toDouble / state.capacity
    val currentSpeed = SpeedUtil.linkDensitySpeed(
      density = density,
      freeSpeed = state.freeSpeed,
      capacity = state.capacity
    )
    val travelTime = (state.length / currentSpeed).toLong
    
    // Register vehicle
    state.registered.add(LinkRegister(
      identity = vehicle,
      entryTick = data.entryTick,
      expectedLeaveTick = data.entryTick + travelTime
    ))
    
    // Get signal prediction if applicable
    val signalPrediction = state.signalId.map { signalId =>
      val signal = getDependency(signalId)
      // Request time-bounded state
      getSignalPrediction(
        signal,
        queryTick = data.entryTick,
        horizonTick = data.entryTick + travelTime
      )
    }
    
    // Confirm entry with travel time
    sendMessageTo(
      vehicle.id,
      vehicle.classType,
      data = EnterLinkConfirmData(
        linkId = getEntityId,
        entryTick = data.entryTick,
        baseTravelTime = travelTime,
        destinationNode = data.destinationNode,
        signalState = signalPrediction
      )
    )
  }
  
  /** Remove vehicle and update density */
  private def handleVehicleLeave(
    event: ActorInteractionEvent,
    data: LeaveLinkData
  ): Unit = {
    state.registered.removeIf(_.identity.id == data.vehicleId)
    
    // Density decreased - notify waiting vehicles if any
    if (state.waitingQueue.nonEmpty) {
      processWaitingVehicles()
    }
  }
}
```

**Key Changes:**
1. **Travel time calculated once** - based on current density
2. **Signal state provided** - time-bounded prediction
3. **No continuous updates** - vehicles don't need link state after entry
4. **Density tracking** - for realistic congestion

---

## Part 5: Safe Horizon Calculation

### TimeManager Integration

```scala
// TimeManager calculates global safe horizon
class EventDrivenTimeManager extends TimeManager {
  
  /** Collect safe horizons from all actors */
  private def calculateGlobalSafeHorizon(): Tick = {
    val localHorizons = localTimeManagers.map { ltm =>
      ltm.reportSafeHorizon()
    }
    
    // Global horizon = minimum of all local horizons
    val globalHorizon = localHorizons.min
    
    // Combined with window and lookahead
    val effectiveHorizon = Math.min(
      globalHorizon,
      currentTick + state.lookaheadWindow + state.windowSize
    )
    
    effectiveHorizon
  }
  
  /** Broadcast safe horizon to actors */
  private def broadcastSafeHorizon(horizon: Tick): Unit = {
    localTimeManagers.foreach { ltm =>
      ltm ! UpdateSafeHorizon(horizon)
    }
  }
}

// Local TimeManager aggregates actor horizons
class LocalTimeManager {
  
  def reportSafeHorizon(): Tick = {
    val actorHorizons = managedActors.flatMap { actor =>
      actor.calculateSafeHorizon()
    }
    
    if (actorHorizons.isEmpty) {
      currentTick + 1  // Conservative: no information
    } else {
      actorHorizons.min  // Earliest event in this shard
    }
  }
}
```

---

## Part 6: Determinism Guarantees

### Why Event-Driven Model is Still Deterministic

**Concern:** Won't asynchronous events break determinism?

**Answer:** No, because:

1. **Lamport Clocks Enforce Causality:**
   ```scala
   // Event ordering preserved
   if (event1.tick < event2.tick) {
     // event1 processed first, always
   } else if (event1.tick == event2.tick) {
     // Tie-breaking by actor ID (deterministic)
     if (event1.actorId < event2.actorId) {
       // event1 first
     }
   }
   ```

2. **Time-Bounded State Snapshots Are Deterministic:**
   ```scala
   // Signal state at tick T is deterministic
   val state = signal.getStateAt(tick = T)
   // Always returns same result for same T
   ```

3. **Travel Time Calculations Are Deterministic:**
   ```scala
   // Same density → same speed → same travel time
   val density = vehicles / capacity
   val speed = f(density)  // Pure function
   val time = distance / speed  // Deterministic
   ```

4. **Event Scheduling Is Deterministic:**
   ```scala
   // Same input → same schedule
   scheduleEvent(tick = currentTick + travelTime)
   // travelTime is deterministic
   ```

### Why Event-Driven Is More Realistic

**Tick-driven model:**
- Samples continuous motion at discrete intervals
- Can miss events between ticks
- Artificial synchronization points

**Event-driven model:**
- Models actual causal events
- No artificial sampling
- Continuous time between events
- More accurate congestion dynamics

---

## Part 7: Implementation Checklist

### Phase 1: Refactor Core Actors ⬜

- [ ] **EventDrivenCar**
  - [ ] Remove `onFinishSpontaneous(Some(currentTick + 1))`
  - [ ] Implement `enterLinkWithPrediction()`
  - [ ] Add `calculateSafeHorizon()`
  - [ ] Handle `EnterLinkConfirmData`
  
- [ ] **EventDrivenTrafficSignal**
  - [ ] Pre-schedule all phase transitions
  - [ ] Implement `getSignalStatePrediction()`
  - [ ] Remove continuous phase checking
  
- [ ] **EventDrivenLink**
  - [ ] Calculate travel time on entry
  - [ ] Provide signal predictions
  - [ ] Track expected leave times

### Phase 2: Safe Horizon Infrastructure ⬜

- [ ] **Actor-Level Horizon**
  - [ ] `calculateSafeHorizon()` in BaseActor
  - [ ] `reportSafeHorizon()` to LocalTM
  
- [ ] **LocalTimeManager**
  - [ ] Aggregate actor horizons
  - [ ] Report to GlobalTM
  
- [ ] **GlobalTimeManager**
  - [ ] Calculate global horizon
  - [ ] Broadcast to all actors

### Phase 3: Testing & Validation ⬜

- [ ] **Determinism Tests**
  - [ ] Same seed → same output (tick vs event)
  - [ ] Verify event ordering
  
- [ ] **Performance Tests**
  - [ ] Measure lookahead values
  - [ ] Compare throughput (tick vs event)
  
- [ ] **Correctness Tests**
  - [ ] Vehicle arrival times
  - [ ] Signal phase accuracy
  - [ ] Congestion realism

---

## Part 8: Common Pitfalls

### Pitfall #1: Hidden Tick Scheduling

```scala
// ❌ WRONG: Hidden in utility function
def updatePosition(): Unit = {
  position += speed
  if (!arrived) {
    onFinishSpontaneous(Some(currentTick + 1))  // ❌ Sneaky!
  }
}
```

**Fix:** Schedule final arrival time upfront.

### Pitfall #2: Synchronous Dependencies

```scala
// ❌ WRONG: Blocking wait
val signalState = Await.result(signal ? GetState, 1.second)
```

**Fix:** Use time-bounded predictions, no blocking.

### Pitfall #3: Non-Deterministic Travel Time

```scala
// ❌ WRONG: Random variation
val travelTime = baseTime * (1.0 + Random.nextDouble() * 0.1)
```

**Fix:** Use seeded random or deterministic function.

### Pitfall #4: Incomplete Horizon Reporting

```scala
// ❌ WRONG: Forget to report horizon
scheduleEvent(tick = futureTime)
// Missing: reportSafeHorizon(futureTime)
```

**Fix:** Always report horizon after scheduling.

---

## Part 9: Expected Performance Improvements

### Lookahead Analysis

**Tick-Driven (current):**
```
Average lookahead ≈ 0 ticks
Barriers = every 1 tick
CPU efficiency = 10-15% (1000-1200% on 112 cores)
```

**Event-Driven:**
```
Average lookahead ≈ travelTime / timeStep
Example: 100m link @ 50 km/h = ~7 seconds = 70 ticks
Barriers = every 70 ticks (70x reduction!)
CPU efficiency = 50-70% (6000-8000% on 112 cores)
```

### Combined with Window Execution

```
Event-Driven + Window(50) + Lookahead(50):
  Effective horizon = 2500 ticks
  Expected speedup = 50-100x over baseline
  CPU utilization = 8000-11000%
```

---

## Part 10: Migration Strategy

### Incremental Rollout

**Week 1:** Refactor TrafficSignal (independent component)
**Week 2:** Refactor Link (provide travel time API)
**Week 3:** Refactor Car (use new APIs)
**Week 4:** Testing and validation
**Week 5:** Extend to Bus, Subway, etc.

### Backward Compatibility

```scala
// Support both modes during migration
trait Movable {
  def usesEventDrivenModel: Boolean = false
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    if (usesEventDrivenModel) {
      actSpontaneousEventDriven(event)
    } else {
      actSpontaneousTickDriven(event)  // Legacy
    }
  }
}
```

---

## Conclusion

### What Must NEVER Be Scheduled

❌ `onFinishSpontaneous(Some(currentTick + 1))`
❌ Generic "check status" events
❌ Polling loops
❌ Synchronous queries

### What Qualifies as Valid Causal Event

✅ ArriveAtNode (physical travel time)
✅ PhaseChange (signal schedule)
✅ PassengerLoadComplete (loading time)
✅ ReachDestination (route completion)

### Why This Is Better

1. **Higher lookahead** - vehicles know arrival times
2. **Less message traffic** - no continuous polling
3. **More realistic** - models actual causality
4. **Still deterministic** - Lamport clocks + pure functions
5. **Scales better** - 50-100x speedup potential

---

**Next Steps:**
1. Review this document with team
2. Start with TrafficSignal refactoring (simplest)
3. Measure lookahead improvements
4. Extend to other actors incrementally
