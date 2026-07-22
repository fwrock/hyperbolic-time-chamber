# Known Gaps

Honest gap assessment against the README/docs claims, based on a source-level audit. This module
has the platform's best documentation, but "best-documented" isn't the same as "fully accurate" —
update this file as gaps close.

## Headline Gap: an Undocumented, Apparently-Orphaned Second Actor Package

README and `docs/*_AGENT.md` document only `model.hybrid.actor` (Car, Bus, Subway, Person, etc.).
There is a **second, complete, parallel actor package**, `model/mobility/actor/` (Car, Bus,
BusStation, BusStop, Link, Node, Person, Subway, SubwayStation, TrafficSignal, GPS — ~2,184 lines
total), that is not mentioned anywhere in the README or `docs/`. A repo-wide grep found it is
**never referenced** by any scenario JSON, docs, or `typeActor` string outside its own package.
`CLAUDE.md` describes it as an intentional "meso-only" model, but nothing in the actual
scenario-loading path uses it, and `model/mobility/actor/Person.scala` is a 15-line stub —
consistent with legacy/orphaned code, not an active alternate simulation mode.

**Before touching anything in `model/mobility/actor/`**: confirm with the maintainer whether it's
dead code safe to remove, or a genuinely planned alternate mode that just hasn't been wired in or
documented yet. Don't assume either without asking — the answer changes whether the right move is
deletion or documentation.

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
