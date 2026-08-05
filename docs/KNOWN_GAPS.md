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

## `scheduleEvent`'s Past-Tick Guard Discards Real Causal Time When Actors Share an LTM Instance (Found 2026-07-30, Fixed 2026-07-30 — Partial Improvement, Residual RMSE Unexplained)

**Different mechanism from the section above — do not assume part 4's generation-counter fix
already covers this.** Part 4 protects `finishEvent` against a *stale* `FinishEvent` from an
actor's own earlier dispatch generation arriving late. This gap is in the sibling guard,
`LocalTimeManagerBase.scheduleEvent` (lines ~103-121), and the request it discards is not stale —
it's the actor's own next legitimate tick, lost because a *different* actor sharing the same
`LocalTimeManager` pool instance advanced the shared `localTickOffset` further in the meantime.
`ScheduleEvent` is a protobuf message (`org.htc.protobuf.core.entity.event.communication.
ScheduleEvent`, no `generation` field in `src/main/protobuf/core/entity/event/communication.
proto`), unlike the plain-Scala `FinishEvent`/`SpontaneousEvent` that part 4's fix touched — so
extending that fix here isn't a drop-in reuse, it needs its own design (see below).

**Found investigating two HTC-vs-SUMO Krauss car-following accuracy artifacts** in
`tools/sumo_validation/` (a new validation harness comparing HTC MICRO-mode output against real
SUMO on hand-authored scenarios — see `tools/sumo_validation/README.md`): a fixed-looking ~10-13
simulated-second startup latency on early vehicles, and a much larger ~106-119 tick stall when a
previously-idle `Link` reactivates mid-run. Root-caused via a live run instrumented with
`-Dpekko.loglevel=DEBUG` (the existing `logDebug` calls in `scheduleEvent` are gated by Pekko's
log level, separate from logback, which is why prior `htc_run.log`s never showed this) — captured
directly:
```
ScheduleEvent tick=63 is at/behind localTickOffset=180; bumping to 181
```
`link_bc` asked to be rescheduled at tick 63 (its own next tick after its idle grace period).
The `LocalTimeManager` instance it shares with other, busier actors (Cars on other links; the LTM
pool is small — `htc.time-manager.max-instances-per-node = 4` locally, confirmed only 4 routees
for a 6-vehicle run) had already advanced `localTickOffset` to 180 servicing those other actors.
The guard at `LocalTimeManagerBase.scala:114-121` can't distinguish "this tick was already
processed and cleared for this specific actor" (the case it was written to protect against, per
its own comment) from "this actor's own request is genuinely earlier than the shared clock only
because unrelated actors on the same LTM instance ran ahead of it" — and bumps both cases the same
way, silently discarding 118 ticks of real causal time for `link_bc` in this example. Matches the
Parquet evidence exactly: nothing recorded for the affected vehicles between tick 70 and 181 in
`tools/sumo_validation/output/scenario_b/htc_parquet/`.

**Corrects an earlier hypothesis for the smaller artifact**: it isn't fixed JVM/Pekko-cluster
warm-up — it's the same registration-time bump (`ScheduleEvent tick=0 is at/behind
localTickOffset=0; bumping to 1` was also captured, for a car's initial registration), whose
magnitude is a genuine real-time race (how far the shared LTM's clock has advanced when the new
actor's registration is processed), which is why repeat runs of the same scenario showed
different-sized offsets for the same vehicles rather than a constant.

**Fixed via the proposed per-actor watermark, plus one keying bug caught during validation.**
Added `highestProcessedTick: mutable.Map[String, Tick]` to `LocalTimeManagerBase`, updated
whenever `sendSpontaneousEvent(tick, identity)` actually dispatches an actor (keyed on
`identity.id`, mirroring `dispatchGeneration`, and cleared on destruct alongside it).
`scheduleEvent`'s guard (`LocalTimeManagerBase.scala:112-136`) now bumps only when
`event.tick <= highestProcessedTick(actorId)` — i.e. only when *this* actor's own dispatch history
says the tick is stale — instead of comparing against the LTM-wide `localTickOffset`. `nextTick`
(`~281-297`) no longer filters `scheduledActors`/`scheduledTicksOnFinish` by `>= localTickOffset`,
since that filter was exactly what hid a legitimately-preserved earlier tick from
`reportGlobalTimeManager`; `processTick`'s pre-existing skip-tick catch-up logic
(`scheduledActors.keys.filter(_ <= tick).minOption`) already knows how to process an entry behind
`localTickOffset` once the GTM re-syncs this LTM to it, confirmed by reading
`LocalDiscreteEventTimeManager.processTick` and `GlobalTimeManager.calculateAndBroadcastNextGlobalTick`
(the latter takes `scheduled.map(_.tick).min` across LTMs, so an earlier-than-`localTickOffset`
report is an expected, supported case, not a foot-gun).

**Keying bug caught by validation, not by review — worth flagging for future changes to this
file.** `ScheduleEvent.actorRef` is *not* a stable actor-id key: `registerActor`
(line ~107) sets it to the entity id, but `SimulationBaseActor.scheduleEvent(tick)`
(`SimulationBaseActor.scala:759-775` — the actual self-reschedule path that produced this gap's
`link_bc` log evidence) sets it to `getPath`, the actor's Pekko path. A first pass keyed
`highestProcessedTick` off `event.actorRef` directly; it compiled and passed all 111 existing
specs, but the guard became a silent no-op for every self-reschedule (write side keys on
`identity.id` in `sendSpontaneousEvent`, read side never matched). Caught only by rerunning
`tools/sumo_validation/run_validation.py` and seeing no RMSE change from the pre-fix baseline.
Fixed by keying on `event.identify.map(_.id).getOrElse(event.actorRef)` instead, matching the key
`sendSpontaneousEvent` writes under.

**Validation performed:**
- `sbt compile` and `sbt test`: clean, all 111 existing specs still pass, both before and after
  the keying fix.
- `tools/sumo_validation/run_validation.py --scenarios a b` (path is `tools/sumo_validation/`, not
  `tools/sumo-validation/`, and the script is `run_validation.py` singular): real, substantial
  improvement on some vehicles, not full closure. Scenario A: `car_0` position RMSE 18.5m → 4.6m
  (now matches the unaffected baseline exactly); max travel-time delta 7s → 3s. Scenario B:
  `car_4` RMSE 75.5m → 6.8m; max travel-time delta 119s → 25s (down ~80%). But `car_1` in scenario
  A (~56-69m across repeated runs) and `car_0`/`car_1`/`car_2` in scenario B (~90-150m) remain
  elevated well above the ~4.6-6.8m baseline, and vary noticeably run-to-run even with the fix
  applied and the same nominal seed — this looks like a **separate, still-open issue**, not
  something this specific guard-plus-`nextTick` change was scoped to fix. Worth a fresh
  investigation rather than folding into this section.
- 86,400-tick reproducibility baseline (`simulations/input/sqlite_validation_test/
  simulation_json_baseline.json`, JSON reporter, method as in parts 4/5 above): ran to completion
  both before and after this fix (max tick observed 86,362 of 86,400 across both runs — the
  simulation itself finishes in under a minute; the `timeout` calls in ad-hoc verification runs
  were catching Pekko's slow *coordinated-shutdown* teardown afterward, not a simulation stall).
  Per-link duplicate-`enter_link`-without-intervening-`leave_link` check: **exactly one duplicate
  in both the pre-fix and post-fix run** (same subway train, different tick/link each run) — i.e.
  this fix neither introduces nor removes duplicate dispatches; the single duplicate is the
  pre-existing ~0.1% residual timing drift already flagged as unexplained in part 4's closing
  note above, not a new regression from this change.

**Remaining open question for whoever picks this up next**: what's causing the still-elevated
RMSE on the specific vehicles listed above. Given the fix here closed the *documented* mechanism
(confirmed by the `car_0`/`car_4` numbers landing exactly on the unaffected-vehicle baseline) but
left other vehicles untouched, the leftover pattern is probably a different bug, not a partial fix
of this one — recommend a fresh instrumented run (same DEBUG-log method used to find this gap)
scoped to `car_1` (scenario A) and `car_0`/`car_1`/`car_2` (scenario B) specifically, rather than
re-opening `scheduleEvent`/`nextTick` again without new evidence pointing there.

**Why this matters beyond these two small scenarios**: LTM pool sharing get more likely, not
less, as scenarios scale up (more actors, same small pool), so this class of lost-causal-time bug
plausibly affects large scenarios (e.g. São Paulo-scale) more than the tiny ones that surfaced it
— just less visibly, since there's no SUMO ground truth to diff against there. The underlying
Krauss car-following math itself was *not* implicated: vehicles unaffected by this scheduling bug
in the same validation runs tracked SUMO closely (~4.6 m position RMSE over a ~300 m trip).

**Update 2026-08-04: found the sibling code path this fix missed, likely explaining the ~0.1%
residual drift referenced throughout this section.** The fix above only patched `scheduleEvent`
(the `ScheduleEvent` message handler, used by `SimulationBaseActor.scheduleEvent(tick)`) to compare
against the actor's own `highestProcessedTick` watermark instead of the shared `localTickOffset`.
`finishEvent`'s own inline handling of `finish.scheduleTick` (`LocalTimeManagerBase.scala`, the
`onFinishSpontaneous(Some(tick))` path — used far more pervasively than `scheduleEvent` itself,
since it's how nearly every actor re-registers after resolving a wait, including e.g. a `Car`'s
`mesoExitTick`) still used `if (tick <= localTickOffset) localTickOffset + 1 else tick` unchanged.
Same mechanism, same silent-mis-scheduling failure mode (not a discard — the actor is still
scheduled, just at a later, physically-incorrect tick — whenever a busier sibling actor on the same
LTM instance has pushed `localTickOffset` past the correctly-computed tick in the meantime), just
on the far more heavily-used code path. Found while designing the congestion-propagation
link-capacity wait (`docs/CONGESTION_PROPAGATION_DESIGN.md`) — that feature creates much longer,
less-bounded dormancy periods than anything previously exercising this path, making the bug both
more likely to trigger and larger in magnitude. Fixed by applying the identical per-actor-watermark
guard to `finishEvent`'s `scheduleTick` handling. Not yet re-run against the SUMO validation harness
to confirm this closes the residual RMSE gap — worth doing before considering this section fully
resolved.

## `TrafficSignalPhaseHandler` Never Transitions Past Its First Phase (Found 2026-07-30, Fixed 2026-07-31)

**Root cause: a code bug, not a data/config bug.** The exported traffic-signal JSON (`phases:
[{greenStart: 0, greenDuration: 30}], cycleDuration: 60`) correctly expresses "green for the
first 30s of a 60s cycle" — the same spec SUMO's `.tll.xml` uses, and SUMO cycles correctly on it.
The bug was entirely in `TrafficSignalPhaseHandler.handlePhaseTransition`
(`model/hybrid/support/trafficsignal/TrafficSignalPhaseHandler.scala`): the formula computing when
to next re-evaluate the signal,
`nextCycleStart = ((ticksSinceStart / cycleDuration) + 1) * cycleDuration`, always jumps to the
start of the next **full cycle** (tick 60, 120, ...), never to the phase's own Red-transition
boundary (`greenStart + greenDuration` = tick 30). For a phase with `greenStart = 0`, every one of
those full-cycle landing points is itself inside the Green window (`currentCycleTick = 0`), so the
computed state is Green every single time the actor re-checks — the signal notifies Green once at
tick 0 and then never changes again for the rest of the simulation.

**Found while investigating the residual scenario-B RMSE gap left after the `scheduleEvent`
past-tick-guard fix above.** Instrumented via the JSON reporter (not logs — Pekko's `-D
pekko.loglevel=DEBUG` alone doesn't reach the actual log line; this repo's `logback.xml` pins
`root level="INFO"` and Pekko's `Slf4jLoggingFilter` defers to it, so DEBUG-level `logDebugFn`
calls need a `-Dlogback.configurationFile=...` override, not just the Pekko system property):
across a full 240-tick run of `tools/sumo_validation`'s scenario B (60s signal cycle), exactly
**one** `signal_phase_change` event was ever recorded (Green, tick 0, `next_tick=60`) — no Red
transition, ever. Confirmed further that `NodeEventHandler.handleRequestSignalState`'s only
report()-emitting branch (`state.signals.get(identify.id) => Some(sig)`) was never reached either
— zero `node_signal_requested` events across the whole run — so every car's signal-state query
fell through to the "uncontrolled intersection, assume Green" fallback the entire time. The signal
had **zero** behavioral effect on any vehicle in that scenario, not just a delayed one.

**Not the cause of the ~20-40 tick stalls also observed on `car_0`/`car_3`/`car_4` in scenario B**
(see the section above's validation notes). Since the signal never gated anyone, those stalls are
a separate, still-open bug, most likely in `CarSignalHandler`'s request/retry round-trip
(`Car.scala:280-290`, `MaxSignalStateRetries = 100`) — worth a fresh look, but out of scope for
this fix.

**Fix**: replaced the next-tick formula with one that finds the earliest upcoming transition
boundary (`greenStart` or `greenStart + greenDuration`, for every phase) after the current
cycle-relative tick, wrapping to the next cycle only when a boundary has already passed within
this one:
```scala
val transitionPoints = state.phases.flatMap(p => Seq(p.greenStart, p.greenStart + p.greenDuration))
val nextTickTime = currentTick + transitionPoints.map { point =>
  val delta = point - currentCycleTick
  if (delta > 0) delta else delta + state.cycleDuration
}.min
```
Also simplified `remainingTime`/`nextTick` on `SignalState` to derive from this same corrected
`nextTickTime` (previously computed from the same broken `greenStart + greenDuration -
currentCycleTick` expression, which could go negative once the signal actually reached Red), and
removed a dead `changedOrigins` local (computed, never read). `nextTick` is not report-only — it's
consumed by `Car`/`Bus`/`Motorcycle`/`Bicycle` `SignalHandler`s to compute how long to wait at Red
— confirmed via grep before changing its derivation.

**Validation**:
- Added `TrafficSignalPhaseHandlerSpec` (`src/test/scala/model/hybrid/support/trafficsignal/`) —
  pure handler test, no Pekko, matching this repo's "prefer testing handlers over actors"
  convention. Asserts the exact scenario-B cycle (Green at 0, Red at 30, Green at 60, Red at 90,
  ...) alternates correctly across multiple cycles — the previous code would have failed this
  after the very first transition. 5 new specs, all passing; full suite (116 specs total) green.
- `sbt compile`: clean.
- `tools/sumo_validation/run_validation.py --scenarios a b`: the fix is logically verified correct
  in isolation (unit tests above), but scenario B's end-to-end RMSE did **not** show a clean
  aggregate improvement across two repeated runs (mean RMSE ~74-76m vs. ~65m before this fix; max
  travel-time delta improved on one run, 25s, but not the other, 57s). This is expected, not a
  regression from this change: scenario A (which has **no** traffic signal at all, so this fix
  cannot affect it) shows the same magnitude of run-to-run car-level RMSE swings (e.g. `car_2`
  jumped from its usual ~4.6m to 123m in one of the two runs) — confirming the swings are driven
  by the residual scheduling nondeterminism noted in the section above, compounded now by the
  still-open `CarSignalHandler` stall bug actually mattering more once the signal genuinely cycles
  (cars now really do queue at Red instead of sailing through on the old always-Green bug, so any
  latent race in the queue/retry path is more exposed than before, not less).
- 86,400-tick reproducibility baseline: not re-run — confirmed
  `simulations/input/sqlite_validation_test/` contains zero `TrafficSignal` actors, so this change
  is inert there.

**Update 2026-07-31: found the actual reason the phase-handler fix alone didn't move scenario B's
RMSE — a second, independent bug, this time in the validation harness's *data export*, not engine
code.** `tools/sumo_validation/htc_export/export.py`'s `_node_connections_and_signals` built
`Node.connections` keyed by `TrafficLight.controlled_link_id` directly — which is the SUMO-style
**approach edge** (`link_ab`, the link a car is arriving *on*, matching `.tll.xml`'s own
convention). But `NodeEventHandler.handleRequestSignalState` looks connections up by
`data.targetLinkId`, which `CarSignalHandler.requestSignalState` populates from
`getNextLinkFn()` (`Movable.getNextLink`) — the **outgoing** link a car is about to *enter next*
(`link_bc`). Confirmed via the exported `nodes_0.json`: node `n_b`'s `connections` had exactly one
entry, keyed `"htcaid:link;link_ab"`, which `handleRequestSignalState` could never match against a
query for `link_bc` — every single request across the whole run fell through to the "no connection
entry — uncontrolled intersection, assume Green" fallback, regardless of the (now-correctly-cycling)
signal phase. This is why the phase-handler fix alone produced no behavioral change for scenario
B's cars: the signal was cycling correctly internally, but genuinely disconnected from every car's
decision, both before and after that fix.

Fixed by keying `connections` on each node's *outgoing* link(s) instead (computed the same way the
exporter already computes `incoming_links` for the `Node.links` field, just mirrored on
`from_node`). Updated the one existing test that had encoded the old (wrong) key
(`test_export_htc_scenario_b_wires_traffic_signal_to_node_connection` in
`tools/sumo_validation/tests/test_htc_export.py`) to assert the corrected `link_bc` key instead.
All 22 harness tests pass.

**Result after both fixes (phase-cycling + connection-keying)**: scenario B's mean position RMSE
dropped from ~65-75m to **~25.6-25.8m** (stable across two repeat runs) — cars that now correctly
see and react to Red track SUMO's position closely while actually moving (`car_2`: 51m→7.4m,
`car_4`: 68m→6.8-16.3m). This is the real, load-bearing fix for the signal-gated portion of
scenario B; the earlier phase-handler-only fix was necessary but not sufficient on its own.

**New issue surfaced by this fix, not previously visible (the signal had zero effect before, so
nothing could react to it wrongly): whichever 1-2 cars end up genuinely queued at Red overshoot
their travel time enormously** — `+161` to `+167`s deltas in both repeat runs (vs. SUMO), for
different specific cars each run, despite good *position* RMSE (meaning they track correctly while
moving, then apparently wait far longer than warranted before being released). This is reproducible
in magnitude, not the small run-to-run noise discussed elsewhere in this doc, and is most likely in
`CarSignalHandler.handleSignalState`'s wait-duration calc
(`adjustedNextTick = data.nextTick + queuePosition * headwayTicks`) or in
`NodeEventHandler`'s `signalWaitingCounts` bookkeeping (reset-on-Green logic, keyed by the
connections map this fix just repointed) — not investigated further; flagged here rather than
expanding scope past "fix the signal bug" again.

**Next step for whoever continues this**: instrument `queuePosition`/`signalWaitingCounts` directly
(report their value on every `RequestSignalState` reply) for the specific cars that overshoot, to
see whether the queue count itself is wrong (e.g. never reset between cycles) or `data.nextTick`
is stale by the time the car finally re-queries. The separate `CarSignalHandler` stall-retry
concern noted in the previous version of this section is superseded by this more concrete lead —
start here instead.

## `sumo_validation` Scenario A `car_1` RMSE Anomaly: Root-Caused to Part 5's `enterLink()`-Poll Race, Plus One New, Now-Fixed `CarMicroHandler` Bug (Found/Fixed 2026-07-31, Corrected 2026-08-01)

**Path correction first**: despite every reference in this doc reading `tools/sumo_validation/`,
the harness actually lives at `<htc-platform>/tools/sumo_validation/` — a **sibling** of this repo
(`hyperbolic-time-chamber/`), not a subdirectory of it. `hyperbolic-time-chamber/tools/` does not
exist. Run it from the platform root, one level up from this repo.

**Found and fixed first: the harness's own SUMO runner had a real, silent bug making every prior
`run_validation.py` invocation compare HTC against a stale SUMO trace.**
`runners/sumo_runner.py`'s `run_sumo` sets `cwd=sumocfg.parent` but then also passes the *same*
(possibly repo-root-relative) `sumocfg` path as the `-c` argument — SUMO looks for
`output/scenario_a/sumo/scenario.sumocfg` *inside* `output/scenario_a/sumo/`, doubly nested, which
never exists, so `sumo` exits 1 with `Error: Could not access configuration ...` every single run.
`run_validation.py` only logs this as a `WARNING` and continues, silently reusing whatever
`scenario_fcd.xml` happened to already be on disk (a 2026-07-30 file, confirmed by its mtime) as if
it were fresh ground truth. Every metrics run between 2026-07-30 and this fix was unknowingly
diffing HTC's (evolving, being-fixed) output against a frozen, un-refreshed SUMO baseline. Fixed by
passing `sumocfg.name` (the bare filename) instead of the full path, since `cwd` is already the
config's own directory. Added `tests/test_sumo_runner.py` (previously zero coverage for this
module) — one regression test reproducing the exact double-resolution failure via a fake `sumo`
executable that only succeeds on a bare filename, one sanity check that genuine failures are still
surfaced. Harness test count: 22 -> 24, all passing.

**The primary chase-down**: `car_1` in scenario A (~56-69m position RMSE vs. the ~4.6m baseline
every other unaffected car in both scenarios consistently hits) has **no traffic signal in its
scenario at all**, ruling out every already-documented signal-related mechanism above. Root-caused
by comparing `journey_started` (the tick a car's trip logically begins) against `enter_micro_link`
(the tick it actually starts moving) per car, across three independent runs, read directly from
the harness's own Parquet output — not assumed:

```
Run 1: car_0 journey_started=0  enter_micro_link=6   car_1 journey_started=6  enter_micro_link=6
Run 2: car_0 journey_started=0  enter_micro_link=13  car_1 journey_started=6  enter_micro_link=13  car_2 journey_started=12 enter_micro_link=13
Run 3: car_0 journey_started=0  enter_micro_link=10  car_1 journey_started=6  enter_micro_link=11
```

This is **the same mechanism as the "`scheduleEvent`'s Past-Tick Guard..." section above, still
not fully closed for an actor's very first dispatch** despite that section's 2026-07-30 fix: each
car's actual first micro-link entry lags its own `journey_started` tick by a real-time-race-sized
delay (6, 7, 10, and 13 ticks observed for `car_0` across different runs — not a fixed constant,
which is itself consistent with a wall-clock race in the shared `LocalTimeManager` pool, not a
deterministic logic bug). Scenario A's four cars are meant to depart 6 seconds apart specifically
to exercise Krauss gap-closing (see `tools/sumo_validation/README.md`'s scenario description) — but
when two cars' independent entry delays happen to land within 0-2 ticks of each other (as they did
in all three runs above), the follower's actual starting gap to its leader collapses from the
intended "6 seconds of free-flow travel" (tens of meters) to almost nothing, forcing genuine (given
the corrupted input, *correct*) Krauss emergency braking and a slow multi-tick recovery that
dominates that car's own position RMSE for the rest of its trip. Whichever car in a given run
*doesn't* get caught in a same-tick pile-up lands at exactly the same ~4.6m baseline as every other
unaffected car — confirming the Krauss car-following math itself is not implicated, only the timing
of when cars are handed their first real tick.

**Explicitly ruled out** (with direct evidence, not assumption):
- **Harness car-id mapping bug**: `journey_started` ticks tie out exactly to each car's configured
  `depart_tick` (`scenario/scenario_a.py`: `car_i` departs at `i * 6`) on both the HTC and SUMO
  sides — car identities are not being crossed or misaligned between the two parsers.
- **Leader/follower index bug in `DefaultMicroSimulationStrategy.processMicroLane`**: read the
  code directly (`model/hybrid/micro/strategy/DefaultMicroSimulationStrategy.scala:149-151`) — a
  vehicle's leader is `vehicles(i-1)` from a `Queue` that is re-sorted by descending position after
  *every* sub-tick (`vehicles.sortBy(v => -v.position)`, line 227-231), so the leader assignment is
  always positionally correct regardless of vehicle-ID order. Confirmed empirically too: in every
  run, whichever car is physically ahead is the one setting the pace, never the reverse.
- ~~**The residual ~0.1% `Waiting`-poll timing drift documented in part 5 above**: that mechanism
  produces rare (one-per-86,400-tick-run) duplicate dispatches later in a simulation, not a
  registration-time-only delay of 6-13 ticks affecting a car's very first dispatch. Different
  trigger, different magnitude, different point in the actor's lifecycle.~~ **Corrected
  2026-08-01 — this bullet was wrong; see the "Correction" subsection immediately below.** Part 5's
  *mechanism* (poll-vs-async-reply race) is exactly what produces this gap; only part 5's own
  *worked example* (2-4 polls, mid-run bus) had smaller magnitude than car_1's 6-13 polls, which is
  a difference in degree (four cars registering into one Link actor's mailbox in a tight window vs.
  a single bus with no contention), not a difference in mechanism or in which point of an actor's
  lifecycle it can strike.

### Correction (2026-08-01): the "still-open" framing above was wrong — this *is* part 5's mechanism, not a separate one

Re-investigated per a direct instruction to verify the "ruled out" bullet above with live
instrumentation rather than the static trace this section originally relied on. Added temporary
`Console.err` tick+`System.nanoTime()` tracing (same method as parts 1/2/5's own investigations,
since removed) at four points: `Movable.actSpontaneous`'s entry (status), `Movable.enterLink()`'s
`EnterLinkData` send, the `Waiting`-case re-poll, and `CarMicroHandler.handleMicroEnterLink` (where
`enter_micro_link` is actually reported). Ran `tools/sumo_validation`'s scenario A twice with this
build.

**Direct, unambiguous result — the entire `journey_started`-to-`enter_micro_link` gap is made of
`Waiting`-case re-polls, one per tick, with no other event in between:**

```
Run 2 (13-tick gap on car_0, the largest observed):
tick=0  car_0 ENTERLINK-SEND (journey_started)   status set to Waiting
tick=1..12  car_0 WAITPOLL (waitingTicksCounter 0..11)   -- 12 consecutive re-polls, one per tick
tick=13 car_0 MICROENTER (enter_micro_link reported)

Same run, car_1 (departs tick=6, a 7-tick gap):
tick=6  car_1 ENTERLINK-SEND      tick=7..12  car_1 WAITPOLL (counter 0..5)      tick=13 car_1 MICROENTER
```

Car_0's real entry (delayed 0->13 by its own poll race) and car_1's real entry (delayed 6->13 by
its *independent* poll race) land on the **exact same tick**, collapsing their intended 6-tick
departure gap to zero and forcing the Krauss emergency-braking recovery that dominates car_1's RMSE
(68.5m vs. the ~4.6m baseline) in that run. Car_2 (entering at tick 14, one tick behind car_1's 13)
picked up an elevated RMSE too (20.5m) in this same run for the identical reason, one tick less
severe. Car_3, whose poll race happened to finish with zero extra ticks (SEND and MICROENTER both
at tick 18, no contention left in the mailbox by then), landed exactly on the unaffected baseline.
A second run showed the same pattern with different magnitudes (car_0: 0->8, 8 ticks; car_1: 6->8,
2 ticks — same-tick collision again, same RMSE profile) — confirming, as part 5's own bus example
already found, that the number of extra `Waiting` polls before the async reply lands is a real
wall-clock race, not a fixed constant.

Also confirmed why no `TRACE-ENTERLINK-REPLY` (`Car.actHandleReceiveEnterLinkInfo`, the MESO
`ReceiveEnterLinkInfo` path part 5's own bus example used) ever fired in either run:
`LinkVehicleFlowHandler.handleEnterLinkMicro` (not `handleEnterLinkMeso`) answers `EnterLinkData`
for a MICRO-mode link like scenario A's, replying with `MicroEnterLinkData` directly instead of
`LinkInfoData`. Same race, same `Movable.enterLink()`/`Waiting` poll pattern — just resolved via a
different reply message on the MICRO branch than the MESO branch part 5 happened to trace.

**What this means for the "explicitly ruled out" bullet above and the "Status" note below**: they
were wrong. This is not a "registration/first-dispatch" mechanism distinct from part 5 — it is
part 5's poll-vs-async-reply race, observed for the first time at an actor's *very first*
`actSpontaneous` dispatch (which this investigation confirms fires exactly on the actor's own
`depart_tick`/`startTick`, with no additional delay *before* the first dispatch — `TRACE-ENTRY`
for `car_0` landed at tick 0 precisely). Scenario A's four cars registering into the same `Link`
actor's mailbox within a 0-18-tick window is what amplifies the race's magnitude (6-13 polls
observed here vs. part 5's 2-4) and, combined with this harness's Krauss gap-closing scenario being
sensitive to timing in a way part 5's duplicate-dispatch-counting validation never checked, is what
makes the resulting position error large and visible for the first time.

**Consistent with, not contradicting, the "`scheduleEvent`'s Past-Tick Guard..." section's own
2026-07-30 fix and its "remaining open question."** That fix targets a different guard
(`scheduleEvent`'s `localTickOffset` comparison) protecting against a different failure (an
actor's own legitimate next-tick request getting bumped forward because unrelated actors on the
same LTM advanced the shared clock first) — real, and correctly fixed, but not what's producing
*this* gap. This gap is entirely on the `enterLink()`/`Waiting`-poll side, which that fix never
touched.

**No code change made here — per the standing guidance in part 5 and part 2 above, do not attempt
to remove `Movable.enterLink()`'s poll a third time.** Both prior attempts (see part 2: `Subway`
stall; part 5: 137 vs. 41 duplicates, worse not better) are unrelated to *this* scenario but prove
the same architectural point: every reply handler in this family assumes a fallback poll exists
elsewhere, and removing one poll site in isolation has repeatedly caused regressions rather than
fixes. No new evidence surfaced during this investigation that changes that risk assessment — the
TimeManager-level "recognize a stale `Waiting`-fallback re-poll" approach part 5 sketches (extending
the existing `dispatchGeneration`/`highestProcessedTick` watermark machinery to also gate *when* a
`Waiting` re-poll is allowed to fire, not just whether a `FinishEvent`/`ScheduleEvent` is stale) is
still the only avenue that doesn't require touching every interacting reply handler at once, and
remains unimplemented, deliberately, for the same reason part 5 left it that way: it needs its own
dedicated investigation and full 86,400-tick reproducibility validation, not a delta landed
alongside a documentation correction.

**Validation for this correction**: `sbt compile` clean; `sbt test` — 119/119 specs pass (all
pre-existing, no new specs added — this session made no production code change, only temporary
instrumentation, added and fully reverted). All four instrumentation call sites confirmed removed
(`git diff` against `HEAD` for `Movable.scala`/`Car.scala`/`CarMicroHandler.scala` is empty).
`tools/sumo_validation/run_validation.py --scenarios a b` re-run against the freshly rebuilt,
instrumentation-free jar to confirm no behavioral change from the trace-and-revert cycle: scenario
A car_1 RMSE 68.463m, scenario B unaffected — both consistent with the numbers already on record
in this doc, confirming the instrumentation left no residue.

**Independent, real, now-fixed bug found along the way — contributes to, but does not by itself
explain, the anomaly's full magnitude.** `CarMicroHandler.handleMicroEnterLink`
(`model/hybrid/support/car/CarMicroHandler.scala`) computed a car's initial MICRO-mode velocity as
`state.microState.map(_.velocity).getOrElse(speedLimitMs * 0.8)`. The `.map` branch can **never**
actually fire: `handleMicroLeaveLink` always calls `state.deactivateMicroMode()` (clearing
`microState` to `None`) on link exit, before the next `handleMicroEnterLink` runs — so every single
micro-link entry, first-ever or chained, fell into the `getOrElse` fallback and started the car
cruising at a flat 80% of the link's speed limit (40 km/h on a 50 km/h link), regardless of whether
it had genuinely never moved before (SUMO's default `departSpeed=0` — cars there visibly accelerate
from a dead stop) or had just been cruising at the end of a previous chained micro-link (e.g.
scenario B's `link_ab` -> `link_bc`), whose real exit velocity was silently discarded every time.
Fixed by reading `journeyReporter.sumoArrivalSpeed` instead — it is `0.0` by construction until a
car's first `handleMicroLeaveLink` ever fires, then holds the real exit velocity from then on
(already being written correctly by both `handleMicroUpdate` and `handleMicroLeaveLink`, just never
read back on the next entry). Same dead-fallback pattern found, **not fixed** (out of scope — not
exercised by this harness, not independently verified), in `BusMicroHandler.scala:51`
(`speedLimitMs * 0.7`) and, more crudely, hardcoded unconditionally with no `state.microState` check
at all in `MotorcycleMicroHandler.scala:45` and `BicycleMicroHandler`'s equivalent
(`speedLimitMs * 0.9`).

**Why fixing this alone did not close the `car_1` gap**: with initial velocity now correctly `0.0`
for a car's first-ever movement, two cars still entering the same link within 1-2 ticks of each
other (per the still-open scheduling delay above) now both depart from a dead stop instead of
already cruising at 11.1 m/s — this changes the specific shape of the resulting Krauss interaction
but does not remove the near-zero-gap collision itself. Re-running the harness after this fix
(Run 3's numbers above) still shows `car_1` at 68.7m RMSE, materially unchanged from before the
fix (66.7m) — exactly as this analysis predicts, since the dominant cause (entry-timing collision)
is untouched by a velocity-default fix.

**Validation**:
- `sbt compile` and `sbt test`: clean. Added `CarMicroHandlerSpec`
  (`src/test/scala/model/hybrid/support/car/`) — 3 new specs, no Pekko (pure handler test, per
  this repo's convention): a car's first-ever micro link departs at rest (0 m/s); a second,
  chained micro link resumes at the car's real carried-over exit velocity, not a flat fraction of
  speed limit; the reported `initial_velocity` event field matches what's actually stored. Full
  suite: 119 specs (116 pre-existing + 3 new), all green.
- `tools/sumo_validation` end-to-end, both before and after the `CarMicroHandler` fix (with the
  `sumo_runner.py` fix already applied so both comparisons are against genuinely fresh SUMO
  ground truth): scenario A `car_1` RMSE 66.7m -> 68.7m (no material change, as predicted above).
  Scenario B did show real improvement on some vehicles from this fix alone — `car_2` 52.2m ->
  4.3m, `car_4` 74.4m -> 6.8m — consistent with the fix's actual mechanism (chained-link velocity
  carryover matters most on scenario B's two-link `link_ab` -> `link_bc` route). Scenario B's
  travel-time deltas got *larger* for the cars genuinely queued at Red (up to +203s, vs. the
  already-documented +161-167s in the section above) — consistent with, and not a new instance of,
  the already-flagged, explicitly-out-of-scope "cars queued at Red overshoot their travel time"
  issue: correcting the departure-speed bug means more cars now *genuinely* reach and queue at the
  signal (instead of sailing past mid-cruise), so that pre-existing bug's effect is more exposed,
  not newly introduced.
- 86,400-tick reproducibility baseline: not re-run for this fix — it only changes which value is
  used as a micro-link's initial velocity (a value that, per the dead-code analysis above, could
  never previously be reached any other way), with no change to control flow, tick scheduling, or
  message ordering, so it cannot affect the duplicate-dispatch/reproducibility concerns tracked
  elsewhere in this doc.

**Status (corrected 2026-08-01 — see the "Correction" subsection above for the instrumented
evidence)**: root cause identified and confirmed. It is **not** a distinct registration/first-
dispatch mechanism, and it is **not** the `scheduleEvent`-past-tick-guard mechanism from the section
above (that guard was checked and correctly fixed on 2026-07-30, but protects a different code
path). It is part 5's already-documented `Movable.enterLink()`-send-then-poll race
(`onFinishSpontaneous(Some(currentTick+1))` racing the real, cross-actor-mailbox `EnterLinkData`/
`MicroEnterLinkData` round-trip), now observed to also strike an actor's very first dispatch and,
in this harness's multi-car-into-one-Link-mailbox scenario, to reach 6-13 polls instead of part 5's
2-4 — large enough to collapse two cars' intended 6-tick departure gap to zero and trigger the
Krauss emergency-braking recovery that produces the elevated RMSE. **Not independently re-fixed
here, deliberately**, for the same reason part 5 itself was left unfixed: the direct fix (stop
polling, rely solely on the reply) has been attempted and reverted twice already (part 2: caused a
`Subway` stall; part 5: made duplicate dispatch rates worse, 137 vs. 41), and there's no new
evidence from this investigation suggesting a third attempt would fare differently without first
either auditing every interacting reply handler system-wide or implementing the TimeManager-level
stale-repoll-detection part 5 sketches (extending the existing `dispatchGeneration`/
`highestProcessedTick` watermarks to gate *when* a `Waiting`-fallback re-poll may fire). What **is**
fixed in this section — the harness's silently-stale SUMO ground truth, and the `CarMicroHandler`
velocity-carryover/rest-start bug — are both real and independently worth having, but neither is
sufficient alone to close this specific RMSE gap, and no further code fix is safe to land without
the dedicated scheduler-level investigation part 5 and this correction both point at. Whoever picks
up that scheduling fix next has a harness that can immediately, correctly confirm it:
`tools/sumo_validation/run_validation.py --scenarios a b` from the platform root — watch `car_1`
(scenario A) and any car whose `MICROENTER`/`enter_link` tick collides with another car's within
1-2 ticks (scenario B).

## `deferFinishSpontaneous()` Holds an Entire LTM Batch Open for the Duration of an Unbounded Async Wait (Found 2026-08-04, Partially Fixed — Signal Wait Only; Next Priority)

**Found while designing congestion-propagation/spillback work** (a car waiting at a `Node` for
downstream link capacity, discussed but not yet built), specifically while answering "does the
TimeManager actually guarantee this waiting car gets woken up, and does anything else stall in the
meantime?"

`deferFinishSpontaneous()` (`SimulationBaseActor.scala:720-721`, pre-existing —
`git blame` attributes it to commit `c03d70d`, 2026-06-10, "chore: refactoring hybrid actors", not
introduced by this investigation) suppresses the "did you forget to call `onFinishSpontaneous`"
safety net in `handleSpontaneous` **without sending any `FinishEvent`**. That safety net exists so
`actSpontaneous` never returns without resolving; `deferFinishSpontaneous()` is the sanctioned way
to say "I'll resolve later, from an interaction event, not right now."

The problem: `LocalTimeManagerBase`/`LocalDiscreteEventTimeManager` dispatch one **batch** of
`SpontaneousEvent`s per tick (`sendSpontaneousEvent`, adding every dispatched actor to
`runningEvents`), and **do not advance to the next tick until `runningEvents` is empty** —
`LocalDiscreteEventTimeManager.processTick`'s own doc comment: *"Time only advances when all
events at the current time have been processed."* `runningEvents` is only cleared by an actual
`FinishEvent` (`finishEvent`, `runningEvents.filterInPlace(...)`), regardless of `scheduleTick`.
`deferFinishSpontaneous()` sends no `FinishEvent` — so **every other actor scheduled in the same
batch on the same `LocalTimeManager` pool instance is blocked from its own next-tick dispatch**
until whatever this one actor is waiting on resolves and it finally calls the real
`onFinishSpontaneous(...)`.

**This is not a deadlock** (the wait does resolve — the counterpart always eventually replies in
every usage found so far) **but it is a real stall**, proportional to however long the async
operation takes, applied to every co-scheduled actor on that LTM instance, not just the waiting
one. Under load (busy shard, actor mailbox contention, slow dynamic-actor spin-up) this is
plausibly a contributor to the class of multi-tick stalls already documented in this file (the
past-tick-guard section above; the 10-13s startup latency and 106-119 tick idle-`Link`-reactivation
stall in the enterLink()-poll-race section) — not confirmed as the root cause of any specific one
of those, but the same shape of problem, and worth keeping in mind when chasing them.

**Already fixed**: the signal-wait exchange in `Car`/`Bus`/`Bicycle`/`Motorcycle`'s
`*SignalHandler.requestSignalState`/`handleSignalState` (this fix's own git history:
`deferFinishSpontaneous` was added there, found to hold the batch open, and replaced with the
pattern the project already used correctly for `Person`'s `StartTrip → Car` handoff — see
`LocalTimeManagerBase.scheduleEvent`'s `wasIdle` re-notification comment). The car now genuinely
deregisters (`onFinishSpontaneous(None)`, a real `FinishEvent`, clears `runningEvents`
immediately) when it sends `RequestSignalStateData`, and re-registers via `scheduleEvent(tick)`
(not `onFinishSpontaneous`) once the `Node`'s reply lands — `scheduleEvent` is required here, not
optional, because `finishEvent`'s own `scheduleTick`-add path has **no** `wasIdle`/Global-TM
re-notification logic; calling `onFinishSpontaneous(Some(tick))` a second time from an
already-fully-resolved context would silently add to `scheduledActors` without telling the Global
TM there's new work, risking premature simulation termination if this was the last scheduled item
on this LTM.

**Still open — three more call sites with the same shape, not yet fixed:**

- **`BusStopHandler.requestLoadPassenger`** (single reply from one `BusStop` — same shape as the
  now-fixed signal wait, likely the least severe of the three).
- **`BusStopHandler.requestUnloadPeopleData`** (fan-out to *every* boarded `Person`, resolves only
  once `countUnloadReceived >= expectedUnloadResponses` in `handleUnloadPassenger` — the LTM batch
  stays blocked for as long as the *slowest* of N passengers takes to reply; likely the most
  exposed of the three, since more messages means more chances of one being slow).
- **`SubwayStation.scheduleNextTick`** and **`BusStation.actSpontaneous`/`dispatchFirstBus`**
  (3 call sites, same pattern): both defer while waiting for `onDynamicActorInitialized` ACKs from
  freshly-spawned `Bus`/`Subway` actors — dynamic actor creation/activation via Pekko cluster
  sharding is not guaranteed instant, especially under load, making this a plausible real-world
  stall source, not just a theoretical one.

Unlike the signal-wait bug, none of these three have the *second*, more dangerous defect (the
"silent scheduling, no Global TM re-notify" trap) — their eventual `onFinishSpontaneous(tick)`
call correctly resolves the *same* `runningEvents` entry that was held open by
`deferFinishSpontaneous()` in the first place, since nothing else clears it in between. The only
defect here is the batch-stall itself.

**Next step for whoever picks this up**: apply the same disengage-then-`scheduleEvent` pattern to
all three remaining call sites (7 total, across `BusStopHandler`, `SubwayStation`, `BusStation`).
Recommended order: `BusStopHandler.requestUnloadPeopleData` first (fan-out, most exposed), then the
dynamic-actor-creation sites in `BusStation`/`SubwayStation`, then
`BusStopHandler.requestLoadPassenger` last (single-reply, least exposed). No test currently
exercises `runningEvents`/batch-stall behavior directly — consider whether a
`pekko-actor-testkit-typed` test asserting an LTM's `runningEvents` clears immediately (not just
eventually) around one of these calls is worth adding alongside the fix, to prevent the same class
of regression the signal-wait fix's own tests couldn't catch (see `CarSignalHandlerSpec`'s doc
comment on why it only tests the pure-handler layer, not `SimulationBaseActor.handleSpontaneous`).

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
