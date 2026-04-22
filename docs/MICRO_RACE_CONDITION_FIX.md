# Micro-Simulation Race Condition Fix

**Date:** April 21, 2026  
**JAR:** `02cba2ba886e88aaba61777c36c51b482b1a8593`  
**Status:** ✅ All 4 simple scenarios pass

---

## Problem Statement

The HTC micro-simulation exhibited two distinct bugs:

1. **Premature termination (s4 stuck at 8/40 vehicles):** In scenarios with multiple micro links, some vehicles never completed their journey. The simulation terminated while vehicles were still in transit.
2. **Container never exiting (s1–s3):** Even when all vehicles completed their journey, the Docker container ran indefinitely until the timeout (300s), instead of exiting cleanly.

---

## Root Cause Analysis

### Bug 1 — Premature termination in multi-link scenarios

**Location:** `src/main/scala/model/hybrid/actor/Link.scala` → `actSpontaneous`

**Mechanism:**

Each micro link acts as a local time manager, scheduling itself tick-by-tick for as long as it has vehicles. When a link becomes empty, it calls `onFinishSpontaneous(None)` to deregister from the TM pool.

The grace period (`MICRO_GRACE_TICKS = 5`) was applied **only** in the pre-tick path — i.e., when the link was already empty at the *start* of `actSpontaneous`. It was **not** applied in the post-tick path — i.e., when the *last vehicle exited during `handleGlobalTick`*.

```
actSpontaneous called:
  ├── PRE-TICK: hasVehicles=false → grace period applied ✅ (was already fixed)
  └── POST-TICK: hasVehiclesAfterTick=false → onFinishSpontaneous(None) immediately ❌
```

**Consequence:** When the last vehicle exited a link during tick processing, the link immediately deregistered. But the *next* vehicle (already in-flight in the actor mailbox) would call `scheduleEvent(currentTick+1)` on a deregistered link. The TM had already reported `hasScheduled=false` to GlobalTM, which then checked all TMs, found none had pending work, and terminated the simulation — while 32 vehicles were still traversing other links.

**Evidence from reports** (s4, prior to fix):
```
link_0: micro_enter=40, micro_leave=39
link_1: micro_enter=40, micro_leave=40
link_2: micro_enter=40, micro_leave=23
link_3: micro_enter=24, micro_leave=3
journey_completed=8, max_tick=179
```
Car_24 was the last event at tick 179 — it entered link_3, which then immediately deregistered.

---

### Bug 2 — Container never exiting

**Location:** `src/main/scala/core/actor/manager/time/GlobalTimeManager.scala` → `terminateSimulation()`

**Mechanism:**

In `HyperbolicTimeChamber.start()`, the actor system is created but `Await.result(system.whenTerminated, ...)` is never called. The application thread returns immediately after starting cluster bootstrap, and the JVM is kept alive solely by non-daemon Pekko threads.

Pekko's `CoordinatedShutdown.exit-jvm` defaults to `false`, so the actor system shutting down does not trigger a JVM exit. Without an explicit `System.exit()` or `system.terminate()` followed by `Await`, the container runs indefinitely after the simulation ends.

**Previous behavior:** `terminateSimulation()` sent `StopSimulationEvent` to all local managers and `SimulationManager`, then returned — leaving the JVM alive.

---

## Fixes Applied

### Fix 1 — Extended grace period to post-tick empty case

**File:** [src/main/scala/model/hybrid/actor/Link.scala](../src/main/scala/model/hybrid/actor/Link.scala)

```scala
val hasVehiclesAfterTick = state.totalVehiclesInMicro > 0
if (hasVehiclesAfterTick) {
  emptyGraceTick = 0
  onFinishSpontaneous(Some(currentTick + 1))
} else {
  // Apply the same grace period when the last vehicle exits DURING tick processing.
  // Without this, the link calls onFinishSpontaneous(None) immediately, but an
  // incoming vehicle (whose EnterLinkData message is already in-flight) will call
  // scheduleEvent() AFTER the TM has already reported hasScheduled=false to GlobalTM,
  // causing GlobalTM to terminate prematurely while vehicles are still in transit.
  emptyGraceTick += 1
  if (emptyGraceTick <= MICRO_GRACE_TICKS) {
    onFinishSpontaneous(Some(currentTick + 1))
  } else {
    emptyGraceTick = 0
    microTickScheduled = false
    onFinishSpontaneous(None)
  }
}
```

The grace period (`MICRO_GRACE_TICKS = 5`) now applies symmetrically to both the pre-tick and post-tick empty cases. The `emptyGraceTick` counter is reset whenever vehicles are present.

---

### Fix 2 — Explicit JVM exit via CoordinatedShutdown

**File:** [src/main/scala/core/actor/manager/time/GlobalTimeManager.scala](../src/main/scala/core/actor/manager/time/GlobalTimeManager.scala)

Added import:
```scala
import org.apache.pekko.actor.{ActorRef, CoordinatedShutdown, Props, Terminated}
```

In `terminateSimulation()`:
```scala
private def terminateSimulation(): Unit = synchronized {
  if (!isTerminated) {
    isTerminated = true
    printSimulationDuration()
    logInfo("Global simulation terminated")
    notifyLocalManagers(StopSimulationEvent())
    simulationManager ! StopSimulationEvent()
    // Explicitly shut down the actor system so the JVM (container) exits.
    // Without this, non-daemon threads keep the JVM alive indefinitely.
    CoordinatedShutdown(context.system).run(CoordinatedShutdown.JvmExitReason)
  }
}
```

`JvmExitReason` triggers the coordinated shutdown phases and then calls `System.exit(0)`, causing the container to exit cleanly.

---

## Related Fixes (Prior Sessions)

These fixes were applied in preceding sessions and remain active:

### TM Race Condition — Atomic actor scheduling in `finishEvent`

**File:** `src/main/scala/core/actor/manager/time/LocalTimeManagerBase.scala`

When an actor sends a `FinishEvent` with `scheduleTick=Some(N)`, the actor is now inserted directly into `scheduledActors[N]` within `finishEvent`, atomically. Previously, a separate `ScheduleEvent(N)` message was sent, which could arrive after `UpdateGlobalTimeEvent(T)` had already been processed, causing the actor to be bumped to a wrong tick.

### Double-Scheduling Prevention — ScheduleEvent removed from `onFinishSpontaneous`

**File:** `src/main/scala/core/actor/SimulationBaseActor.scala`

`onFinishSpontaneous` no longer sends a separate `ScheduleEvent`. The TM fix (above) handles scheduling atomically within `finishEvent`, so sending an additional `ScheduleEvent` would cause actors to be scheduled twice.

---

## Test Results

Scenarios run with `scripts/run_htc_simple_scenarios.sh --skip-build --skip-generate --skip-sumo --scenarios 1 2 3 4`:

| Scenario | Expected | Completed | Distance | Container Exit | Result |
|---|---|---|---|---|---|
| simple_1_street | 10 | 10 | 500m | ✅ ~20s | **PASS** |
| simple_2_streets | 20 | 20 | 1000m | ✅ ~20s | **PASS** |
| simple_3_streets | 30 | 30 | 1500m | ✅ ~20s | **PASS** |
| simple_4_streets | 40 | 40 | 2000m | ✅ ~20s | **PASS** |

Script output:
```
[09:31:51]   journey_completed events so far: 10
[09:32:12] Container exited — simulation complete.
[09:32:12] HTC passed for simple_1_street: container exited cleanly (expecting 10 journey_completed events)
[09:32:17]   journey_completed events so far: 20
[09:32:37] Container exited — simulation complete.
[09:32:37] HTC passed for simple_2_streets: container exited cleanly (expecting 20 journey_completed events)
[09:32:43]   journey_completed events so far: 30
[09:33:02] Container exited — simulation complete.
[09:33:02] HTC passed for simple_3_streets: container exited cleanly (expecting 30 journey_completed events)
[09:33:07]   journey_completed events so far: 40
[09:33:27] Container exited — simulation complete.
[09:33:27] HTC passed for simple_4_streets: container exited cleanly (expecting 40 journey_completed events)
```
