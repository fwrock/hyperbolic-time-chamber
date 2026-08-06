# Time Warp (Optimistic Synchronization with Rollback) — Design

Status as of 2026-08-06: **implementation started**. §8 (RNG determinism) is done — see
"Implementation log" below. Everything else in "Decisions" is still design-only. This document is
the design log/rationale from the planning conversation that produced it — update it as decisions
are revisited or implementation diverges, don't treat it as aspirational once code lands (same
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
| `core/actor/manager/time/OptimisticLocalTimeManager.scala` (new) | Time Warp LTM: optimistic dispatch, straggler detection via `highestProcessedTick`, drives rollback |
| `core/actor/manager/time/GlobalTimeManagerBase.scala` (new) | Extracted cluster/registration/migration-pause/progressive-loading plumbing common to both GTM variants |
| `core/actor/manager/time/ConservativeGlobalTimeManager.scala` (new, renamed from today's `GlobalTimeManager`) | Existing `SelectiveBarrier`/`QueryNextTickEvent` behavior, unchanged |
| `core/actor/manager/time/OptimisticGlobalTimeManager.scala` (new) | `GVTCoordinator`, termination-plateau detection |
| `core/actor/manager/time/gvt/GVTEstimationStrategy.scala`, `MarginBasedGVTEstimation.scala` (new) | Pluggable GVT strategy; Mattern-style variant deferred |
| `core/actor/rollback/RollbackHistoryHandler.scala` (new) | Per-actor checkpoint+event-log handler, composed into participating actors |
| `core/actor/rollback/LoggedEvent.scala`, `MessageId.scala` (new) | Event-log entry format; message identity for anti-messages |
| `core/entity/event/ActorInteractionEvent.scala` (modify) | Add `messageId`, `isAntiMessage` fields |
| `core/enumeration/TimeManagerTypeEnum.scala` (modify) | Add `TIME_WARP` |
| `model/hybrid/micro/model/KraussModel.scala` (done) | Seed RNG deterministically per `(entityId, tick)`; remove unseeded `new Random()` call sites |
| `model/hybrid/decision/TravelTimeLogitEngine.scala`, `core/actor/manager/RandomSeedManager.scala` (done) | Replace global shared RNG with per-`(entityId, tick)` seeding |

## Next steps

Implementation-ready per the decisions above, except for the items in "Open questions." Suggested
order: (1) ~~the RNG determinism fix (§8)~~ — done, see "Implementation log"; (2) the
`Conservative*`/`GlobalTimeManagerBase` extraction (§2) — pure refactor, no behavior change,
de-risks the rest; (3) `RollbackHistoryHandler` + checkpoint/replay (§6/§7) in isolation, testable
without a live optimistic LTM; (4) `OptimisticLocalTimeManager` +
`OptimisticGlobalTimeManager`/GVT (§2/§3/§11); (5) anti-messages (§10) last, since it depends on
the event log's "what did I send" data already being correct from step 3.

**Next up: step (2), the `Conservative*`/`GlobalTimeManagerBase` extraction.**
