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
