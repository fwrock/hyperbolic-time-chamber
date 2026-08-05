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

## Decided: wire format (2026-08-04)

Unified into a single, renamed message pair — the old names described "signal state" only, but
the payload now also carries link capacity, so both the data classes and the event-type enum
entries are renamed:

| Before | After |
|---|---|
| `RequestSignalStateData` | `RequestLinkAccessData` |
| `SignalStateData` | `LinkAccessData` |
| `EventTypeEnum.RequestSignalState` | `EventTypeEnum.RequestLinkAccess` |
| `EventTypeEnum.ReceiveSignalState` | `EventTypeEnum.ReceiveLinkAccess` |
| *(new)* | `LinkCapacityStateEnum { Available, Full }` |

`TrafficSignalPhaseStateEnum` is unchanged — it's still purely the signal-phase sub-field, nested
inside the larger reply, not renamed. Vehicle-side handler/status names (`CarSignalHandler`,
`WaitingSignalState`, `WaitingSignal`) are *not* renamed as part of this — out of scope for the
wire-format decision, larger blast radius, can be revisited separately if wanted.

```scala
case class LinkAccessData(
  phase: TrafficSignalPhaseStateEnum,
  nextTick: Tick,
  queuePosition: Int = 0,
  capacityState: LinkCapacityStateEnum = LinkCapacityStateEnum.Available
) extends BaseEventData
```

`capacityState` only matters when `phase == Green` (while Red, the vehicle isn't crossing yet
regardless, so capacity isn't checked at request time).

**Correctness wrinkle this surfaced, and how it's resolved**: today, once `Node` replies Red, the
vehicle computes a deterministic `waitUntilTick` and — once reached — calls `leavingLinkFn()`
*directly*, without asking `Node` again (safe today because fixed-time signal phase is exactly
predictable). Once capacity is a second, independent gate, that shortcut is no longer sufficient:
downstream capacity could still be `Full` even after the red phase legitimately ends. Fix: when a
Red wait's `waitUntilTick` is reached, the vehicle re-calls the request (re-verifying phase *and*
capacity together, at the moment it actually matters) instead of proceeding unilaterally. This adds
one extra round-trip, but only once per red-wait cycle (not per tick) — far cheaper than the
resend-storm bug already eliminated.

**Phase 2 push semantics — decided as a direct grant, not a re-ask** (2026-08-04): when `Node`
dequeues a vehicle from the capacity-FIFO buffer, it sends `LinkAccessData` again, unsolicited,
already populated as `Green`/`Available` — the vehicle treats it exactly like an initial Green
reply, no re-verification round-trip. Rejected the alternative (push is a mere nudge prompting the
vehicle to re-call `RequestLinkAccessData`) as strictly worse: more messages, and shaped like a
retry loop reminiscent of the resend bug already fixed once.

**Decided (2026-08-04): close the gap for zero new messages.** The direct-grant choice above leaves
a gap — the signal could have cycled back to Red while the vehicle sat in the capacity buffer, so a
blind direct grant could send a vehicle across on a Red it can't see. `Node` already holds the
current signal phase locally (`state.signals`, kept current by `TrafficSignalPhaseHandler`'s
existing phase-change notifications), so before dequeuing a vehicle from the capacity buffer it
checks that *local, already-in-memory* state — if Red at that instant, don't dequeue yet; the
vehicle is picked up again the next time either more capacity frees, or the relevant phase turns
Green (`Node` already receives that transition as an existing event,
`handleReceiveSignalChangeStatus`). This means the capacity-FIFO buffer must be
re-scanned/drained from **two** triggers, not one: `Link`'s "N slots freed" notification (original
design) and `Node`'s own phase-change handler turning the relevant movement Green (this decision).
Both are cheap, both are already-existing event entry points — no new message types needed for
either.

## Decided: `Node`-side FIFO buffer structure (2026-08-04)

New model class, same location/style convention as `LinkRegister`/`SignalState`
(`model/hybrid/entity/state/model/PendingLinkAccessRequest.scala`):

```scala
case class PendingLinkAccessRequest(
  actorRefId: String,
  shardRefId: String
)
```

Deliberately minimal — exactly what `sendMessageFn(entityId, shardId, data, eventType)` needs to
reply later, both already available on the original request event (`event.actorRefId`/
`event.shardRefId`). No `nextTick` or other signal-wait fields here; those belong to the vehicle's
own `WaitingCapacity` state, not the `Node`'s buffer entry.

New `NodeState` field:

```scala
capacityWaitQueue: mutable.Map[String, mutable.Queue[PendingLinkAccessRequest]] = mutable.Map.empty
```

Keyed by `targetLinkId` (the link the waiting vehicle wants to enter). `mutable.Map[String,
mutable.Queue[X]]` is an already-precedented shape in this codebase's JSON-serialized state
(`SubwayStationState.subways`, `LinkState.vehiclesByLane`) — no new serialization concern.

**Resolves the migration-snapshot question for free**: because this lives in `NodeState` (part of
`state`, not an actor-local `var` on `Node`/`NodeEventHandler`), it's automatically covered by the
same generic state serialization/snapshot mechanism every other `NodeState` field already uses —
exactly what `CLAUDE.md`'s "what must live in `state`" guidance calls for a pending
reply-obligation like this. No special migration code needed; the only failure mode to avoid is
accidentally keeping this as a handler/actor-local `var` instead.

**Draining**: both trigger points decided earlier — `Link`'s "N slots freed" notification, and
`Node`'s own phase-change handler turning the relevant movement Green — call one shared internal
method (working name: `tryDrainCapacityQueue(linkId, maxToWake)`) that checks `state.signals` is
Green for that movement, dequeues up to `maxToWake` entries FIFO, and sends each a `LinkAccessData`
(`Green`/`Available`) grant.

## Open questions / next decisions needed before coding starts

1. How many vehicles to wake per freed slot — exactly N (matching the link's reported freed
   count) was the working assumption; still needs explicit confirmation.
2. Whether `LinkState.capacity` (already used by `SpeedUtil.linkDensitySpeed` and
   `bprCongestionFactor`) should be reused as-is for `storageCapacity`, or whether flow capacity
   and storage capacity need to become two distinct fields.

## Relevant file map (for whoever picks this up)

| File | Role |
|---|---|
| `model/hybrid/support/node/NodeEventHandler.scala` | `Node`'s signal/capacity reply logic; would own `tryDrainCapacityQueue` and both drain triggers |
| `model/hybrid/entity/state/NodeState.scala` | Gains `capacityWaitQueue: mutable.Map[String, mutable.Queue[PendingLinkAccessRequest]]` |
| `model/hybrid/entity/state/model/PendingLinkAccessRequest.scala` (new) | Minimal per-waiting-vehicle record: `actorRefId`, `shardRefId` |
| `model/hybrid/support/car/CarSignalHandler.scala` (+ Bus/Bicycle/Motorcycle equivalents) | Vehicle-side request/reply handling; already fixed for the deregister/`scheduleEvent` pattern this feature must reuse |
| `model/hybrid/support/link/LinkVehicleFlowHandler.scala` | Where `LeaveLinkData` is processed; would own "N slots freed" notification to `state.from` |
| `model/hybrid/util/SpeedUtil.scala` | Already has `bprCongestionFactor`; would gain the jam-spacing-based storage-capacity helper |
| `docs/KNOWN_GAPS.md` | Tracks the `deferFinishSpontaneous` batch-stall gap this design must not reintroduce |
