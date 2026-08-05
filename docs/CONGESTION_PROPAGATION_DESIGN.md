# Congestion Propagation & Intersection Design (Work in Progress)

Status as of 2026-08-04. This document captures the design decisions made so far on
`feat/congestion-propagation`, before implementation of the link-capacity/spillback feature
begins. It is a working design log, not a finished spec — update it as decisions change.

## Motivation

Investigated as points 3, 4, 5 of a broader congestion-modeling review (see conversation history /
`htc-architect` investigation that kicked this off): today `Link` has no spillback/backpressure
mechanism at all — a link accepts vehicles unconditionally even when full, in both MESO and MICRO.
Points 1 and 2 of that review (populating `LinkState.currentSpeed`/`congestionFactor` from real
occupancy) are already implemented and committed (`SpeedUtil.bprCongestionFactor`,
`LinkVehicleFlowHandler.recomputeAndPublishMesoDynamics`, `LinkMicroSimulationHandler`'s per-tick
recompute). This document covers what's left: spillback/backpressure (point 3), queue modeling
(point 4), and intersection capacity (point 5).

## Prerequisite work already landed on this branch

Before any of the design below could be trusted, two bugs were found and fixed in the *existing*
signal-wait exchange (`Car`/`Bus`/`Bicycle`/`Motorcycle` ↔ `Node`), because the new feature reuses
the same `Node`-mediated wait pattern:

1. **`+161-167s` signal-wait overshoot** (`docs/KNOWN_GAPS.md`): `WaitingSignalState`'s retry
   branch resent `RequestSignalStateData` every tick a reply hadn't arrived, and
   `NodeEventHandler.signalWaitingCounts` has no per-request decrement (only a bulk reset on
   Green) — each retry permanently inflated the queue position fed into the headway wait
   calculation. Fixed by making `requestSignalState()` wait purely for the reply as an interaction
   event, never resending.
2. **LTM batch-stall via `deferFinishSpontaneous()`** (`docs/KNOWN_GAPS.md`, newest section):
   `deferFinishSpontaneous()` holds the actor's `runningEvents` entry open at the `LocalTimeManager`
   for the full duration of an async wait, blocking every other actor co-scheduled in the same
   batch until it resolves. Fixed by replacing it with a genuine `onFinishSpontaneous(None)`
   (deregister) + `scheduleEvent(tick)` (re-register on reply) — the same pattern already used
   correctly for `Person`'s `StartTrip → Car` handoff. **This is the load-bearing lesson for the
   design below**: the link-capacity wait can be much longer and much less bounded than a signal
   check, so it must use this deregister/`scheduleEvent` pattern from the start, never
   `deferFinishSpontaneous()`.

Three more pre-existing usages of `deferFinishSpontaneous()` (`BusStopHandler` x2, `SubwayStation`
x1, `BusStation` x3) have the same batch-stall shape and are documented as the next gap-fixing
priority in `docs/KNOWN_GAPS.md`, tracked separately from this feature.

## Problem scope (points 3/4/5)

- **Point 3 — spillback/backpressure**: no Link→Link (or Link→Node→Link) channel exists today. A
  `Link` accepts entry unconditionally regardless of occupancy, in both MESO and MICRO.
- **Point 4 — queue modeling**: MICRO already has a real physical queue (the "virtual leader" a car
  follows when the exit signal is Red). MESO has none — `signalWaitingCounts` at the `Node` is a
  bare counter, not a real queue with capacity semantics.
- **Point 5 — intersection capacity**: `Node` is a binary Green/Red gate with no throughput limit
  and no conflict resolution between competing movements.

## Design decisions made so far

### Storage capacity (point 3, "how do we know a link is full")

- A single literature-grounded constant — **jam spacing** (~7-8m/vehicle, standard traffic
  engineering value) — drives "full" in both modes, not two separate concepts:
  - **MESO**: `storageCapacity = length × lanes / jamSpacing`, checked against aggregate
    occupancy (`registered.size`).
  - **MICRO**: no new number needed — "full" is already physically expressed as the queue
    reaching `position ≈ 0` (front of link). The same `jamSpacing` constant should inform the
    minimum-gap parameter the Krauss car-following model already uses, so both modes derive
    "full" from the same physical assumption.

### Where the wait happens (point 3, solving the "limbo" problem)

Key insight from this conversation: **the vehicle already asks the `Node` for permission before
leaving its current link** (`requestSignalState()` → `RequestSignalStateData`, *before*
`leavingLinkFn()`/`LeaveLinkData` is ever sent). This means downstream-capacity checking can be
folded into the *same* exchange, at the *same* point — the vehicle simply never calls
`leavingLinkFn()` until granted, so it stays correctly counted in its current link's `registered`
set the whole time. No new "vehicle is nowhere" limbo state needs to be invented.

### Two-phase protocol (point 3/4)

- **Phase 1 (synchronous, at the `Node`)**: on `RequestSignalStateData`, `Node` now has three
  possible outcomes instead of two: Green, Red (existing, deterministic `nextTick` via headway),
  or **"waiting for capacity"** — the destination link is full. In this third case, `Node`:
  - Enqueues the requester (FIFO, keyed by target link) in an in-memory buffer, storing
    `actorRefId`/`shardRefId` (already available on the request event).
  - Replies immediately (never silently drops the request — this preserves the "Node always
    replies on every branch" invariant the signal-wait fix's deadlock-freedom depends on) with a
    reply that carries no `nextTick` (unlike Red, this can't be computed deterministically — see
    "why not analytical estimate" below).
  - The vehicle, on receiving this reply, deregisters properly: `onFinishSpontaneous(None)`, NOT
    `deferFinishSpontaneous()` — per the prerequisite fix above, this wait is unbounded and must
    not hold the LTM batch open.
- **Phase 2 (asynchronous, triggered by the Link)**: when a `Link` processes a `LeaveLinkData` (a
  vehicle actually departing), it knows *exactly* how many slots freed (not an estimate). It
  notifies its governing `Node` — which is already knowable without a new lookup: `LinkState.from`
  **is** that Node's id, since that's exactly the Node a vehicle already queries before entering
  this link. Node dequeues up to N waiting vehicles (FIFO) and sends each a "go" message. The
  vehicle treats this exactly like a Green reply: `scheduleEvent(tick)` (its own current tick,
  updated to at least the sender's tick via the standard `handleInteractWith` monotonic-tick rule —
  see "TimeManager tick safety" below) to re-register, then proceeds via the existing
  `WaitingSignal`/`leavingLinkFn()` machinery once genuinely re-dispatched.

### Why event-driven (Phase 2 push), not an analytical "next free tick" estimate

Considered and rejected: mirroring the Red-signal reply's deterministic `nextTick`. Rejected
because the signal case works *because* fixed-time signal phases are exactly computable in
advance; link capacity freeing up is not — it depends on unpredictable downstream conditions
(how long each queued vehicle ahead takes to actually depart). An analytical estimate would
necessarily be wrong sometimes, requiring either accepted inaccuracy or a correction round-trip
(reintroducing the resend-based message storm the signal-wait fix just eliminated). Event-driven
exact notification (Link tells Node the instant — and only the instant — capacity changes) is
always exact, never wasted, and fits the project's established "avoid unnecessary messages"
discipline (mirrors `TrafficSignalPhaseHandler.notifyNodes`, which only fires on phase *change*,
never per-tick).

### TimeManager tick safety (resolved, not a concern)

Raised and answered in this conversation: can the tick value in the eventual "capacity freed, go"
push regress or otherwise desync? No — `SimulationBaseActor.handleInteractWith`
(`SimulationBaseActor.scala:561-563`) already guarantees `currentTick` is monotonically
non-decreasing on every interaction event (`if (event.tick > currentTick) currentTick =
event.tick`), for *any* actor-to-actor message in this framework, not something specific to this
feature. The Link→Node→Vehicle relay chain inherits this automatically at each hop. The only
consequence of a slow relay is that the waiting vehicle "skips" the intervening ticks it was
dormant for — already-accepted, pre-existing behavior for every async exchange in this codebase,
not a new risk.

### Fairness (point 5, deferred detail)

FIFO per destination link to start (the buffer at `Node` naturally provides this ordering). Not yet
decided: priority between multiple competing movements/approach links converging on the same
destination link (real-world right-of-way, protected turns, etc.) — explicitly deferred until FIFO
is working and validated.

### Explicitly deferred / out of scope for now

- Gap-acceptance modeling for uncontrolled (non-signalized) intersections (HCM Ch. 9 / Tanner
  1962) — literature-grounded option identified, not started.
- Movement-conflict matrices for auto-generated signal plans — irrelevant while phase plans are
  authored externally in `simulation.json`.
- Permanent-gridlock behavior (a vehicle waiting forever because the network genuinely never
  frees up) is **correct**, not a bug, and should not be "fixed" — noted so it isn't confused with
  a real stall bug during testing.

## Open questions / next decisions needed before coding starts

1. Exact wire shape: extend `SignalStateData`/`RequestSignalStateData` with a capacity-wait
   variant (one round-trip, one message family), or a fully separate message pair? Leaning toward
   unifying into the existing signal exchange to avoid doubling round-trips per approach, but not
   finalized.
2. How many vehicles to wake per freed slot — exactly N (matching the link's reported freed
   count) was the working assumption; confirm this before implementing the `Node`-side buffer.
3. `Node`-side buffer data structure and its interaction with `NodeState`/migration snapshot rules
   (per `CLAUDE.md`'s "what must live in `state`" guidance — a pending FIFO queue of waiting
   vehicles is exactly the kind of reply-obligation state that must not be an actor-local `var`
   invisible to `buildMigrationSnapshot`, even though migration is currently disabled).
4. Whether `LinkState.capacity` (already used by `SpeedUtil.linkDensitySpeed` and
   `bprCongestionFactor`) should be reused as-is for `storageCapacity`, or whether flow capacity
   and storage capacity need to become two distinct fields.

## Relevant file map (for whoever picks this up)

| File | Role |
|---|---|
| `model/hybrid/support/node/NodeEventHandler.scala` | `Node`'s signal/capacity reply logic; would own the new FIFO buffer |
| `model/hybrid/entity/state/NodeState.scala` | Would carry the buffer, migration-snapshot-visible |
| `model/hybrid/support/car/CarSignalHandler.scala` (+ Bus/Bicycle/Motorcycle equivalents) | Vehicle-side request/reply handling; already fixed for the deregister/`scheduleEvent` pattern this feature must reuse |
| `model/hybrid/support/link/LinkVehicleFlowHandler.scala` | Where `LeaveLinkData` is processed; would own "N slots freed" notification to `state.from` |
| `model/hybrid/util/SpeedUtil.scala` | Already has `bprCongestionFactor`; would gain the jam-spacing-based storage-capacity helper |
| `docs/KNOWN_GAPS.md` | Tracks the `deferFinishSpontaneous` batch-stall gap this design must not reintroduce |
