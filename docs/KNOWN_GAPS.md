# Known Gaps

Honest gap assessment against the README/docs claims, based on a source-level audit. This module
has the platform's best documentation, but "best-documented" isn't the same as "fully accurate" —
update this file as gaps close.

## [RESOLVED 2026-07-22] Headline Gap: an Undocumented, Orphaned Second Actor Package

**Resolution**: `model/mobility/actor/` (Car, Bus, BusStation, BusStop, Link, Node, Person, Subway,
SubwayStation, TrafficSignal, GPS — ~2,184 lines) has been deleted. Confirmed dead before removal:
a repo-wide grep found no scenario JSON, doc, or `typeActor` string outside the package itself
referencing it, with one exception — `DigitalTwinManager.entityTypeClassName` routed
`EntityType.SUBWAY`/`EntityType.PERSON` to `model.mobility.actor.Subway`/`Person` while every other
`EntityType` case already routed to `model.hybrid.actor`. That was a genuine mis-routing bug (the
digital-twin update path for Subway/Person entities would have hit the dead package), fixed as
part of this removal by routing both cases to `model.hybrid.actor` like the rest.
`model/mobility/entity`, `model/mobility/collections`, `model/mobility/util`, and
`model/mobility/types` were kept — they hold data types (`SubRoutePair`,
`VehicleLinkFlowData`, etc.) still imported by `JsonUtil.scala` and
`core/actor/manager/report/ClickHouseReportData.scala`; only the `actor` subpackage was orphaned.

Original finding, kept for history: README and `docs/*_AGENT.md` documented only
`model.hybrid.actor` (Car, Bus, Subway, Person, etc.). `model/mobility/actor/` was a second,
complete, parallel actor package not mentioned anywhere in the README or `docs/`, with
`model/mobility/actor/Person.scala` a 15-line stub — consistent with legacy/orphaned code, not an
active alternate simulation mode.

## Shard Migration Silently Drops Actor-Local Reply State (Currently Latent — Migration Is Disabled)

**[RESOLVED 2026-07-22] Gaps A and B below are fixed.** `PrivateVehicle` gained
`captureMigrationFields`/`restoreMigrationFields` (called from each concrete vehicle's
`buildMigrationSnapshot`/`applyMigrationSnapshot` override — `Car.scala`, `Bicycle.scala`,
`Motorcycle.scala`, since a trait with a self-type can't itself call `super` on a method it doesn't
extend), and `Person` got the equivalent override for `currentPTVehicleRef`. `MigrationSnapshot`
carries the new fields with safe defaults so old snapshots still deserialize. Round-trip coverage:
`src/test/scala/model/hybrid/actor/PrivateVehicleMigrationSnapshotSpec.scala` and
`PersonMigrationSnapshotSpec.scala`.
**Gap C is a distinct, still-open issue** — see its note below; fixing A/B did not resolve it.

Root-caused while investigating why `Car`/`Bicycle`/`Motorcycle` could fail to emit `TripCompleted`.
The original fix attempt (`vehicleTripTimeoutTicks`, commit `932798f`) was correctly reverted in
`420b285` in favor of a structural guarantee (`Car.onDestruct` always calls
`onFinishPrivateVehicle` when `state.status != Finished`). That fixes the trip-lifecycle case, but
three related gaps trace back to one shared structural cause and are still open:

- **A — `PrivateVehicle` reply-linkage vars aren't covered by migration snapshots.**
  `ownerPersonRef`, `personCentric`, `tripOrigin`, `tripDestination`, `tripStartTick` live as plain
  `var`s on the actor (`PrivateVehicle.scala:33-69`), not inside `state: T`. `BaseActor.buildMigrationSnapshot`
  (`BaseActor.scala:286-291`) only serializes `state`, and no actor under `model.hybrid` overrides
  `buildMigrationSnapshot`/`applyMigrationSnapshot` to add these fields. If a vehicle migrates
  shard mid-trip, it rehydrates with `ownerPersonRef = None`; `reportTripCompletion`
  (`PrivateVehicle.scala:205-206`) becomes a silent no-op, and `personCentric` resetting to `false`
  also risks an incorrect `selfDestruct()` for a still-owned vehicle.
- **B — same pattern on `Person.currentPTVehicleRef`.** The Bus/Subway boarding-response barrier
  (`expectedUnloadResponses`) was correctly fixed in commit `531ca55` by having `Person.onDestruct`
  always answer the vehicle it's boarded on. That fix depends on `currentPTVehicleRef`, itself a
  plain actor-local `var`, not part of `PersonState` — so it reopens the same barrier deadlock if
  `Person` migrates shard while boarded.
- **C — `Car.signalStateRetryCounter` watchdog (`Car.scala:252-262`) is a live retry/give-up loop,
  still open — confirmed a distinct root cause from A/B, not fixed by them.** `Car.status ==
  WaitingSignalState` lives in `state` (a `MovableState` field), so it *does* survive migration via
  the default `buildMigrationSnapshot` — A/B were about actor-local `var`s outside `state`, which
  this isn't. The real exposure is the in-flight `REQUEST_SIGNAL_STATE`/`SIGNAL_STATE_RESPONSE`
  messages themselves during the migration hand-off window: `MessageBuffer` (see its own docstring)
  silently drops buffered messages once `maxBufferSize` is exceeded, and that's a message-delivery
  gap, not a missing-snapshot-field gap. `NodeEventHandler.handleRequestSignalState` was confirmed
  to reply on every branch, so this isn't a Node bug either. A real fix belongs in the migration
  coordinator/`MessageBuffer` (e.g. backpressure or a non-lossy overflow policy instead of silent
  drop) — that's a `htc-architect`-level design call, not a Car.scala patch, and out of scope for
  the A/B fix above.

**Why this hadn't bitten anyone yet**: shard rebalancing/migration is currently kept disabled while
the project prioritizes simulation execution quality and performance — see `CLAUDE.md`'s Actor
State section. These gaps were real but dormant; A/B are now fixed (see above) so they no longer
become load-bearing if migration is turned back on. Gap C remains dormant-but-real for the same
reason and needs its own fix before migration can be safely re-enabled.

## Simulation Runs Are Not Fully Reproducible for a Fixed Seed — Duplicate Spontaneous-Event Dispatch (Structural duplicate dispatch: RESOLVED 2026-07-25, TM-level fix; small residual timing drift still open)

Found 2026-07-24 while validating the new SQLite input format (see `SCENARIO_MODELING.md` §3/§8)
end-to-end against a real cluster run. Running the **identical** scenario (same `simulation.json`,
same `randomSeed`, same topology) twice produced materially different `enter_link`/`leave_link`
event totals for the same bus completing laps on a fixed 2-link loop (originally 9150 vs. 5527
`enter_link`s across two JSON runs — see git history for the full original table). All runs
reached the same final simulated tick, ruling out early termination; the divergence is caused by
`actSpontaneous` effectively firing extra times per run, at ticks that vary run to run, which
shifts how many laps fit in the same tick budget.

**Root cause, part 1 (fixed 2026-07-24): `BusStopHandler.requestLoadPassenger`/
`requestUnloadPeopleData` polled blindly instead of waiting for the async reply.**

`BusState.status` and `BusState.movableStatus` are **the same field** — `BusState.status` is a
`def`/`def _=` delegate straight onto the inherited `MovableState.movableStatus` var
(`BusState.scala:96-97`). This matters because `requestLoadPassenger` set
`state.status = WaitingLoadPassenger`, sent `BusRequestPassengerData` to the `BusStop`, and then
— before this fix — immediately called `onFinishSpontaneousFn(Some(currentTick + 1))`, an
unconditional "poll again next tick" pattern. `Bus.actSpontaneous`'s
`case WaitingLoadPassenger => enterLink()` cannot distinguish "still waiting for the stop's
`BusLoadPassengerData` reply" from "boarding just finished, proceed" — both look identical because
`handleBusLoadPeople` (the actual boarding-complete handler) reuses the exact same status value.
So: if the stop's reply took longer than one tick, the blind poll fired `enterLink()`
*prematurely*, sending a first `EnterLinkData`. When the stop's real reply arrived afterward,
`handleBusLoadPeople` reset `status` back to `WaitingLoadPassenger` and rescheduled again, and the
next dispatch's `case WaitingLoadPassenger => enterLink()` fired a *second*, genuinely duplicate
`EnterLinkData` for a link already registered. Confirmed directly via instrumentation (temporary,
since removed) showing, at every duplicate, `status=WaitingLoadPassenger` twice in a row across
consecutive ticks, each with its own `enterLink()` call.

Fix applied in `BusStopHandler.scala`:
- `requestLoadPassenger`/`requestUnloadPeopleData` now call `deferFinishSpontaneousFn()` (added as
  a new constructor lambda, wired to `SimulationBaseActor.deferFinishSpontaneous()` in `Bus.scala`)
  instead of polling — the spontaneous event is left unresolved until the stop's reply actually
  arrives, exactly the "consistency-critical" pattern `CLAUDE.md`'s Synchronization Discipline
  already prescribes.
- `handleBusLoadPeople`/`handleUnloadPassenger`'s own reschedule was changed from `scheduleEventFn`
  (a raw `ScheduleEvent` message) to `onFinishSpontaneousFn` (a proper `FinishEvent`). This was a
  **second, dependent bug**, only surfaced once the poll above was removed: `ScheduleEvent` adds
  the actor to `scheduledActors` but does **not** clear the TimeManager's `runningEvents`
  bookkeeping — only a `FinishEvent` does. The old poll's `FinishEvent` had been incidentally
  clearing `runningEvents` on every tick as a side effect; removing it without also fixing this
  caused the bus to permanently stick in `runningEvents` (confirmed: `[STALL]` /
  `[GTM-STALL] ... stuck actors: Bus/...` warnings after ~3-4 minutes). Switching both call sites
  to `onFinishSpontaneousFn` fixed the stall and preserved correct rescheduling.
- Also fixed the same "reused status value, blind poll" pattern in `Bus`/`Motorcycle`/`Bicycle`/
  `Subway`'s `case Ready => enterLink()` (and `Bus`'s `case WaitingLoadPassenger` re-entry path):
  `Movable.enterLink()` only ever sets `state.movableStatus = Waiting`, but only `Car` is
  incidentally protected by it (it has no explicit `case Ready` and falls through to `Movable`'s
  base `actSpontaneous`, which *does* check `movableStatus == Waiting`). The other four vehicle
  types match on `state.status` directly, so added an explicit `state.movableStatus == Waiting`
  guard before each of those `enterLink()` call sites too (belt-and-suspenders — see part 2 below
  for why this alone wasn't sufficient).

**Result, validated against the full 86,400-tick baseline scenario with the fixed `randomSeed`**:
same-link duplicate `enter_link` count dropped from the original ~30/1770 (~1.7%) baseline — which
itself had been made *worse* by the guard-only fix above in isolation (41-99/1790, ~2.3-5.5%,
across a few runs, before the `deferFinishSpontaneousFn`/`onFinishSpontaneousFn` fix landed) — down
to **16/1767 (~0.9%)**, with the first duplicate now appearing at tick ~26,000 instead of ~3,650.
111/111 tests pass; no stalls in the validated run.

**Root cause, part 2 (not fixed — architectural, affects every "send + immediately poll"
call site): late-arriving redundant `FinishEvent`s get bumped to the wrong tick by
`LocalTimeManagerBase.finishEvent`'s own orphan-prevention logic.**

The residual ~16 duplicates were traced (via temporary `Console.err` instrumentation with
JVM stack traces on every `onFinishSpontaneous`/`deferFinishSpontaneous`/`scheduleEvent` call for
the bus, since removed) to a *different* mechanism than part 1. At the tick where a bus leaves a
link, **three independent call sites** each call `onFinishSpontaneous(Some(currentTick + 1))` for
what is logically one resolution: `BusSignalHandler.requestSignalState` (waiting for the node's
signal reply), `Movable.leavingLink` (right after sending `LeaveLinkData`), and
`BusLinkHandler.handleLeaveLink` (the *link's* reply confirming the vehicle left). Normally this is
harmless — all three add the same `Identify` to the same `scheduledActors(tick+1)` `Set`, which
naturally de-duplicates. But if one of them (typically the link's reply — it has an extra
actor-hop, so it tends to arrive last) reaches the TimeManager *after* `localTickOffset` has
already advanced past that tick, it hits this guard in `LocalTimeManagerBase.finishEvent`:
```scala
val effectiveTick = if (tick <= localTickOffset) localTickOffset + 1 else tick
```
This logic exists to prevent a legitimately-late `ScheduleEvent`/`FinishEvent` from being silently
dropped (see the `scheduleEvent` comment on the same rationale) — but here it "rescues" an
already-redundant reschedule by bumping it to `localTickOffset + 1`, creating a **genuine extra
schedule entry one tick later than anything the actor's own code intended**. If that phantom tick
happens to land while the bus is mid-flight in an unrelated async wait (e.g. genuinely still
waiting on `WaitingLoadPassenger` for the stop's reply from part 1's fixed code path), the
`case WaitingLoadPassenger => enterLink()` branch fires on a status value that hasn't actually
changed, producing the same class of duplicate `EnterLinkData` as part 1, just via a different
trigger. Confirmed directly: at every residual duplicate, exactly this triple-`FinishEvent`
pattern preceded it by one dispatch cycle.

This is architectural, not a one-line fix: the "send a message, then unconditionally
`onFinishSpontaneous(Some(currentTick + 1))` to poll again" pattern is used at many call sites
across `Movable`/`BusSignalHandler`/`BusLinkHandler`/etc., each implicitly relying on the
`scheduledActors` `Set`'s natural de-duplication to make redundant reschedules harmless — which
holds *except* when the orphan-rescue bump above turns a late redundant reschedule into a distinct
tick.

**Attempted and reverted (2026-07-25): removing `Movable.leavingLink()`'s own redundant poll.**
`Movable.leavingLink()` sends `LeaveLinkData` then immediately polls
(`onFinishSpontaneous(Some(currentTick+1))`) — redundant with `BusSignalHandler.requestSignalState`'s
own poll one step earlier in the same leave sequence, and exactly the mechanism behind the
tick-3651-triple-`FinishEvent` example above. Replaced it with `deferFinishSpontaneous()`, relying
solely on the Link's `LinkInfoData` reply (`*LinkHandler.handleLeaveLink`) to resolve — same pattern
already applied successfully to `BusStopHandler` earlier in this investigation. **This caused a
genuine stall** (`[STALL] ... stuck actors: Subway/...` at tick 622, well before the bus's own
gap would even matter) — reverted immediately. Root cause of the stall, found before reverting:
`Subway.actHandleReceiveLeaveLinkInfo` **intentionally** does not always resolve the spontaneous
event —
```scala
// If status is already Waiting/Ready/Stopped the subway is managing its
// own scheduling; suppress the duplicate FinishEvent to avoid a spurious extra tick.
if (state.status == Moving) {
  state.status = Ready
  onFinishSpontaneous(Some(currentTick + 1))
}
```
— i.e. this reply handler is *itself* written assuming a fallback poll exists elsewhere to cover
the case where it silently no-ops. Removing the poll broke that assumption and the actor hung
until the watchdog. **This is strong evidence the "redundant poll" pattern is load-bearing by
design in multiple places, not accidental duplication** — every reply handler in this family
(`Bus`/`Car`/`Motorcycle`/`Bicycle`'s `handleLeaveLink` also have their own
`state.status == Parked || Finished` stale-discard guards, e.g.) was written on the assumption
that *some* poll elsewhere covers the case where it doesn't fire. Consequently: **do not attempt
to remove any individual poll site again without first either (a) auditing and making *every*
interacting reply handler unconditionally resolve on every branch, system-wide, in one
coordinated change, or (b) fixing this at the TimeManager level instead** — e.g. giving
`LocalTimeManagerBase` a way to recognize that a `FinishEvent` is stale relative to an actor that
has already been dispatched again more recently (a per-actor "last dispatched tick" map, comparing
against `FinishEvent.end`, would let `finishEvent` drop a stale/redundant reschedule instead of
bumping it into a phantom tick) without touching any actor-level logic or its careful
stale-suppression guards. This second option is the safer next step for whoever continues this,
precisely because it doesn't require touching (and re-verifying) every one of these interacting
state machines at once.

**Root cause, part 3 (found and fixed 2026-07-25): `handleBusLoadPeople` had no stale-reply guard.**
Classified all ~11 residual duplicates from a full 86,400-tick run (each traced via caller-tagged
`onFinishSpontaneous` logging, temporary, since removed) and found they shared one dominant
mechanism, distinct from part 2's leaving-link triple-poll: `BusStop`'s `BusLoadPassengerData`
reply to a `RequestPassenger` (sent by `requestLoadPassenger`) can arrive **after** the bus has
already moved on from that `WaitingLoadPassenger` cycle — e.g. it entered the next link via its own
`enterLink()` poll before the stop's reply made it through the mailbox. `handleBusLoadPeople` had
no guard against this: it unconditionally set `state.status = WaitingLoadPassenger` (recall this is
the *same field* as `movableStatus`, see part 1) and rescheduled, which made
`Bus.actSpontaneous`'s `case WaitingLoadPassenger => enterLink()` fire a second, spurious time for a
link already entered.

This is the **same class of bug as part 1**, just via a different stale-arrival path, and the fix
follows the same already-proven pattern already used elsewhere in this codebase for exactly this
situation (`BusSignalHandler.handleSignalState`'s `state.status != WaitingSignalState` check;
`Car`/`Motorcycle`/`Bicycle`'s `status == Parked || Finished` checks in their `handleLeaveLink`) —
**a stale-reply guard, not a resolver removal**. This is why it's safe where parts 2's two attempts
(removing `Movable.enterLink()`'s and `Movable.leavingLink()`'s polls, relying solely on the
reply) were not: those removed a resolver that other code depended on always firing; this only
skips a reschedule when the reply is *already known to be irrelevant* (state has moved on), and
never touches the always-on resolver path.

Fix applied in `BusStopHandler.handleBusLoadPeople`: compute
`isStaleReply = state.status != WaitingLoadPassenger` at entry, keep passenger boarding
unconditional (people are already committed by the stop, discarding the message would leak them
out of the simulation), but skip the `state.status = WaitingLoadPassenger` /
`onFinishSpontaneousFn` reschedule when `isStaleReply`.

**Result: 0/1759 and 0/1758 same-link duplicate `enter_link`s across two independent full
86,400-tick runs with the fixed `randomSeed`** (down from the original ~30/1770, and from
~11-16/1770 after parts 1+2's fixes). No `[STALL]`/`[GTM-STALL]` warnings in either run. 111/111
tests pass. This closes the non-reproducibility bug **for the bus-loop scenario tested** — two
back-to-back runs now produce identical `enter_link`/`leave_link` totals.

**Scope note — not a full architectural fix.** Part 2's finding still stands: the "send a message,
then unconditionally poll `onFinishSpontaneous(Some(currentTick+1))`" pattern, and the
correspondingly-necessary stale-reply guards on its reply handlers, are used throughout
`Movable`/`*SignalHandler`/`*LinkHandler`/`BusStopHandler` for `Car`/`Motorcycle`/`Bicycle`/`Subway`
too, not just for `Bus`'s load-passenger exchange. This session fixed the one instance proven (via
full-scenario instrumentation) to dominate the one bus-loop scenario tested; other vehicle
types/exchanges were not similarly stress-tested and may have their own not-yet-observed instances
of the same missing-stale-guard pattern (check any reply handler that unconditionally reassigns
`state.status`/`state.movableStatus` and reschedules, without first checking whether that status is
still current). The TimeManager-level fix suggested in part 2 (recognizing a `FinishEvent` as stale
relative to an actor's more recent dispatch, instead of "rescuing" it into a phantom tick) would
close the whole family of gaps at once rather than one reply handler at a time, and remains the
more complete option for whoever wants to close this permanently rather than reactively.

**Root cause, part 4 (implemented 2026-07-25): the TimeManager-level generation-counter fix
suggested above.** Closes part 2's remaining architectural gap without touching any actor-level
reply handler.

Added a per-actor **dispatch generation** counter to `LocalTimeManagerBase`
(`dispatchGeneration: mutable.Map[String, Long]`), incremented every time the TM actually sends an
actor a `SpontaneousEvent` (in `sendSpontaneousEvent(tick, identity)`, covering both the shard and
pool dispatch paths, and both `LocalDiscreteEventTimeManager`'s batching and
`LocalTimeSteppedTimeManager`). The new generation value is embedded in the `SpontaneousEvent`
itself (`SpontaneousEvent.generation: Long`, new field, default 0). `SimulationBaseActor` records
whatever generation it most recently received (`currentGeneration`, updated in `handleSpontaneous`)
and echoes it on **every** `FinishEvent` it sends (`FinishEvent.generation: Long`, new field) —
regardless of whether that `FinishEvent` is sent synchronously from `actSpontaneous` or later from
an async reply handler (`handleEnterLink`, `handleLeaveLink`, `handleBusLoadPeople`,
`handleSignalState`, etc.) — since `currentGeneration` isn't touched by
`actInteractWith`/interaction-event processing, only by receiving a new `SpontaneousEvent`.

`LocalTimeManagerBase.finishEvent` now checks, before doing anything else:
```scala
val currentGen = dispatchGeneration(finish.identify.id)
if (!finish.destruct && finish.generation < currentGen) {
  // stale — drop without touching scheduledActors/runningEvents/advanceToNextTick
  return
}
```
`destruct` events always bypass the check (destruction is terminal and one-way; there's no "newer
dispatch" scenario to protect against). `dispatchGeneration` entries are removed on destruct to
avoid unbounded growth over a long run with many transient actors.

**Why this closes part 2's mechanism precisely**: in the tick-3651 triple-`FinishEvent` example,
all three redundant calls (`requestSignalState`, `Movable.leavingLink`, `BusLinkHandler.
handleLeaveLink`) fire while the actor is still on the *same* generation (say `G`, from the 3651
dispatch) and normally collapse harmlessly into one `scheduledActors` entry. The failure required
one of them (typically the Link's reply — an extra round-trip) to arrive **after** the TM had
already dispatched the actor again (tick 3652, generation `G+1`). That straggler still carries
`generation = G` — by definition **provably stale**, since generation `G+1` could only have been
dispatched if the actor's `G`-cycle was already resolved by one of the other two. The fix requires
no case-by-case reasoning about *which* call site is "the real resolver": any `FinishEvent` whose
generation trails the actor's current one is, by construction, chasing an already-superseded
cycle.

**Note this is a different (complementary) mechanism from part 3's fix.** A stale
`handleBusLoadPeople` reply can arrive *after* the bus's own subsequent `enterLink()` poll has
already bumped `currentGeneration` — meaning by the time the stale reply is processed, it echoes
the *same, current* generation, not an older one (the actor's local `currentGeneration` field
reflects whatever `SpontaneousEvent` it most recently received, which can race ahead of a
still-in-flight reply). So this TM-level generation check, by itself, does **not** catch part 3's
mechanism — the `state.status`-based stale-reply guard added in part 3 is still required for that
case. The two fixes protect against genuinely different failure windows and are both kept.

**Validated**: three independent full 86,400-tick runs with the fixed `randomSeed`, no
`[STALL]`/`[GTM-STALL]` warnings in any of them, 0 same-link duplicate `enter_link`s in every run
(1750, 1752, then a third confirmed 0-duplicate run). 111/111 tests pass.

**However — diffing the exact tick-by-tick event sequences between two of these runs (not just
counts) revealed a separate, much smaller-magnitude divergence that neither this fix nor part 3's
was targeting**, and this one **was** root-caused (2026-07-25, same session).

**Root cause, part 5 (characterized, not fixed): the number of harmless "Waiting" poll cycles
before an async reply lands is itself a real-wall-clock race, not a logical value.**
`Movable.enterLink()` sends `EnterLinkData` then immediately polls
(`onFinishSpontaneous(Some(currentTick+1))`). Until the Link's reply actually arrives,
`state.movableStatus` stays `Waiting`, and every dispatch in between falls through to
`Movable`'s own `case Waiting => waitingTicksCounter += 1; onFinishSpontaneous(Some(currentTick+1))`
— a harmless, side-effect-free re-poll (unlike the `Ready`/`WaitingLoadPassenger` cases fixed in
part 1, calling `enterLink()` again here would be wrong, but this fallback correctly doesn't).
Traced with temporary tick+wall-clock-tagged logging (since removed) across two runs of the same
scenario/seed, isolating just the bus's very first `enterLink()` cycle (tick 3601, the first event
of the whole run):

| Run | Waiting polls before reply | Reply processed at | `enter_link` reported at |
|---|---|---|---|
| 1 | 2 (ticks 3602, 3603) | tick 3603 | tick 3604 (`Moving`) |
| 2 | 4 (ticks 3602–3605) | tick 3605 | tick 3606 (`Moving`) |

Both runs send `EnterLinkData` at the *same* tick (3601) and the Link receives it at the *same*
tick (3601) — genuinely identical up to that point. The divergence is entirely in **how many
logical ticks elapse before the Link's real, cross-actor-mailbox reply happens to be processed** —
i.e. a race between two things that run on different clocks: the "poll again next tick" cycle
(governed by the simulation's own tick-advancement machinery) and the actual Pekko message
round-trip for `EnterLinkData`/`LinkInfoData` (governed by real JVM thread scheduling, GC, and
whatever else the process happens to be doing at that wall-clock moment). Since the real round-trip
has no fixed relationship to the logical tick cycle, a slower JVM moment can let 1-2 extra
"Waiting" polls slip in before the reply wins the race, shifting the tick at which the transition
to `Moving` (and thus the `enter_link` report) is recorded — with no lost or duplicated events,
just a shifted timestamp. This is the exact same architectural pattern documented in part 2
(logical polling racing a real async reply) — the only reason it doesn't *also* duplicate a
registration here is that `Waiting`'s fallback handler is a no-op re-poll rather than a
side-effecting action like `enterLink()`.

**Consistent with all observed symptoms**: explains why the very first `enter_link` of the whole
run already differs (this race exists from the bus's first tick, independent of anything the
leaving-link/load-passenger fixes touch); explains the small, slowly-accumulating, plateauing
magnitude (each of the bus's ~875 `enterLink()`/`leavingLink()` waits across a run independently
has a small chance of landing on a different number of extra polls, ~0.1% aggregate effect, with
no mechanism for it to compound the way genuine duplicate registration did).

**Not fixed this session.** The direct fix — stop polling and rely solely on the Link's reply
(`deferFinishSpontaneous()` instead of the immediate poll) — is *exactly* the change already
attempted twice in part 2 (once for `enterLink`, once for `leavingLink`) and reverted both times
after causing real regressions (worse duplicate rate; an actual stall). There is no reason to
expect a third attempt at removing this specific poll would fare differently without first
addressing why removing it breaks other actors' assumptions (see part 2's `Subway` stale-guard
finding). This is deliberately left as a documented, understood, low-severity characteristic —
worth pursuing only if bit-exact reproducibility (not just "no structurally duplicated events") is
required, e.g. via part 2's suggested TimeManager-level generation check extended to also gate
*when* a `Waiting`-fallback re-poll is allowed to fire, or via a more fundamental redesign of the
`enterLink`/`leavingLink` wait pattern — not a quick fix.

**Ruled out as causes** (still valid from the original investigation):
- Concurrent-vehicle contention (the bus is the only vehicle on its own two links in this
  scenario).
- The known "skip-tick" catch-up mechanism (`Rewinding localTickOffset`/`Processing skipped tick`)
  — zero such log lines appear around any captured duplicate.
- **Ruled out (regression, not a fix): making `EnterLink` itself genuinely consistency-critical.**
  Attempted replacing `Movable.enterLink()`'s immediate `onFinishSpontaneous(Some(currentTick+1))`
  with `deferFinishSpontaneous()`, relying solely on the Link's reply
  (`*LinkHandler.handleEnterLink`) to resolve the spontaneous event. **Verified empirically against
  a full 86,438-tick run: duplicate rate got *worse*, not better** — 137 same-link duplicates vs.
  41 before this specific change, in a denser cluster around tick 9,000-35,000. Reverted in full.
  Given part 2's finding above, the likely explanation is that removing this specific poll shifted
  *relative* timing enough to expose the redundant-`FinishEvent`-bump race more often elsewhere,
  rather than fixing anything — consistent with part 2's diagnosis that this whole family of
  "poll-based" reschedules is inter-dependent and can't safely be changed one call site at a time.
- **Ruled out: duplicate registration across two different `LocalTimeManager` pool routees.**
  Instrumented `LocalTimeManagerBase.registerActor`/`scheduleEvent`/`finishEvent`/
  `sendSpontaneousEvent` (temporarily, since removed) to tag every bus-related event with the
  emitting TM's `self.path.name`. Result: the bus registers once and is dispatched exclusively by
  a single TM instance for the entire run. The pool has multiple `LocalTimeManager` routees
  (`htc.time-manager.max-instances-per-node = 4` locally), so this was a plausible mechanism, but
  it isn't what's happening here.

**Diagnostic method that worked, for whoever continues this**: run the tiny bundled
`simulations/input/sqlite_validation_test/simulation_json_baseline.json` scenario locally via
`sbt "runMain org.interscity.htc.main"` with `HTC_SIMULATION_CONFIG_FILE` and
`HTC_MOBILITY_CITY_MAP_FILE` (the latter isn't read from the scenario JSON's own `cityMapFile`
field — separate env var) pointed at it, with the JSON reporter enabled
(`htc.report-manager.default-strategy = "json"`, `enabled-strategies = ["json"]`,
`htc.report-manager.json.directory` pointed at a scratch dir) so `enter_link`/`leave_link` events
land in plain `.jsonl` files that can be counted with a short Python script (per-link
registration-state tracking — a bus can only be "entered" on one link at a time, so two
`enter_link`s for the *same* `link_id` with no intervening `leave_link` is an unambiguous signal;
naive event-list sorting by `tick` alone is **not** reliable across different links at the same
tick). The whole scenario (86K ticks) runs in under a minute locally. For root-causing the
*mechanism* (not just counting symptoms), add temporary stack-trace-tagged logging directly on
`onFinishSpontaneous`/`deferFinishSpontaneous`/`scheduleEvent` in `SimulationBaseActor` (filtered
to the entity under investigation) — this is what surfaced both part 1 and part 2 above; counting
alone (even per-link duplicate detection) cannot distinguish between the two mechanisms or reveal
new ones.

**Why this matters beyond the bus**: `state.registered`-based density speed
(`SpeedUtil.linkDensitySpeed`) is shared by every MESO `Movable` link handler (`CarLinkHandler`,
`BicycleLinkHandler`, `MotorcycleLinkHandler`) and the "send + poll" pattern is used throughout
`Movable`/`*SignalHandler`/`*LinkHandler` — the same class of run-to-run drift could in principle
have affected any of them, not just `Bus`. Part 4's TM-level generation-counter fix is
**general** — it applies to every actor dispatched through `LocalTimeManagerBase`, not just `Bus`,
so it should close the "redundant-poll-arrives-late-and-gets-bumped" mechanism platform-wide
without needing per-vehicle-type verification the way part 3's targeted guard did. **For the
bus-loop scenario actually tested, three independent full-length runs with the same seed now
produce zero structurally-duplicated `enter_link`/`leave_link` events** (parts 1/3/4 combined).
A separate, much smaller-magnitude (~0.1%) timing drift remains unexplained (see part 4's closing
note) — worth chasing for bit-exact reproducibility, but it is not the duplicate-dispatch class of
bug this investigation was scoped to, and does not threaten result validity the way the original
~1.7% structural-duplicate bug did.

## Test Coverage

Effectively none. `src/main/scala` has ~504 files; `src/test/scala` has **exactly one** file
(`system/broker/kafka/abstraction/KafkaAbstractionSpec.scala`). No tests exist for Person, Car,
Bicycle, Motorcycle, Link, Node, TrafficSignal, Bus, or Subway — i.e. none of the core mobility
logic (car-following, lane-change, mode choice, routing) has automated coverage. `CLAUDE.md`
already acknowledges this in its own "Testing" section, but the README/docs don't surface it, so a
reader skimming just the README would assume normal test coverage exists. Treat any change to
`model.hybrid.actor` as needing manual verification (see the `verify` skill / `run` skill) since
there's no test suite to catch regressions.

## Stub / No-Op Components

- **`MachineLearningManager.scala`** (17 lines) is a no-op stub —
  `handleEvent: Receive = { case _ => }`. The README's manager table lists it as if it provides ML
  inference hooks; it currently does nothing.

## Stale Refactor Progress Logs

`docs/REFACTORING_PERSON_PROGRESS.md` and `docs/REFACTORING_STATUS.md` describe an in-progress
`Person.scala` refactor that at one point had 20 active compilation errors and duplicated/obsolete
methods. This was apparently resolved (`docs/REFACTORING_PERSON_COMPLETED.md`; current
`Person.scala` is 392 lines and compiles), but the earlier progress logs remain in `docs/` without
a clear "superseded" marker. A reader skimming `docs/` chronologically could reasonably conclude
the refactor is still broken. Either mark the in-progress logs as historical/completed at the top,
or fold their still-relevant content into `REFACTORING_PERSON_COMPLETED.md` and remove the rest.

## Minor Drift

- **REST API**: `POST /api/v1/simulation/resume` exists in `SimulationRoutes.scala` but isn't
  listed in `docs/API.md`'s endpoint table. Otherwise the documented endpoints
  (`/health`, `/simulation/status|start|pause|stop`, `/scenarios`, `/scenarios/{name}/load`,
  `/settings`) match the real routes.
- **Diagrams**: `docs/diagrams/HTC_EXECUTION_FLOW.drawio`/`.mmd` predate the other per-agent
  diagrams by over a week, and 324 files under `src/main/scala` have newer mtimes than
  `docs/PERSON_AGENT.md` — a plausible (not confirmed) sign that some agent docs have drifted from
  recent refactors. Worth a periodic pass to confirm the `docs/*_AGENT.md` files still match
  current handler behavior, especially after any `htc-actor-dev` work lands.

## What Holds Up Well

- Terraform (`terraform/gcp/main.tf`, `vm/main.tf`) and Kubernetes manifests (`k8s/*.yaml`,
  `k8s/monitoring/*.yaml`) are real, filled-in infrastructure — not scaffolding.
- Prometheus metrics and ClickHouse reporting are genuinely wired (`core/metrics/`,
  `core/actor/manager/report/ClickHouseReportData.scala`).
- The REST API, Person/Car/Bus/Subway agent behavior, and the manager hierarchy described in the
  README are accurate for the `model.hybrid.actor` package that's actually in active use.
