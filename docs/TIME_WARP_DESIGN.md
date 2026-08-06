# Time Warp (Optimistic Synchronization with Rollback) — Design

Status as of 2026-08-06: **all five "Suggested order" steps below have landed, each fully or
deliberately scoped** — §8 (RNG determinism), §2 (Conservative/GlobalTimeManagerBase extraction),
§6/§7 (`RollbackHistoryHandler`), §2/§3/§11 (`OptimisticLocalTimeManager`/`OptimisticGlobalTimeManager`
/GVT), and §10 (anti-message cascade math) — see "Implementation log" below for what's real vs.
scoped in each. Started on §10's post-suggested-order "not live yet" list too: `RollbackHistoryHandler` is now
composed into `SimulationBaseActor`, and `rollbackTo` is now genuinely safe and correct to call
(the `actSpontaneous`/`actInteractWith` replay-safety gap found mid-step is solved via an
`isReplaying` flag) — see that section's two follow-up log entries. **Time Warp is still not
runnable**: nothing calls `rollbackTo` yet (the straggler trigger itself isn't activated), and the
wire protocol (`ActorInteractionEvent`'s `messageId`/`isAntiMessage`) is untouched, so even once
triggered, a rollback's anti-messages have nowhere real to go. This document is the design
log/rationale from the planning conversation that produced it — update it as decisions are
revisited or implementation diverges, don't treat it as aspirational once code lands (same
convention as `docs/CONGESTION_PROPAGATION_DESIGN.md`).

## Motivation

HTC's time control today has two execution strategies, both **conservative**: an actor is only
dispatched for a tick once it's safe to do so, and a `LocalTimeManagerBase` only advances to the
next tick once every actor it dispatched has resolved (`runningEvents` empties). This guarantees
causal order by construction, at the cost of parallelism — the simulator can't get ahead of itself
even in regions of the network where nothing conflicts.

Time Warp (Jefferson, 1985) is the classic alternative: let actors run ahead **optimistically**,
and when a "straggler" message arrives with a timestamp behind what an actor has already
processed, **roll back** that actor to before the violation and **anti-message** any outbound
effects that are now invalid, cascading as needed. This document designs how to add Time Warp as a
third, selectable execution mode, reusing as much of HTC's existing actor/messaging/snapshot
infrastructure as holds up, and building new infrastructure only where nothing suitable exists.

## Current infrastructure (surveyed 2026-08-05)

Summarized from a full-codebase investigation; see file paths for detail.

- **TimeManager hierarchy** (`core/actor/manager/time/`): `TimeManagerBase` → `GlobalTimeManager`
  (cluster-wide singleton, barrier coordinator) and `LocalTimeManagerBase` →
  `LocalDiscreteEventTimeManager` (active default) / `LocalTimeSteppedTimeManager` (exists but not
  wired into the pool — `GlobalTimeManager.createTimeManagersPool` hardcodes
  `LocalDiscreteEventTimeManager.props`). `TimeManagerTypeEnum` has only `discrete-event` and
  `time-stepped`. Both existing strategies are conservative: advance only when `runningEvents`
  empties.
- **Actor state**: `BaseState` is a plain current-state container, no history. The only existing
  snapshot mechanism is `BaseActor.buildMigrationSnapshot()`/`applyMigrationSnapshot()`
  (`BaseActor.scala`), which serializes `state` to JSON plus actor-local reply-obligation fields
  (overridden per concrete actor — see Gaps A/B in `docs/KNOWN_GAPS.md`). It's single-slot,
  triggered only during shard-migration prep, stored via the cluster-singleton `SnapshotManager`
  (in-memory or Redis-backed).
- **Messaging**: `sendMessageTo` wraps payloads in `ActorInteractionEvent` (tick, `lamportTick`,
  sender identity, payload) and routes via cluster sharding or actor selection. `LamportClock`
  tracks causal order; `SimulationBaseActor.handleInteractWith` enforces `currentTick` is
  monotonically non-decreasing per actor on every interaction. No anti-message, cancellation, or
  undo concept exists anywhere today.
- **Pekko persistence is dead code**: `BaseActor.save()`'s real `persist(...)` body is commented
  out; the actual save/restore path in active use is the hand-rolled `MigrationSnapshot`/
  `SnapshotManager` protocol, not `pekko-persistence-*`.
- **Known cautionary precedent**: `docs/KNOWN_GAPS.md`'s "Gap C" documents that today's
  `buildMigrationSnapshot` capture is **not** race-free against the source actor continuing to
  mutate state afterward, because migration prep doesn't pause the source entity. Time Warp's own
  checkpointing must not inherit this — see "Checkpointing" below for why the actor capturing its
  *own* snapshot synchronously, rather than being asked to by another actor asynchronously, avoids
  the same race.

## Decisions

### 1. Scope: one synchronization mode per simulation run (v1)

Time Warp is a third, mutually-exclusive execution mode alongside `discrete-event`/`time-stepped`
— **not** mixed with conservative LTMs in the same run. A federated conservative/optimistic model
(breathing-time-buckets, YAWNS-style hybrids) exists in the literature but adds a second
cross-protocol synchronization problem on top of Time Warp itself; deferred indefinitely, not
planned for any specific phase.

### 2. Manager hierarchy: split Conservative/Optimistic at both Global and Local level

Rejected: a single `GlobalTimeManager` with an injected GVT-coordination component, because v1's
"one mode per run" decision means that component would never actually run alongside the barrier
logic — it would just be dead weight and `if (mode == ...)` branching for a mode that never
switches at runtime. Splitting by type is more honest and mirrors the Local side.

```
LocalTimeManagerBase (abstract, existing)
  ├── ConservativeLocalTimeManager (abstract, NEW — extracted, no behavior change)
  │     ├── LocalDiscreteEventTimeManager   (unchanged)
  │     └── LocalTimeSteppedTimeManager     (unchanged)
  └── OptimisticLocalTimeManager (abstract, NEW — Time Warp base)
        — dispatches without waiting for a barrier
        — owns each actor's rollback trigger point (via highestProcessedTick)
        — reports LVT to the GVTCoordinator asynchronously, non-blocking

GlobalTimeManagerBase (abstract, NEW — extracted common cluster/actor-registration/
                        migration-pause/progressive-loading plumbing, no behavior change)
  ├── ConservativeGlobalTimeManager (current GlobalTimeManager behavior: SelectiveBarrier,
  │     handleLocalTimeReport/calculateAndBroadcastNextGlobalTick, QueryNextTickEvent probe)
  └── OptimisticGlobalTimeManager (NEW: GVTCoordinator, termination detection — see below)
```

`createTimeManagersPool` (currently hardcoded) and the GTM singleton `Props` selection both need
to become config-driven factories choosing the right concrete class — a fix needed regardless of
Time Warp, since it's also what's silently preventing `LocalTimeSteppedTimeManager` from ever
running today.

### 3. GVT: pluggable estimation strategy, margin-based for v1

GVT (global virtual time) = the lowest timestamp of anything still unprocessed or in flight;
required for safe fossil collection (pruning history/anti-message state that can never be needed
again) and for safe commit of irreversible side effects. Computed via composition, not subclassing
`OptimisticLocalTimeManager`/`OptimisticGlobalTimeManager` — the rollback/history logic is
identical regardless of how GVT is estimated, only the aggregation math differs:

```scala
trait GVTEstimationStrategy {
  def estimate(localReports: Seq[LocalVirtualTimeReport]): Tick
}
class MarginBasedGVTEstimation(margin: Tick) extends GVTEstimationStrategy   // v1
class MatternChannelCountingGVTEstimation(...) extends GVTEstimationStrategy // deferred
```

- **v1: margin-based** — `GVT = min(reported LVTs) - margin`. Safe because underestimating GVT is
  always correct (delays fossil collection, never causes it to discard something still needed);
  overestimating is the only unsafe direction, and a large-enough margin covers plausible
  in-transit message lag. Simple, no new per-channel counting state. Cost: retains more
  history/memory than strictly necessary, by an amount proportional to the margin.
- **Deferred: exact (Mattern-style channel counting)** — per-LTM-pair send/receive counters +
  periodic token round to eliminate in-transit uncertainty exactly. Correct with no wasted
  retention, but real coordination-protocol complexity. Revisit once the margin-based approach's
  actual memory cost is measured against a real workload.
- GVT reporting from each `OptimisticLocalTimeManager` must be **asynchronous and non-blocking**
  — actors keep dispatching while the LTM's LVT is being aggregated; GVT is a background watermark,
  not a gate on forward progress (that gate is exactly what conservative mode already does, and
  what Time Warp exists to avoid).

### 4. Irreversible side effects (`report()`): buffered until GVT passes

Every `report()` call under an `OptimisticLocalTimeManager` is buffered per actor/tick rather than
emitted immediately. The `GVTCoordinator`'s watermark, once it passes tick T, flushes everything
buffered up to T. This piggybacks on the same "safe to act" signal that authorizes fossil
collection — both are "things safe to do once nothing before T can ever be rolled back."

### 5. Rollback unit: actor (LP), scoped to simulation entities only

One LP per HTC actor, matching the platform's existing per-entity isolation. Scope is deliberately
narrower than "every `SimulationBaseActor`": only actual simulation entities registered under an
`OptimisticLocalTimeManager` participate — `Car`, `Bicycle`, `Motorcycle`, `Bus`, `Subway`,
`Person`, `Link`, `Node`, `TrafficSignal`. Infrastructure singletons (`SnapshotManager`,
`ReportManager`, `LoadBalanceManager`, the TimeManagers themselves) are excluded — they're
machinery, not modeled objects with a causal timeline, and rolling them back would be
self-referential nonsense (they're what implements rollback).

### 6. Checkpoint granularity: per processed event, not per tick

Rejected: checkpoint only at tick boundaries. An actor can process several `actInteractWith`
exchanges within one tick (consistency-critical round trips); rolling back the whole tick when only
one of those exchanges needs undoing discards correctly-processed work unnecessarily and
complicates "which sub-event actually needs undoing" bookkeeping. **Decided: checkpoint
conceptually before every event (`actSpontaneous` or `actInteractWith`) that can mutate state** —
the textbook Time Warp default. The *storage* cost of this is addressed separately (next section);
this decision is about correctness granularity, not snapshot frequency.

### 7. Checkpointing mechanism: periodic full-copy + event replay log

Three known strategies: copy-state-saving (full copy every event — correct, expensive at HTC's
actor scale), incremental/delta saving (cheap per event, requires instrumenting every state
mutation, more fragile to implement correctly), and **periodic checkpointing with event replay**
(full copy every K events, replay the deterministic event log between checkpoints to reach an
arbitrary target). Decided: **periodic + replay**, for two reasons specific to this codebase:

- It reuses `buildMigrationSnapshot()`/`applyMigrationSnapshot()` as the full-copy primitive
  directly — no new serialization logic needed, and (unlike Gap C's migration-prep race) it's
  race-free here because the actor calls it on *itself*, synchronously, at a quiescent point right
  after finishing an event — never triggered asynchronously by another actor while it keeps
  mutating.
- Incremental/delta saving would require instrumenting every state mutation across every actor
  type — much larger blast radius, against the "don't build abstraction beyond what's needed"
  principle, when periodic+replay gets the same asymptotic memory savings more cheaply.

Composed into each participating actor via a stateless-style handler (same delegation pattern as
`CarMicroHandler` — holds no `ActorRef`, only lambdas + its own history buffers), **not**
centralized in the `OptimisticLocalTimeManager`, because only the actor's own `BaseActor[T]`
instance knows how to serialize its concrete `T`:

```scala
class RollbackHistoryHandler[T <: BaseState](
  checkpointInterval: Int,
  captureSnapshotFn: () => MigrationSnapshot,   // = () => buildMigrationSnapshot()
  restoreSnapshotFn: MigrationSnapshot => Unit, // = applyMigrationSnapshot
) {
  def recordProcessedEvent(event: BaseEvent, tick: Tick, seq: Long): Unit
  def rollbackTo(targetTick: Tick): Seq[LoggedEvent]  // returns undone sends, for anti-messages
  def pruneBelow(gvt: Tick): Unit                      // fossil collection
}
```

`recordProcessedEvent` is cheap (append to an event log) and runs every event; a full
`buildMigrationSnapshot()` copy is taken only every `checkpointInterval` events. Fossil collection
(triggered by the GVT watermark) discards checkpoints/log entries below GVT, always keeping the
latest checkpoint `<= GVT` as the floor.

The `OptimisticLocalTimeManager` doesn't hold this history — it only needs what it already tracks
today (`highestProcessedTick` per actor) to detect a straggler and know *when* to tell an actor to
roll back; the actor itself owns the *how*.

### 8. Prerequisite: deterministic RNG (in scope for this work)

Periodic+replay only works if `(state, event) → new state` is a pure function — replaying the same
events must reproduce the same result. Investigation found two non-deterministic sources in the
micro path that break this today, both **in scope to fix**, independent of being a Time Warp
prerequisite (they're also a pre-existing simulation-reproducibility bug):

- `KraussModel.scala:34,83` — `random: Random = new Random()`, unseeded JVM default, advances its
  own mutable state every sub-tick acceleration call (`randomFactor`). No call site
  (`DefaultMicroSimulationStrategy`, `LinkMicroTimeManager`, `MobilLaneChange`) passes a seed
  despite `KraussModel.withSeed(seed)` existing.
- `TravelTimeLogitEngine.scala:69,97` — mode-choice sampling draws from a **single global shared**
  `RandomSeedManager` generator, advanced by call order across the *entire* run, not per actor.
  Worse than the first case for Time Warp specifically: a shared mutable resource whose "correct"
  next value depends on global call order has no meaning once actors can roll back and re-execute
  independently of each other.

**Fix**: both become deterministically seeded per `(entityId, tick)` (e.g.
`new Random(hash(entityId, tick))`, constructed fresh per call site rather than carrying mutable
cross-call state). This removes both the replay-correctness blocker and the global-ordering
coupling in one change, and is a legitimate reproducibility fix on its own merits.

### 9. Micro sub-ticks (IDM/MOBIL inside `Link`): not independently checkpointed

Given the determinism fix above, a `Link`'s in-tick micro computation (IDM/MOBIL across sub-ticks)
is a pure function of the `Link`'s state at the top of the tick plus whatever interaction events it
received that tick. On rollback, sub-tick computation is simply **recomputed from scratch** from
the restored tick-boundary checkpoint — no `MicroUpdateData` intermediate state needs its own
checkpoint. This keeps checkpoint volume proportional to (actors × events), not
(actors × sub-ticks), which matters at HTC's actor scale.

### 10. Anti-messages: aggressive cancellation, message identity via `(senderId, tick, seq)`

No anti-message concept exists today; this is new infrastructure end to end.

- `ActorInteractionEvent` gains `messageId: MessageId` (`(senderId, tick, seq)`) and
  `isAntiMessage: Boolean = false`. `seq` is a per-actor monotonic send counter that **must not be
  rolled back with state** — it lives outside what `RollbackHistoryHandler` restores, so a resend
  after replay always gets a fresh id, never reusing one that might still be referenced by an
  in-flight anti-message. This is what makes **aggressive cancellation** (always anti-message
  everything after a rollback point, never try to detect "the replay produced an identical
  message so skip cancellation") safe and simple. Lazy cancellation (the classic optimization that
  skips redundant anti-messages) is deferred — same "correctness first, optimize once measured"
  posture as the GVT strategy split.
- **Cascade mechanism**: because HTC delivers messages via Pekko mailboxes with FIFO order
  guaranteed per sender→receiver pair, and an anti-message is always sent *after* its
  corresponding positive message (rollback happens after the original processing), the anti-message
  will in practice **always** find the original already processed at the receiver — the "still
  queued, cheap annihilation" case from classic Time Warp (built around explicit future-event
  queues) doesn't really apply to this push-based transport. So the anti-message handler always
  does real work: look up `messageId` in the receiver's own `RollbackHistoryHandler` event log,
  call `rollbackTo(tick_of_original)`, which both restores state and returns the list of messages
  *that receiver* sent while processing everything now undone — each becomes a new anti-message the
  receiver must emit downstream. This is the same `rollbackTo` used for straggler-driven rollback,
  just triggered by annihilation instead of a genuinely new causally-earlier event; no separate
  mechanism needed.
- **Termination of the cascade**: guaranteed, because no rollback can ever be asked to go before
  GVT (nothing below GVT can still be in flight or reversible by definition), so every cascade is
  bounded.
- **Correcting a likely misreading of the CLAUDE.md synchronization discipline**: "fire-and-forget"
  describes whether the *sender* waits for a reply, not whether the message needs an anti-message.
  Any message whose processing mutated the receiver's state needs to be undoable regardless of
  whether the exchange was fire-and-forget or consistency-critical — e.g. a fire-and-forget
  `LinkInfoData` that incremented a `Link`'s vehicle count still needs an anti-message if the
  sender rolls back. The dividing line for whether cascade rollback actually does anything is
  "did processing this event mutate state or send anything downstream," not the sync pattern —
  and rather than special-case pure-read exchanges, v1 always runs the same restore+replay
  machinery uniformly (a no-op mutation just converges back to the same state trivially).
- **Edge case, not solved yet, not blocking**: an anti-message theoretically arriving before its
  original is locatable (e.g. a shard rebalance mid-flight changed routing). Proposed treatment:
  stash it, reusing the existing migration-prep stash pattern (`preStart` stashing until
  `MigrationContextEvent`/`NoPendingMigrationEvent` arrives) rather than inventing a new mechanism
  — not designed in detail, flagged for whoever implements this.

### 11. Termination detection in optimistic mode

Conservative mode's termination check ("is everyone idle") doesn't transfer: an idle optimistic
actor can still be reactivated by a straggler or anti-message that hasn't arrived yet. Decided:
combine two signals already produced by the mechanisms above —

1. Every `OptimisticLocalTimeManager` reports locally idle (nothing scheduled, no rollback
   in flight, nothing mid-processing).
2. The GVT **plateaus** — stops advancing — across several consecutive `GVTCoordinator` rounds
   while (1) holds.

(2) is necessary, not just a nicety, because of the margin-based GVT strategy (§3): `GVT = min(LVTs)
- margin` may never numerically reach "the last real tick processed" even with zero remaining
activity, since the margin is a constant subtraction. A plateau across N rounds combined with
universal idleness is the safe substitute for "GVT caught up exactly," reusing the same
reconfirm-before-declaring-done posture the existing `QueryNextTickEvent` grace-period probe
already uses in conservative mode — extend that probe rather than inventing a separate protocol.

## Implementation log

### §8 RNG determinism — done (2026-08-06)

- `KraussModel.calculateAcceleration`/`updateState` (and the `CarFollowingModel` trait) dropped the
  stored mutable `random: Random` field entirely; both now take a `randomSeed: Long` parameter and
  construct `new Random(randomSeed)` fresh inside the call. `KraussModel.withSeed` was removed
  (nothing called it, and a per-instance seed no longer means anything once there's no
  per-instance generator to seed).
- `LaneChangeModel.evaluateLaneChange` (and `MobilLaneChange`/`SimpleLaneChange`) gained the same
  `randomSeed: Long` parameter, threaded down to the two internal `calculateAcceleration` calls
  (current-lane and target-lane) with a distinct derived seed for each so they don't draw
  identical values.
  - Caveat found during implementation: `evaluateLaneChange` has **zero callers anywhere in
    `src/main` or `src/test`** — MOBIL lane-changing is wired as a model but never invoked from any
    live actor path. The RNG fix landed as designed regardless (dead code still needs a correct
    contract once someone wires it up), but whoever adds the first caller must also decide what
    `(entityId, tick)` to hash for the seed at that call site.
- `TravelTimeLogitEngine.decide` no longer reads `RandomSeedManager.getScalaRandom()` (a single
  globally-shared generator). It now seeds a fresh `Random` per call from
  `TravelTimeLogitEngine.seedFor(ctx.entityId, ctx.currentTick)`. This required adding an
  `entityId: String` field to `DecisionContext` — the only two real construction sites,
  `PersonPlanManager.resolvePending`/`replanAfterPTTimeout`, already had `personId` in scope, so
  they pass `entityId = personId`. Three test files construct `DecisionContext` directly and were
  updated to pass a literal `entityId`.
- `RandomSeedManager.getJavaRandom`/`getScalaRandom` (and the `createDefaultSimulation` fallback
  they used) were deleted — `TravelTimeLogitEngine` was their only caller, and nothing else reads
  the shared generators. `RandomSeedManager.initialize`/`deterministicUUID`/
  `deterministicSimulationId` are untouched — those aren't simulation-outcome RNG draws, just
  stable-ID generation, and weren't in §8's scope.
- Full test suite (198 tests) passes unchanged after these signature changes; no test previously
  covered `KraussModel`/`MobilLaneChange`/`TravelTimeLogitEngine.sampleLogit`'s RNG behavior
  directly, so this is a compile-level correctness check, not a behavioral regression check —
  flagging since `docs/KNOWN_GAPS.md` already notes near-zero coverage on this area.

### §2 Conservative/GlobalTimeManagerBase extraction — done (2026-08-06)

Local side (clean split, matches the design exactly):
- `LocalTimeManagerBase` is now genuinely strategy-agnostic: actor registration/dispatch
  bookkeeping (`dispatchGeneration`, `highestProcessedTick`, `sendSpontaneousEvent*`,
  `finishEvent`, `terminateSimulation`, `forceDestructActiveActors`). `advanceToNextTick` and
  `reportGlobalTimeManager` are abstract (no body); a new hook,
  `onActorRescheduled(effectiveTick, wasIdle, isEarlierTick)`, replaces the inline barrier
  re-notify call in `scheduleEvent` (default no-op).
- New `ConservativeLocalTimeManager` (abstract) implements the barrier: `syncWithGlobalTime`,
  `advanceToNextTick` (wait for `runningEvents.isEmpty`), `reportGlobalTimeManager`
  (`LocalTimeReportEvent` to the parent), and `onActorRescheduled`'s re-notify. Handles
  `UpdateGlobalTimeEvent`/`QueryNextTickEvent`.
- `LocalDiscreteEventTimeManager`/`LocalTimeSteppedTimeManager` now extend
  `ConservativeLocalTimeManager` instead of `LocalTimeManagerBase` directly — their own
  `advanceToNextTick` overrides and `super.advanceToNextTick()` calls needed no changes.

Global side (partial, deliberately — see caveat): `GlobalTimeManagerBase` extracts only what's
truly strategy-independent — creating the LTM pool (`createTimeManagersPool`, now parameterized by
an abstract `localTimeManagerProps()` hook), `notifyLocalManagers`, `getSelfProxy`. Everything else
(the `SelectiveBarrier`'s `localTimeManagers` per-LTM tick/isProcessed map, migration-pause,
progressive-loading, termination) stayed in the renamed `ConservativeGlobalTimeManager`, verbatim.

**Caveat**: unlike the Local side, this is *not* a full base/strategy split — migration-pause and
progressive-loading are deeply entangled with the `SelectiveBarrier`'s specific per-LTM state
(`localTimeManagers.values.forall(_.isProcessed)` gates almost everything). Inventing shared hooks
for them now, before `OptimisticGlobalTimeManager` exists to demonstrate what it actually needs
from GVT-based idle/pause detection (§3/§11), would be speculative abstraction built to a guess.
Deferred until that class is built for real; flagged explicitly in `GlobalTimeManagerBase`'s
doc-comment so it isn't mistaken for finished.

`SimulationManager.createSingletonTimeManager` now wires `ConservativeGlobalTimeManager.props`
instead of `GlobalTimeManager.props`.

Verification: full test suite (198 tests) passes, including
`LocalTimeManagerBatchStallSpec` which directly exercises `LocalDiscreteEventTimeManager`'s
`runningEvents`/`advanceToNextTick`/`reportGlobalTimeManager` machinery — the part of this refactor
with real regression coverage. `ConservativeGlobalTimeManager` itself has no direct unit test
(same pre-existing gap `docs/KNOWN_GAPS.md` already notes for this area) — this move was verified
by full-suite pass plus careful line-by-line relocation (no logic rewritten), not by new test
coverage; flagging honestly rather than claiming more confidence than that warrants. No scenario
smoke run was performed this session (needs a live cluster/Redpanda).

Still not done from the design doc's item 2: the config-driven LTM-strategy factory
(`createTimeManagersPool`'s hardcoded `LocalDiscreteEventTimeManager.props`, and the GTM singleton
`Props` selection) — noted in the original decisions as "a fix needed regardless of Time Warp,"
but out of scope for this pure-refactor step; `localTimeManagerProps()` is the extension point
that fix would hang off of.

### §6/§7 `RollbackHistoryHandler` — done (2026-08-06)

New `core/actor/rollback/` package: `MessageId.scala`, `LoggedEvent.scala`,
`RollbackHistoryHandler.scala`, plus `RollbackHistoryHandlerSpec.scala` (7 pure-ScalaTest cases,
no Pekko — built and tested fully in isolation as §7's suggested step-3 order intended, no live
`OptimisticLocalTimeManager` needed).

- `RollbackHistoryHandler` follows the design sketch closely, with one deliberate deviation and one
  addition:
  - **Deviation**: not generic over `T <: BaseState` as originally sketched. `MigrationSnapshot`
    (the existing `buildMigrationSnapshot`/`applyMigrationSnapshot` primitive it wraps) already
    serializes state to an opaque `stateJson: String` blob — `T` would never appear anywhere in the
    class body, so carrying it would be an unused type parameter, not real type safety.
  - **Addition**: a `replayEventFn: BaseEvent[?] => Unit` constructor param and an `initialize
    (startTick: Tick)` method, neither present in the original sketch. `replayEventFn` is what
    actually walks state forward from a checkpoint to an exact log position — the sketch's
    `rollbackTo` signature implied replay happens somewhere but didn't say how; a checkpoint alone
    can't reconstruct an arbitrary target tick without re-executing the events between it and the
    target. `initialize` establishes a "before anything happened" floor checkpoint immediately, so
    rolling back to undo the actor's very first-ever processed event has a checkpoint to restore
    from — the periodic-only cadence in the sketch would otherwise have no floor for that case.
- `recordProcessedEvent`'s ordering key is `seq` (a per-actor monotonic processed-event counter),
  not `tick` — multiple events can share a tick (batched dispatch), so `tick` alone can't tell
  replay order. This is a different counter from `MessageId.seq` (§10's per-actor *send* counter);
  both are monotonic `Long`s but track different things.
- `rollbackTo(targetTick)` semantics: undoes every logged event with `tick >= targetTick` (Jefferson's
  "roll back to before the violation"), returning them so a future anti-message cascade (§10) can
  act on them; everything with `tick < targetTick` survives via restore-then-replay from the
  nearest earlier checkpoint.
- `MessageId`/`LoggedEvent.sentMessageIds` exist now (typed, unused) specifically because
  `docs/TIME_WARP_DESIGN.md`'s own step ordering says anti-messages (step 5) depend on the event
  log's "what did I send" data "already being correct" from this step — the field is there so step
  5 has a place to put that data, but nothing populates it yet: no live actor wiring exists to
  capture "what did this event send" (that requires instrumenting `sendMessageTo`, which needs a
  live `OptimisticLocalTimeManager` driving real dispatch — step 4's scope, not step 3's).
- Not yet composed into any actor — this step built and validated the handler as a standalone unit
  per the suggested order; wiring it into `BaseActor`/`SimulationBaseActor` (as a `private lazy val`
  the same way `CarMicroHandler` is wired into `Car`) is step 4's job, once
  `OptimisticLocalTimeManager` exists to actually call `rollbackTo` on a straggler.

### §2/§3/§11 `OptimisticLocalTimeManager` + `OptimisticGlobalTimeManager`/GVT — done, scoped (2026-08-06)

New: `core/actor/manager/time/gvt/{LocalVirtualTimeReport,GVTEstimationStrategy,MarginBasedGVTEstimation,TerminationPlateauDetector}.scala`,
`core/entity/event/control/execution/LvtReportEvent.scala`, `OptimisticLocalTimeManager.scala`,
`OptimisticGlobalTimeManager.scala`. Modified: `TimeManagerTypeEnum` gained `TIME_WARP`. 15 new
tests (11 pure-ScalaTest for the GVT/plateau pieces, 4 TestKit-based for
`OptimisticLocalTimeManager` mirroring `LocalTimeManagerBatchStallSpec`'s harness) — full suite
(220 tests) passes.

**What's real and tested:**
- `OptimisticLocalTimeManager` dispatches whatever's scheduled the moment it's available — no
  `UpdateGlobalTimeEvent` permission round-trip from a `GlobalTimeManager` the way
  `ConservativeLocalTimeManager` needs. Verified directly: registering an actor dispatches it
  immediately with zero messages exchanged with the parent; finishing with a fresh `scheduleTick`
  redispatches immediately; going idle then receiving a new `ScheduleEvent` redispatches
  immediately. This realizes §2's actual parallelism win for v1: *different LTM instances* (i.e.
  different shard/pool routees) now run fully independently of each other and of any global
  coordinator permission, rather than every LTM being serialized to the same GTM-chosen tick every
  round the way the `SelectiveBarrier` forces today.
- Progress reporting is genuinely fire-and-forget: `LvtReportEvent(lvt, isIdle)` sent to the
  `OptimisticGlobalTimeManager` after every batch resolves, never awaited.
- `OptimisticGlobalTimeManager` aggregates those reports via the pluggable `GVTEstimationStrategy`
  (`MarginBasedGVTEstimation` is v1's only implementation, per §3) and declares termination once
  `TerminationPlateauDetector` confirms every registered LTM is idle *and* the GVT estimate has
  stopped moving for `plateauRoundsRequired` consecutive rounds (§11) — deliberately waits for
  every registered LTM to have reported at least once before estimating anything, since an
  incomplete report set makes "all idle" meaningless.
- Deliberately does not attempt progressive-loading or migration-pause coordination — neither is
  designed for optimistic mode anywhere in this document (not even flagged as an open question,
  just never considered), so adding either now would be a guess, not an implementation of a
  decision.

**What's real but deliberately not enabled — the actual rollback trigger:**

§2 describes `OptimisticLocalTimeManager` as owning "each actor's rollback trigger point (via
`highestProcessedTick`)." That mechanism already exists — `LocalTimeManagerBase.scheduleEvent`'s
stale-tick guard (pre-dating Time Warp entirely) already detects exactly the condition Time Warp
needs: a request at or behind an actor's own `highestProcessedTick` watermark. Under conservative
mode this can only happen due to the LTM's own pool-sharing/batch bookkeeping quirks (never a real
causality violation, since the barrier guarantees no actor ever runs ahead) and is harmlessly
absorbed by bumping the tick forward. Under optimistic mode, the *same* condition can also mean a
genuine straggler — but **this implementation still just bumps it forward, identically to
conservative mode**, rather than calling `RollbackHistoryHandler.rollbackTo` on the actor. This is
a deliberate scope cut, not an oversight, for two compounding reasons:

1. **`RollbackHistoryHandler` isn't wired into any real actor yet.** Composing it into
   `BaseActor`/`SimulationBaseActor` needs a `replayEventFn` that can distinguish and re-invoke
   `actSpontaneous` vs. `actInteractWith`, and `recordProcessedEvent` calls added at the end of
   both `handleSpontaneous` and `handleInteractWith` (there is no single unified "an event was
   just processed" point in `SimulationBaseActor` today — confirmed by reading it in full for this
   step). That's real, substantial, correctness-sensitive work against the actor base class every
   single actor type in the simulation extends.
2. **Cross-actor interaction-message stragglers aren't even visible to the LTM at all.**
   `ActorInteractionEvent`s are peer-to-peer (actor-to-actor, via shard region/`actorSelection`),
   never routed through the LTM — `SimulationBaseActor.handleInteractWith`'s own tick-monotonic
   check (`if (event.tick > currentTick) currentTick = event.tick`) currently just silently leaves
   `currentTick` unchanged on a stale-tick interaction, throwing away exactly the signal Time Warp
   would need to treat it as a straggler. Fixing that is separate, `SimulationBaseActor`-level work
   this step didn't touch.
3. **Enabling a rollback that can't cascade-undo what it already sent is worse than not having
   Time Warp at all.** An actor that rolls its own state back after having already sent
   (now-invalid) messages downstream, with no anti-message mechanism (§10, step 5) to retract them,
   would silently desynchronize the simulation rather than correctly resynchronizing it. This
   mirrors the project's own existing posture toward shard rebalancing (kept disabled given a
   similar half-built-migration risk, per `CLAUDE.md`) — a real, dormant gap is safer than a
   partially-wired "fix."

Wiring the actual rollback trigger — both the LTM-local case above and the
`SimulationBaseActor.handleInteractWith` case — is deferred to land together with anti-messages
(§10, step 5), the same dependency ordering already decided for why anti-messages come last.

**Other scope notes:**
- `OptimisticLocalTimeManager`'s local virtual time is a simple v1 approximation (`localTickOffset`
  if anything is currently running, else the next scheduled tick) — not specified at this level of
  detail anywhere in this document; cheap to refine once measured against real GVT behavior.
- No large-tick batching (`LocalDiscreteEventTimeManager`'s `TICK_BATCH_SIZE` mechanism) — deferred
  until measured at scale, same "don't guess a premature optimization" posture as the checkpoint
  interval and GVT margin.
- Only one concrete Time Warp LTM strategy exists (no discrete-event/time-stepped split) — Time
  Warp is inherently episodic/discrete-event-shaped; no time-stepped variant is planned.
- `OptimisticLocalTimeManager`/`OptimisticGlobalTimeManager` are not reachable from any config —
  the three hardcode points already flagged in §2's implementation log (`GlobalTimeManagerBase.
  localTimeManagerProps`, `SimulationManager.createSingletonTimeManager`, and a third one found
  this step: `SimulationBaseActor.onInitialize` unconditionally sets `currentTimeManagerType =
  TimeManagerTypeEnum.DISCRETE_EVENT` regardless of what `Properties.defaultTimeManagerType` says)
  are all still hardcoded to the conservative side. Building the config surface for scenario-level
  selection is real follow-up work, deliberately not attempted here — it's already an open
  question below, and doing it before a single one of the three time-manager strategies below it
  is actually safe to select in production would be building a door to a room that isn't finished.

### §10 Anti-messages — done, scoped to the pure cascade math (2026-08-06)

New: `core/actor/rollback/{SentMessage,AntiMessageCascade}.scala`, plus
`AntiMessageCascadeSpec.scala` (4 tests). Modified: `LoggedEvent.sentMessageIds: Seq[MessageId]`
(step 3, always empty, never populated) → `LoggedEvent.sentMessages: Seq[SentMessage]` — a real
fix, not a rename, see below. Full suite (224 tests) passes.

**A gap found and fixed from step 3, before building on top of it**: `MessageId(senderId, tick,
seq)` alone cannot address an anti-message — it identifies *which* send, not *where it went*.
Checked `SimulationBaseActor.sendMessageTo`/`sendMessageToShard`/`sendMessageToPool`: the
destination `entityId`/`shardId` passed in are used only for routing (building the envelope /
selecting the shard region or pool actor) and are never stored on the outgoing `ActorInteractionEvent`
itself — that event's own `actorRefId`/`shardRefId` fields are the *sender's* identity, not the
receiver's. So a bare `MessageId` would leave a future rollback knowing a message needs to be
retracted with nowhere to send the retraction. Fixed by introducing `SentMessage(messageId,
receiverId, receiverShardId, receiverActorType)` and changing what `LoggedEvent` carries — free to
fix now since nothing populated the old field yet (step 3 built the type but never wired a real
caller).

**What's real and tested**: `AntiMessageCascade.messagesToRetract(undone: Seq[LoggedEvent]):
Seq[SentMessage]` — the actual "aggressive cancellation" math §10 decided: every message any
undone event sent comes back, unconditionally, no attempt to detect that a subsequent replay
reproduces an identical send and skip re-cancelling it (that detection is explicitly the *deferred*
"lazy cancellation" optimization, not this step). Deliberately not a recursive data structure —
per §10's own design, the cascade recursion happens *across actors*, not within one computation:
each `SentMessage` becomes a real anti-message sent to its receiver, which reacts by calling
`RollbackHistoryHandler.rollbackTo` on itself (the same method straggler-driven rollback uses — no
separate mechanism), producing the next wave. Termination is guaranteed by the GVT bound, per the
original design; nothing new needed there.

**What's still not live — the actual wire protocol and trigger, now the sum of everything deferred
across steps 4 and 5**:

1. **`ActorInteractionEvent` doesn't carry `messageId`/`isAntiMessage` yet.** Deliberately not
   touched this step: this is the hottest message type in the simulator, with its own custom
   `EntityEnvelopeSerializer`/`ActorInteractionSerializer` and a *recent, dedicated* commit history
   on this exact struct (`perf: eliminate redundant shardRefId...`, `perf: remove redundant
   shardId/actorId...` — see `git log` on this file). Adding two fields without updating that
   serializer in lockstep (and its `EntityEnvelopeSerializerSpec` wire-shape assertions) would
   either silently break the optimized wire path or bounce onto slower generic Kryo serialization
   — exactly the class of regression that recent history was written to prevent. This needs its
   own careful, serializer-aware pass, not a couple of fields tucked into a broader step.
2. **`RollbackHistoryHandler` is still not composed into `BaseActor`/`SimulationBaseActor`** — same
   gap flagged in step 4's log, restated here because step 5 doesn't change it: no
   `recordProcessedEvent`/`replayEventFn` wiring at the two dispatch points
   (`handleSpontaneous`/`handleInteractWith`), and (now) no `sendMessageTo` instrumentation to
   actually populate `LoggedEvent.sentMessages` — the cascade math above has nothing real to
   consume until that capture exists.
3. **The straggler → rollback trigger is still not activated** — `OptimisticLocalTimeManager`'s
   `scheduleEvent` guard and `SimulationBaseActor.handleInteractWith`'s tick-monotonic check both
   still just absorb the stale-tick condition silently, exactly as documented in step 4's log.
4. **The anti-message-arrives-before-its-original stash edge case (§10's own "not solved yet, not
   blocking" note)** — still exactly that: named, not designed, not blocking anything since nothing
   above sends a real anti-message yet either.

None of 1–4 can be done as a side effect of something else — each is real, separable, live-system
work against the actor base class or the wire protocol, not additional pure logic buildable in
isolation the way every other piece of Time Warp so far has been. This is where this design's
"Suggested order" list of five steps ends; what's left is exactly the four items above, in
roughly that order (1 and 2 can proceed in parallel; 3 depends on both; 4 depends on 3 landing and
being observed to actually matter in practice).

### §10 follow-up: `RollbackHistoryHandler` composed into `SimulationBaseActor` — mechanical part only (2026-08-06)

After closing out the five suggested steps, started on the "not live yet" list from §10's log. Hit
a new, real design gap immediately (see below), so — per explicit direction to not risk
conservative mode — scoped this pass to exactly the mechanical, side-effect-free half of item 2
from that list: composing the handler and its bookkeeping calls, with `rollbackTo` itself left
uncallable. New: `TimeWarpConfigSpec.scala` (1 test, catches exactly the class of bug found below).
Modified: `SimulationBaseActor.scala`, `application.conf`, `RollbackHistoryHandler`/`LoggedEvent`
(a type fix, see below). Full suite (225 tests) passes.

**New design gap found (not previously flagged anywhere): `actSpontaneous`/`actInteractWith` are
not replay-safe.** `RollbackHistoryHandler.rollbackTo`'s replay phase needs to re-execute an
already-processed event against a just-restored checkpoint to walk state forward — the design
sketch's `replayEventFn` assumed this was just "call the event's handler again." But
`SimulationBaseActor.handleSpontaneous`/`handleInteractWith` interleave state mutation with
protocol side effects that must *not* repeat during a replay the way they do during a live
dispatch: `onFinishSpontaneous` signaling the time manager (`FinishEvent`), and `sendMessageTo`'s
real sends. Re-invoking `actSpontaneous`/`actInteractWith` verbatim during replay would resend
spurious `FinishEvent`s and corrupt the time manager's `runningEvents` bookkeeping — a live-system
correctness bug, not a hypothetical one. Fixing this needs those side effects to be separable from
the state mutation (e.g. a "replay mode" that suppresses the protocol effects while still running
the business logic), which is a real design decision not made anywhere in this document. Presented
to the user as an explicit choice given the "don't interfere with conservative mode" instruction;
decided: **do the safe mechanical wiring now, leave `rollbackTo` uncallable, solve replay-safety as
a separate future decision** — not blocking, since nothing calls `rollbackTo` yet regardless.

**What's real and live, but fully inert for conservative mode:**
- `SimulationBaseActor` gained `private lazy val rollbackHandler: RollbackHistoryHandler`,
  `private var processedEventSeq: Long` (the actor's own monotonic processed-event counter,
  correctly a local `var` not `state` per `CLAUDE.md`'s rule — no reply obligation, cheap to
  reset), and `private def isTimeWarp: Boolean = currentTimeManagerType ==
  TimeManagerTypeEnum.TIME_WARP`. Every new call site (`registerOnTimeManager` calling
  `rollbackHandler.initialize(startTick)`; `handleSpontaneous`/`handleInteractWith` each calling
  `rollbackHandler.recordProcessedEvent(...)`) is gated behind `isTimeWarp`. Since nothing sets
  `currentTimeManagerType` to `TIME_WARP` anywhere reachable yet, `isTimeWarp` is provably always
  `false` today — the `lazy val` never initializes, none of the new code ever runs, and the full
  225-test suite (unchanged behavior, same count modulo the one new config test) confirms nothing
  regressed. `replayEventFn` is a documented stub that throws `UnsupportedOperationException` —
  deliberately, so if something ever *did* wrongly call `rollbackTo` before replay-safety is
  solved, it fails loudly instead of corrupting state silently.
- **A second, real type-correctness fix found while wiring this**: `RollbackHistoryHandler`/
  `LoggedEvent` were typed around `core.entity.event.BaseEvent[?]` (`SpontaneousEvent`'s type),
  but `ActorInteractionEvent` — the *other* real event type `actInteractWith` receives — is a
  separate, unrelated case class, not a `BaseEvent` subtype. Both are now typed `AnyRef`, the true
  common supertype, since the handler only ever stores/passes the event through, never calls a
  `BaseEvent`-specific method on it.
- **A path bug caught before it could ever bite**: the checkpoint-interval config was first wired
  to read `htc.time-manager.time-warp.checkpoint-interval`, but the new `application.conf` block
  is a sibling of `time-manager` under `htc`, not nested inside it — the real path is
  `htc.time-warp.checkpoint-interval`. Because the read only happens behind `isTimeWarp`, this
  typo would have thrown `ConfigException.Missing` the moment Time Warp was ever actually enabled,
  with zero test coverage to catch it beforehand — exactly the "silently never verified" risk of
  gating everything behind an unreachable flag. Fixed, and `TimeWarpConfigSpec` now resolves the
  path independently of the gate specifically so a future typo like this doesn't sit undetected
  again.

**Still not done from §10's "not live yet" list** (as of the entry above): `ActorInteractionEvent`'s
wire fields (item 1), the rest of item 2 (`rollbackTo` itself, blocked on the replay-safety gap),
item 3 (activating the straggler trigger), item 4 (the anti-message-before-original stash edge
case).

### §10 follow-up, continued: replay-safety solved, `rollbackTo` is now real (2026-08-06)

Solved the gap the previous entry left open. New: `SimulationBaseActorTimeWarpReplaySpec.scala`
(3 TestKit-based tests, driving a minimal concrete actor through its real mailbox — same harness
style as `LocalTimeManagerBatchStallSpec`/`OptimisticLocalTimeManagerSpec`). Modified:
`SimulationBaseActor.scala` (the actual fix), `application.conf` untouched this round. Full suite
(228 tests) passes.

**The fix: an `isReplaying` flag, checked only by the two protocol-signaling methods.**
`onFinishSpontaneous` and `scheduleEvent(tick)` — the only two places `actSpontaneous`/
`actInteractWith` talk to the time manager — now check `if (isReplaying) return` as their very
first line. `sendMessageTo` is deliberately left untouched: §10 wants replay to actually resend
messages (each getting a fresh identity so the pre-rollback sends can be anti-messaged), so
suppressing it would be wrong, not just unnecessary. `rollbackHandler`'s `replayEventFn` now
delegates to a real `replayLoggedEvent(event: AnyRef)` method that sets `isReplaying = true`,
mirrors `handleSpontaneous`/`handleInteractWith`'s pre-dispatch bookkeeping
(`currentTick`/`currentTimeManager`/`currentGeneration`, Lamport clock update) for whichever event
type it's replaying, calls `actSpontaneous`/`actInteractWith` directly, and resets `isReplaying` in
a `finally`. Deliberately skips both methods' *post*-dispatch bookkeeping (handleSpontaneous's
"did you call onFinishSpontaneous" safety net, `recordProcessedEvent` itself) — replay isn't a new
event for the time manager to track, and the event being replayed is already in the log, not a
fresh one to log again.

Still fully inert for conservative mode by the same argument as before: `isReplaying` only ever
becomes `true` inside `replayLoggedEvent`, only ever invoked via `rollbackHandler`'s `replayEventFn`,
only ever reachable from `rollbackTo`, which — even now that it's *safe* to call — still has
nothing calling it (item 3, the straggler trigger, is still not activated). `rollbackTo` moved from
"not callable" to "callable and correct, but still not called by anything live."

**Three bugs found and fixed while writing the test, worth recording since they're the kind of
thing that would have surfaced during real integration otherwise:**
1. `LoggedEvent`'s original design (step 3) assumed test state types could be freely nested inside
   spec classes, matching the rest of this test suite's style — but `RollbackHistoryHandler`'s
   `restoreSnapshotFn` round-trips state through real JSON (`JsonUtil.fromJsonClassName`), and
   Jackson cannot construct a non-static inner class (needs the enclosing instance, which nothing
   supplies) — `InvalidDefinitionException` the moment a checkpoint is actually restored. Fixed by
   declaring the test state type at module level, not nested in the `Spec` class — a test-only
   fix, but worth noting as a real constraint on any future state type used with rollback tests.
2. `sendMessageTo`'s `PoolDistributed` path addresses its target by convention at
   `/user/{entityId}` (`BaseActor.getActorPoolRef`), but a `TestProbe`'s own actor lives under
   `/system/` (created via `systemActorOf`, confirmed by reading Pekko's own `TestKit.scala`
   source) — never reachable at `/user/{name}` no matter what name is given. Fixed with a small
   real `/user/{name}` forwarding actor in the test, standing in for a `PoolDistributed` peer the
   same way a `TestProbe` alone cannot.
3. `BaseActor` is a `PersistentActor`; recovery (querying this `persistenceId`'s journal, even an
   empty in-memory one) is asynchronous regardless of `TestActorRef`'s `CallingThreadDispatcher` —
   a command sent before `RecoveryCompleted` is stashed by Pekko Persistence itself, not dropped,
   but the stash only drains once that async round-trip finishes. `PersonMigrationSnapshotSpec`/
   `PrivateVehicleMigrationSnapshotSpec` never hit this because they call protected methods
   directly instead of going through the mailbox; this spec's whole point was exercising the real
   mailbox path (`handleSpontaneous`'s new `recordProcessedEvent` call), so that workaround wasn't
   available. Fixed with a one-time bounded wait after actor construction, before any real traffic
   — not a retry loop, a single documented startup synchronization for a known Pekko/Akka
   PersistentActor-in-tests race.

**Still not done from §10's "not live yet" list**: `ActorInteractionEvent`'s wire fields (item 1),
item 3 (nothing calls `rollbackTo` yet — the straggler trigger itself is still not activated,
though it's now safe to activate), item 4 (the anti-message-before-original stash edge case).

## Open questions / not designed yet

- Exact values for `checkpointInterval` (K) and the GVT margin — no default chosen; needs
  measurement against a real scenario once implemented, not guessed up front.
- Config/enum wiring specifics: `TimeManagerTypeEnum` needs a `TIME_WARP` value (or equivalent);
  the config surface for choosing `checkpointInterval`/margin per scenario is undesigned.
- Lazy anti-message cancellation (skip re-sending when replay produces an identical message) —
  deferred optimization, not designed.
- Exact (Mattern-style) GVT — deferred optimization, not designed beyond naming it as the eventual
  alternative to margin-based estimation.
- The anti-message-arrives-before-original stash mechanism (§10) — named, not designed.
- Federated conservative/optimistic execution in one run — explicitly out of scope for any planned
  phase, not just deferred.
- Test strategy — none of this has unit/integration test coverage designed yet; `docs/KNOWN_GAPS.md`
  already flags near-zero coverage on the core mobility actors this would sit alongside, so this
  lands in a codebase with little regression safety net for the actors it interacts with.

## Relevant file map (proposed — nothing below exists yet)

| File | Role |
|---|---|
| `core/actor/manager/time/ConservativeLocalTimeManager.scala` (new) | Extracted barrier logic shared by the two existing LTM subclasses; no behavior change |
| `core/actor/manager/time/OptimisticLocalTimeManager.scala` (done, scoped) | Time Warp LTM: optimistic dispatch, async LVT reporting done; straggler detection exists but still just bumps forward (rollback trigger deferred to step 5) |
| `core/actor/manager/time/GlobalTimeManagerBase.scala` (done, partial — see §2 log) | Extracted cluster/registration plumbing common to both GTM variants; migration-pause/progressive-loading stayed Conservative-only |
| `core/actor/manager/time/ConservativeGlobalTimeManager.scala` (done, renamed from `GlobalTimeManager`) | Existing `SelectiveBarrier`/`QueryNextTickEvent` behavior, unchanged |
| `core/actor/manager/time/OptimisticGlobalTimeManager.scala` (done) | GVT coordinator + termination-plateau detection |
| `core/actor/manager/time/gvt/GVTEstimationStrategy.scala`, `MarginBasedGVTEstimation.scala`, `TerminationPlateauDetector.scala`, `LocalVirtualTimeReport.scala` (done) | Pluggable GVT strategy + §11 termination detector; Mattern-style GVT variant still deferred |
| `core/entity/event/control/execution/LvtReportEvent.scala` (done) | Fire-and-forget LTM→GTM LVT report |
| `core/actor/rollback/RollbackHistoryHandler.scala` (done, not yet composed into any actor) | Per-actor checkpoint+event-log handler |
| `core/actor/rollback/LoggedEvent.scala`, `MessageId.scala`, `SentMessage.scala` (done) | Event-log entry format; message identity + addressing for anti-messages (fields exist, unpopulated — no live capture wired yet) |
| `core/actor/rollback/AntiMessageCascade.scala` (done) | Pure "which sends must be retracted" math over a rollback's undone events |
| `core/entity/event/ActorInteractionEvent.scala` (not done — see §10 log's item 1) | Needs `messageId`/`isAntiMessage`; deferred, hot-path wire-format change needing its own serializer-aware pass |
| `core/enumeration/TimeManagerTypeEnum.scala` (done) | Added `TIME_WARP` — string exists, not yet reachable from config (see §2/§3/§11 log's "Other scope notes") |
| `model/hybrid/micro/model/KraussModel.scala` (done) | Seed RNG deterministically per `(entityId, tick)`; remove unseeded `new Random()` call sites |
| `model/hybrid/decision/TravelTimeLogitEngine.scala`, `core/actor/manager/RandomSeedManager.scala` (done) | Replace global shared RNG with per-`(entityId, tick)` seeding |

## Next steps

Implementation-ready per the decisions above, except for the items in "Open questions." Suggested
order: (1) ~~the RNG determinism fix (§8)~~ — done; (2) ~~the `Conservative*`/`GlobalTimeManagerBase`
extraction (§2)~~ — done (Local side fully split; Global side partially, see caveat above); (3)
~~`RollbackHistoryHandler` + checkpoint/replay (§6/§7)~~ — done, built and tested standalone; (4)
~~`OptimisticLocalTimeManager` + `OptimisticGlobalTimeManager`/GVT (§2/§3/§11)~~ — done, scoped: the
coordinator layer (dispatch-without-barrier, async LVT/GVT reporting, termination-plateau
detection) is real and tested; the actual straggler → `RollbackHistoryHandler.rollbackTo` trigger
is not wired live (see that section's log entry for why); (5) ~~anti-messages (§10)~~ — done,
scoped to the pure cascade math (`AntiMessageCascade`) plus a real fix to step 3's `MessageId`
(it couldn't address a receiver; now `SentMessage` can) — the wire protocol and live trigger are
not built, see that section's log entry for the exact four-item remaining list.

**Next up: none of the five suggested steps remain — what's left is §10's four-item "not live yet"
list** (wire fields + serializer, `BaseActor` integration, activating the straggler trigger, the
anti-message-before-original stash edge case), which is real system-integration work rather than
another self-contained design decision to implement.
