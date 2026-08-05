# Congestion Propagation & Intersection Design

Status as of 2026-08-05: **the point-3/4 link-capacity/spillback mechanism designed below is
implemented** (`RequestLinkAccessData`/`LinkAccessData`, `NodeState.capacityWaitQueue`/
`availableCapacity`, both drain triggers, the Link→Node capacity registration and freed-slot
notifications, and the vehicle-side `WaitingCapacity` status across Car/Bus/Bicycle/Motorcycle).
Point 5 (intersection throughput/conflict modeling beyond capacity backpressure — saturation flow,
gap acceptance) is **not** started. This document remains the design log/rationale — update it if
the implementation diverges from what's recorded here, don't treat it as aspirational anymore for
the parts marked implemented.

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

**Revised 2026-08-05**: earlier drafts of this section proposed a *new* derived
`storageCapacity = length × lanes / jamSpacing` field, reasoning from `docs/SCENARIO_MODELING.md`'s
description of `LinkState.capacity` as "maximum vehicles per hour" (a flow rate) — which would have
made it the wrong denominator for occupancy-based "is this link full" checks. Confirmed with the
model's author that this was a **documentation** error, not the field's actual intended semantics:
`capacity` was always meant as the link's physical vehicle-occupancy capacity (how many vehicles
fit), matching how `SpeedUtil.linkDensitySpeed`/`bprCongestionFactor` already use it — no separate
field needed. `docs/SCENARIO_MODELING.md`'s Link field table has been corrected to match. (Side
benefit of chasing this down: `numberOfCars / capacity` in `linkDensitySpeed`, with the corrected
occupancy semantics, is mathematically the Greenshields (1935) density-speed ratio
`k/k_jam` — the existing formula was already the right literature model, just previously
undocumented as such.)

- **MESO**: "full" is `registered.size >= state.capacity` (no new number, no new field).
- **MICRO**: no new number needed either — "full" is already physically expressed as the queue
  reaching `position ≈ 0` (front of link). A shared jam-spacing-style constant *could* still be
  worth introducing later to keep MICRO's minimum-gap parameter and MESO's `capacity` values
  mutually consistent (i.e. `capacity ≈ length × lanes / jamSpacing` as a sanity check on
  scenario data, not a runtime dependency) — not needed for this feature to work, noted as a
  possible follow-up.

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
  this link. Node dequeues waiting vehicles (FIFO) and sends each a "go" message — see "How many to
  wake" below for exactly how many. The vehicle treats this exactly like a Green reply:
  `scheduleEvent(tick)` (its own current tick, updated to at least the sender's tick via the
  standard `handleInteractWith` monotonic-tick rule — see "TimeManager tick safety" below) to
  re-register, then proceeds via the existing `WaitingSignal`/`leavingLinkFn()` machinery once
  genuinely re-dispatched.

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
(`Green`/`Available`) grant. `maxToWake` is not a raw pass-through of the Link's last-reported
count — see "How many to wake" below.

## Decided: how many vehicles to wake per freed slot (2026-08-04)

**Not a blind pass-through of the Link's reported freed-count.** Every vehicle, for every link, asks
its `Node` before entering — there is no direct-entry path that bypasses this gate. That means a
race is possible: between the `Link`'s "N slots freed" notification and `Node` finishing the drain,
fresh `RequestLinkAccessData` from vehicles that were never queued can arrive for the same link, and
`Node`'s single-threaded (Pekko actor) processing interleaves all of it — freed-notifications,
buffer drains, and fresh requests — in one sequential stream. If `Node` only ever passed through the
Link's last-reported count without tracking its own grants, it could hand out more slots than
actually exist (a fresh grant and a buffer-drain grant both "spending" the same reported freed slot).

Fix: `Node` keeps its own per-link running counter — call it `availableCapacity: mutable.Map[String,
Int]` in `NodeState` (same migration-snapshot-visibility reasoning as `capacityWaitQueue` above) —
that is the *only* authority on how many grants `Node` may hand out for that link, right now:

- **Decrement by 1** on every grant `Node` issues for that link — fresh `Green`/`Available` replies
  *and* buffer-drain grants alike, no distinction.
- **Increment** by whatever count the `Link`'s freed-slots notification reports.

`tryDrainCapacityQueue(linkId, ...)` wakes `min(availableCapacity(linkId), capacityWaitQueue(linkId)
.size)` vehicles, decrementing `availableCapacity` by 1 per grant as it goes — never a fixed number,
never a raw echo of the Link's last message.

**`availableCapacity` needs a correct *initial* value per link** — resolved 2026-08-05 (see the
revised "Storage capacity" section above): seeded directly from that link's existing
`LinkState.capacity`, no separate field or computation. `Node` still needs to *learn* this value
once, the same way as before — `Link` reports its own `state.capacity` once, at `Node`↔`Link`
connection setup (alongside or via the same channel that already builds `NodeState.connections`),
and `Node` stores it as `availableCapacity(linkId)`'s starting point.

## Open questions / next decisions needed before coding starts

All previously-open design-level questions are now closed. Remaining work is implementation:
wiring `RequestLinkAccessData`/`LinkAccessData`, `NodeState.capacityWaitQueue`/`availableCapacity`,
the two drain triggers, and the one-time `Node`↔`Link` capacity handshake described above.

## Relevant file map (implemented 2026-08-05)

| File | Role |
|---|---|
| `model/hybrid/support/node/NodeEventHandler.scala` | `handleRequestLinkAccess` (renamed from `handleRequestSignalState`), `replyGreenOrBufferForCapacity`, `tryDrainCapacityQueue`, `handleLinkCapacityFreed`, `handleRegisterLinkCapacity`; `handleReceiveSignalChangeStatus` now also drains on Green (second trigger) |
| `model/hybrid/entity/state/NodeState.scala` | `capacityWaitQueue: mutable.Map[String, mutable.Queue[PendingLinkAccessRequest]]`, `availableCapacity: mutable.Map[String, Int]` |
| `model/hybrid/entity/state/model/PendingLinkAccessRequest.scala` (new) | Minimal per-waiting-vehicle record: `actorRefId`, `shardRefId` |
| `model/hybrid/entity/state/enumeration/LinkCapacityStateEnum.scala` (new) | `Available` / `Full` |
| `model/hybrid/entity/event/data/vehicle/RequestLinkAccessData.scala` (renamed from `RequestSignalStateData`) | Vehicle → Node request |
| `model/hybrid/entity/event/node/LinkAccessData.scala` (renamed from `SignalStateData`) | Node → vehicle reply/grant; gained `capacityState` field |
| `model/hybrid/entity/event/data/link/LinkCapacityFreedData.scala`, `RegisterLinkCapacityData.scala` (new) | Link → Node: exact freed count per departure; one-time capacity registration at Link init |
| `model/hybrid/support/car/CarSignalHandler.scala` (+ Bus/Bicycle/Motorcycle equivalents) | `handleLinkAccess` (renamed from `handleSignalState`); Red replies now flag `signalWaitNeedsReverify` so `actSpontaneous`'s `WaitingSignal` branch re-verifies (fresh request) instead of proceeding unilaterally once capacity is a second, independent gate |
| `model/hybrid/actor/{Car,Bus,Bicycle,Motorcycle}.scala` | New `signalWaitNeedsReverify` var + `WaitingCapacity` status case (safety-net, same shape as `WaitingSignalState`) |
| `model/hybrid/support/link/LinkVehicleFlowHandler.scala` | `handleLeaveLink` sends `LinkCapacityFreedData(linkId, freedCount = 1)` to `state.from` whenever `wasRegistered` |
| `model/hybrid/actor/Link.scala` | `onInitialize` sends `RegisterLinkCapacityData(linkId, state.capacity.toInt)` to `state.from` |
| `model/hybrid/util/SpeedUtil.scala` | `linkDensitySpeed`/`bprCongestionFactor` already used `capacity` correctly as-is — no change needed; confirmed `linkDensitySpeed` is mathematically the Greenshields (1935) speed-density model once `capacity` is read as occupancy, not flow |
| `docs/SCENARIO_MODELING.md` | Link's `capacity` field description corrected 2026-08-05 (was wrongly documented as "vehicles per hour") |
| `docs/KNOWN_GAPS.md` | Tracks the `deferFinishSpontaneous` batch-stall gap this design must not reintroduce |
| `src/test/scala/model/hybrid/support/node/NodeEventHandlerSpec.scala`, `src/test/scala/model/hybrid/support/car/CarSignalHandlerSpec.scala` | Updated/extended for the renamed types and capacity behavior |

**Not yet done**: point 5 (saturation-flow/gap-acceptance intersection throughput modeling),
priority-between-competing-movements fairness (FIFO only today), and no scenario-level integration
test yet exercising the full spillback path end-to-end (current coverage is handler-level unit
tests only, per the existing `CityMapUtil` JVM-singleton constraint noted in `CarSignalHandlerSpec`'s
doc comment).
