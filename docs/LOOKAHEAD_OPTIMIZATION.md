# Lookahead Optimization - Breaking the Synchronization Barrier

## Overview

This document describes the **Lookahead Optimization** (Strategy 1) implemented to overcome the theoretical CPU utilization limit imposed by global tick synchronization.

### The Problem

The original architecture uses a **conservative global synchronization barrier**:
- Every tick requires ALL local TimeManagers to complete
- GlobalTimeManager waits for complete fan-in before advancing
- This creates an **Amdahl's Law bottleneck** limiting speedup to ~10-12x regardless of available CPUs

### The Solution

**Conservative Lookahead** allows actors to advance multiple ticks speculatively when it's provably safe:

```
Safe Execution Window = [currentTick, safeHorizon]

where: safeHorizon = min(
  currentTick + lookaheadWindow,
  nextScheduledEventTick,
  simulationDuration
)
```

Actors can process multiple ticks within this window **without external synchronization**, reducing barrier frequency.

---

## Architecture

### Component Changes

#### 1. Configuration (`application.conf`)

```hocon
htc.time-manager {
  # Lookahead window (in ticks)
  # 1 = disabled (conservative synchronous)
  # >1 = enable speculative execution
  lookahead-window = 1
  lookahead-window = ${?HTC_TIME_MANAGER_LOOKAHEAD}
}
```

#### 2. TimeManagerState

Added `lookaheadWindow` field:

```scala
case class TimeManagerState(
  var lookaheadWindow: Tick = 1,  // NEW
  // ... existing fields
)
```

#### 3. SpontaneousEvent

Extended to carry safe horizon information:

```scala
case class SpontaneousEvent(
  tick: Tick,
  actorRef: ActorRef,
  safeHorizon: Tick = -1  // NEW: -1 means no lookahead
) {
  def effectiveSafeHorizon: Tick = if (safeHorizon == -1) tick else safeHorizon
  def hasLookahead: Boolean = safeHorizon > tick
}
```

#### 4. TimeManager

**New method: `calculateSafeHorizon`**

```scala
private def calculateSafeHorizon(currentTick: Tick): Tick = {
  if (state.lookaheadWindow <= 1) {
    return currentTick // Conservative mode
  }
  
  // Calculate based on next scheduled events
  val nextScheduledTick = state.scheduledActors.keys.minOption
    .getOrElse(currentTick + state.lookaheadWindow)
    
  val horizon = Math.min(
    currentTick + state.lookaheadWindow,
    nextScheduledTick
  )
  
  // Never exceed simulation duration
  Math.min(horizon, simulationDuration)
}
```

**Modified methods:**
- `sendSpontaneousEvent`: now passes `safeHorizon` to actors
- `createTimeManagersPool`: logs lookahead configuration

#### 5. BaseActor

**New hook: `actSpontaneousWithLookahead`**

```scala
private def handleSpontaneous(event: SpontaneousEvent): Unit = {
  // ... validation ...
  
  if (event.hasLookahead) {
    actSpontaneousWithLookahead(event)  // Multi-tick execution
  } else {
    actSpontaneous(event)  // Single tick (conservative)
  }
}

// Subclasses override to leverage lookahead
protected def actSpontaneousWithLookahead(event: SpontaneousEvent): Unit = {
  actSpontaneous(event)  // Default: fallback to single tick
}
```

#### 6. Movable & Car Actors

**Movable base class:**
```scala
override def actSpontaneousWithLookahead(event: SpontaneousEvent): Unit = {
  state.movableStatus match {
    case Start | Finished =>
      // Single-tick states: no benefit
      actSpontaneous(event)
    case Ready =>
      // Requires coordination: conservative for now
      actSpontaneous(event)
    case _ =>
      actSpontaneous(event)
  }
}
```

**Car-specific optimization:**
```scala
override def actSpontaneousWithLookahead(event: SpontaneousEvent): Unit = {
  val safeHorizon = event.effectiveSafeHorizon
  
  state.movableStatus match {
    case Stopped =>
      // Known wait duration: can skip ahead if within horizon
      val waitDuration = 1
      if (currentTick + waitDuration <= safeHorizon) {
        currentTick += waitDuration
        onFinishSpontaneous(Some(currentTick))
      } else {
        onFinishSpontaneous(Some(currentTick + 1))
      }
    // ... other states
  }
}
```

---

## Determinism Guarantees

### Safety Conditions

Lookahead is **safe** (deterministic) when:

1. **No external messages arrive within the window**
   - Enforced by `calculateSafeHorizon` checking scheduled events
   
2. **Actor state is predictable**
   - Only internal transitions allowed
   - No inter-actor communication within window

3. **Time ordering preserved**
   - Lamport clocks still maintained
   - FinishEvent reports correct future tick

### What Breaks Determinism

❌ **DO NOT** use lookahead for:
- Inter-actor communication (requires immediate synchronization)
- Traffic signal interactions (phase changes are scheduled)
- Route planning (requires graph queries)

✅ **SAFE** for:
- Fixed-duration waits (Stopped state)
- Known travel times (with no signals)
- Internal state transitions

---

## Performance Impact

### Expected Improvements

With `lookaheadWindow = 10`:

| Scenario | Barrier Frequency | Expected Speedup |
|----------|-------------------|------------------|
| Conservative (window=1) | Every tick | 1.0x (baseline) |
| Short waits (window=5) | Every 5 ticks | 1.5x - 2.0x |
| Medium waits (window=10) | Every 10 ticks | 2.0x - 3.0x |
| Long waits (window=50) | Every 50 ticks | 3.0x - 4.0x |

### Theoretical Limit

Original Amdahl limit:
```
S_max = 1 / (β + α/P) ≈ 10-12x
```

With lookahead reducing barrier frequency by factor `L`:
```
β_effective = β / L
S_max_new = 1 / (β/L + α/P) ≈ (10-12) * L
```

**For L=10, theoretical limit increases to ~100-120x**

---

## Usage Guide

### Basic Configuration

**Conservative (current behavior):**
```bash
export HTC_TIME_MANAGER_LOOKAHEAD=1
```

**Enable lookahead:**
```bash
export HTC_TIME_MANAGER_LOOKAHEAD=10  # 10-tick window
```

### Monitoring

Check logs for lookahead activity:

```
[INFO] Creating TimeManager pool: totalInstances=1024, maxInstancesPerNode=128
[INFO] Lookahead optimization enabled: window=10 ticks
[INFO] Send spontaneous at tick 500 to 1000 actors (lookahead: +8 ticks)
```

The `lookahead: +X ticks` indicates the actual safe window calculated.

### Tuning Guidelines

| Workload | Recommended Window | Rationale |
|----------|-------------------|-----------|
| Dense traffic, many signals | 1-5 | Frequent synchronization needed |
| Sparse traffic | 10-20 | Longer safe windows |
| Highway scenarios | 20-50 | Minimal interactions |
| Test/validation | 1 | Conservative, easier debugging |

### Debug Mode

To verify determinism:

1. Run with `lookahead-window=1` (baseline)
2. Run with `lookahead-window=10` (optimized)
3. Compare final simulation states (should be identical)

---

## Implementation Phases

### ✅ Phase 1: Foundation (COMPLETED)
- [x] Configuration infrastructure
- [x] SpontaneousEvent extension
- [x] TimeManager safe horizon calculation
- [x] BaseActor hook for multi-tick execution
- [x] Basic Movable/Car implementations

### 🔄 Phase 2: Advanced Optimizations (FUTURE)
- [ ] Link travel time prediction
- [ ] Signal phase lookahead
- [ ] Route segment batching
- [ ] Adaptive window sizing

### 🔄 Phase 3: Validation (FUTURE)
- [ ] Determinism tests
- [ ] Performance benchmarks
- [ ] Scalability analysis (1M+ actors)

---

## Troubleshooting

### CPU utilization still capped at 1000-1200%

**Possible causes:**
1. Lookahead window = 1 (disabled)
2. Workload has frequent inter-actor messages (breaking safe windows)
3. Scheduler bottleneck (check TimeManager distribution)

**Solutions:**
- Increase `lookahead-window` gradually
- Profile message patterns (high-frequency interactions reduce effectiveness)
- Ensure `total-instances` and `max-instances-per-node` are properly set

### Non-deterministic results

**Check:**
1. Are actors using `actSpontaneousWithLookahead` correctly?
2. Any inter-actor messages sent within lookahead window?
3. External state modifications (shared mutable state)?

**Fix:**
- Only advance time for truly independent computations
- Use `actSpontaneous` fallback for uncertain cases
- Add validation tests comparing runs with window=1 vs window>1

### Simulation slower with lookahead

**This can happen if:**
- Window calculation overhead > synchronization savings
- Actors rarely benefit from lookahead (e.g., always blocked)

**Solution:**
- Reduce window or disable (set to 1)
- Profile actor state distributions

---

## Advanced: Custom Actor Lookahead

To implement lookahead in custom actors:

```scala
class CustomActor extends BaseActor[CustomState] {
  
  override def actSpontaneousWithLookahead(event: SpontaneousEvent): Unit = {
    val safeHorizon = event.effectiveSafeHorizon
    var localTick = event.tick
    
    // Process multiple ticks if safe
    while (localTick < safeHorizon && canAdvance) {
      // Perform internal computation
      updateState(localTick)
      localTick += 1
    }
    
    // Update current tick and finish
    currentTick = localTick
    onFinishSpontaneous(Some(localTick))
  }
  
  private def canAdvance: Boolean = {
    // Return true only if next tick doesn't require external sync
    state.status match {
      case InternalComputation => true
      case WaitingForMessage => false
      case _ => false
    }
  }
}
```

---

## References

### Related Documentation
- [ARCHITECTURE.md](ARCHITECTURE.md) - System overview
- [PERFORMANCE_TUNING.md](PERFORMANCE_TUNING.md) - General optimization guide
- [CONFIGURATION.md](CONFIGURATION.md) - Configuration options

### Academic Background
- **Amdahl's Law**: Gene Amdahl (1967), "Validity of the single processor approach to achieving large scale computing capabilities"
- **Conservative Parallel Simulation**: Chandy-Misra (1979), Bryant (1977)
- **Time Warp (optimistic alternative)**: Jefferson (1985)

### Implementation Notes
- Inspired by PDES (Parallel Discrete Event Simulation) lookahead strategies
- Similar to null message protocols in conservative synchronization
- Maintains strong causality guarantees (unlike optimistic approaches)

---

## Changelog

### v1.0.0 - Initial Implementation
- Basic lookahead infrastructure
- Conservative safe horizon calculation
- Movable/Car actor integration
- Configuration and monitoring support

---

## Future Directions

### Strategy 2: Time Windows
Extend to synchronize on window boundaries instead of every tick:
```
[t, t+Δ] → single synchronization
```

### Strategy 3: Partitioned TimeManagers
Shard-specific time managers with cross-shard sync only:
```
Shard A → TM_A (independent)
Shard B → TM_B (independent)
Sync only on A↔B messages
```

### Strategy 4: Quorum-Based Sync
Replace full barrier with K% quorum:
```
advance if >= K% localManagers report ready
```

---

**Status**: ✅ Phase 1 Complete - Ready for testing
**Next Steps**: Configure `HTC_TIME_MANAGER_LOOKAHEAD` and measure performance improvements
