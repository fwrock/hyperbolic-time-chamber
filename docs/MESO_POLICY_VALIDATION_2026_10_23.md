# MESO Car policy validation — 2026-10-23

Each simulator process selects one policy before startup. This check uses the
same seed-42 person-owned Car inputs under conservative and optimistic execution;
it does not mix policies within a simulation.

## Reproduced defects

Two baseline runs on `/tmp/htc-retirement-checked-20261023.jar` reproduced the
distance defect after the retirement correction was already applied:

| Baseline run | Car entries / leaves | Person trips / summaries | Defect |
|---|---|---|---|
| `meso-baseline-cons-single` | 4 / 0 | 4 / 2 | Every trip reported 0 m for a 200 m route. |
| `meso-baseline-opt-multi` | 8 / 4 | 4 / 2 | The final link's 200 m was missing from each 400 m trip. |

Both runs reached engine shutdown. The strict verifier rejects them for unmatched
MESO entries and exits; trip totals and a stop marker alone are insufficient.
Four actor regressions also failed before the fix: final journeys completed before
their leave reply, and intermediate departures scheduled another wake while that
reply was still outstanding. Evidence:
`/tmp/htc-meso-regression-before-20261023.log`.

An intermediate traversal correction restored person distances and physical timing,
but the second trip's SUMO `routeLength` still used the lifetime odometer: 400 m
instead of 200 m in the one-link case. The expanded verifier rejects this too.
The focused reporter regression failed with
`List(200.0, 400.0) was not equal to List(200.0, 200.0)`; see
`/tmp/htc-meso-report-distance-before-20261023.log`.

## Model and protocol correction

`Movable.leavingLink` now distinguishes completion data from cleanup using the
checkpointed `movableLinkExitReported` marker:

- MESO registers `LeaveLinkData` as a reply obligation and sends no next wake.
  `CarLinkHandler.handleLeaveLink` consumes the reply, adds distance, clears the
  completed link's metadata, and finishes the journey or schedules the next link.
  It cannot clear a newer link's timing because that newer traversal has not begun.
- MICRO already received distance in the Link's proactive `MicroLeaveLinkData`.
  Its later `LeaveLinkData` remains cleanup without another wait. The marker resets
  on each link entry, including a transition back to MESO.

Car signal handling initiates departure and leaves lifecycle completion to the
traversal completion path. It no longer requests immediate destruction while a
MESO leave reply is outstanding. No new model message, polling loop, or timeout is
introduced. Reply obligations and schedules retain their existing rollback state.

Car SUMO `routeLength` and the journey-distance metric subtract the existing
checkpointed trip-start odometer. The vehicle odometer and `total_distance` remain
cumulative; `person_trip_completed.distance_traveled` and SUMO `routeLength`
describe a single trip.

## Inputs and independent acceptance checks

The preserved generator invocation and deterministic input adaptations are in
[the original validation record](TIME_WARP_VALIDATION_2026_10_22.md#reproducible-bounded-model-scenario).
The MESO variants are in `/tmp/htc-person-replay-20261022/meso/`: all eight road
links have mode MESO. The city graph and person plans are the same as in the MICRO
checks. There are two persons (14 and 16), two owned Cars (13 and 15), no transit,
activity boundaries at ticks 10/200/800, no active junction controls, static routing,
and a horizon of 1000.

One-link plans are node 1 → 2 → 1, with 200 m per trip and 400 m per daily summary.
Two-link plans are node 1 → 4 → 1, with 400 m per trip and 800 m per daily summary.
Each case expects four trips and two daily summaries; one-link routes require four
MESO entry/leave pairs and two-link routes require eight.

[`validate_meso_reports.py`](../scripts/scenario/validate_meso_reports.py) loads the
actual person plans and link topology. It checks destination/activity sequence,
route connectivity, shortest route distance, trip counters, person and SUMO trip
distances, cumulative journey odometers, matching per-vehicle link entries/exits,
and final completion at the final leave reply. It also checks each traversal time
against the configured MESO density-speed law and rejects overlapping traversals.

With `--free-flow`, it additionally checks exact departure and handoff ticks. Here,
each 200 m traversal takes 19 ticks after rounding the density-adjusted travel time
upwards. Vehicle activation and an intermediate handoff each cost one tick.
Therefore trips starting at ticks 10/200 must finish at 30/220 on one-link routes
and at 50/240 on two-link routes. These expectations are derived from the input
geometry and model rules, not copied from another run's output.

Runtime acceptance rejects errors, replay mismatches, null-state events, stalls,
and missing engine completion. Optimistic cases additionally require increasing
round IDs, monotone GVT, and acknowledged accounted completion before reporter
shutdown. The bounded launcher subsequently stops the remaining cluster JVM;
engine completion and natural JVM exit are distinct observations.

## Verified results

`sbt test assembly` passed **483 tests across 100 suites**. Build log:
`/tmp/htc-meso-final-20261023-build.log`. The final executable is
`/tmp/htc-meso-validated-20261023.jar`, assembly hash
`5ee0747e538f78c8d8f330ba41e20d74b25ecb65`, SHA-256
`35f2582491ba5ade86e5d2133d5061f3bcdcbdfb560760b7866ea9ae08a6e58c`.

| Check | Conservative, one link | Conservative, two links | Optimistic, one link | Optimistic, two links |
|---|---:|---:|---:|---:|
| Person trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 4 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 |
| MESO entries / exits | 4 / 4 | 8 / 8 | 4 / 4 | 8 / 8 |
| Person trip distance / expected (m) | 200 / 200 | 400 / 400 | 200 / 200 | 400 / 400 |
| SUMO route length / expected (m) | 200 / 200 | 400 / 400 | 200 / 200 | 400 / 400 |
| Completion ticks, both persons | 30 / 220 | 50 / 240 | 30 / 220 | 50 / 240 |
| Exception lines / replay mismatches | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Null-state discards | 0 | 0 | 0 | 0 |
| Rollbacks / stalls | 0 / 0 | 0 / 0 | 29 / 0 | 62 / 0 |
| Engine stop before launcher teardown | Yes | Yes | Yes | Yes |
| Distance, traversal, timing, runtime acceptance | Pass | Pass | Pass | Pass |

Every trip, including the return trip, passes the person and SUMO distance checks.
Cumulative journey odometers and daily person totals also match. Two MICRO
two-link regression runs on this exact artifact each passed four trips, two
summaries, eight matched entries/exits, and corrected 400 m SUMO lengths for every
trip. They have zero errors, null-state discards, replay mismatches, or stalls;
the optimistic MICRO run performed 162 rollbacks.

The comparison proves matching MESO trip distances, completion ticks, and
per-vehicle entry ticks/links in these runs. It does **not** prove full payload
parity: independent same-tick entries can receive different density positions
under conservative arrival order. For example, car 13 entering link 6 at tick 201
in the one-link case sees occupancy 1 conservatively and 2 optimistically,
producing 39.933333 versus 39.866667 km/h. Both round up to 19 traversal ticks on
these links, so matching completion ticks do not expose this remaining report
difference. The two-link case has four such per-vehicle density differences.
This remains part of the conservative channel/order work, and is preserved in
`/tmp/htc-meso-policy-comparison-20261023.json`.

## Artifacts and reproduction

All run directories are under `/tmp/htc-person-replay-20261022/`:

| Run directory | Configuration relative to that root | Port offset |
|---|---|---:|
| `meso-validated-cons-single` | `meso/discrete-event-single-meso.json` | 141 |
| `meso-validated-opt-single` | `meso/time-warp-single-meso.json` | 142 |
| `meso-validated-cons-multi` | `meso/discrete-event-multi-meso.json` | 143 |
| `meso-validated-opt-multi` | `meso/time-warp-multi-meso.json` | 144 |
| `accounted-meso-validated-micro-cons-multi` | `discrete-event-multi.json` | 145 |
| `accounted-meso-validated-micro-opt-multi` | `time-warp-multi.json` | 146 |

Each directory preserves `run.conf`, `run.log`, JSONL reports, and `result.json`.
MESO cases additionally have `strict-meso.json`; MICRO cases have `analysis.json`
and outcome verification. `/tmp/htc-meso-validation-20261023-manifest.json` records
input hashes, executable identity, commands, all six results, and a separate SUMO
per-trip check for both MESO and MICRO. The baseline and intermediate run
directories are preserved alongside these final cases and are not release passes.

An example reproduction from the repository root is:

```bash
python3 /tmp/htc-person-replay-20261022/run.py meso-recheck-cons-single /tmp/htc-meso-validated-20261023.jar /tmp/htc-person-replay-20261022/meso/discrete-event-single-meso.json 151
python3 scripts/scenario/validate_meso_reports.py \
  --simulation /tmp/htc-person-replay-20261022/meso/discrete-event-single-meso.json \
  --reports /tmp/htc-person-replay-20261022/meso-recheck-cons-single/reports \
  --log /tmp/htc-person-replay-20261022/meso-recheck-cons-single/run.log \
  --output /tmp/htc-person-replay-20261022/meso-recheck-cons-single/strict-meso.json \
  --free-flow
```

Select the other configurations from the table for each policy/route case. Use
fresh labels and free ports because reports append to existing files. MICRO runs
use the preserved `analyze.py`/`verify.py` checker instead of the MESO verifier.

## Scope

This establishes a bounded Car MESO check, including repeated person-owned trips.
It does not establish congestion/signal behavior, Bicycle/Motorcycle or transit
correctness, all per-event report equality, distributed scaling, migration, or
failure recovery. Conservative same-tick channel ordering remains open. Live
transit birth and shared speculative routing feedback remain unsupported by the
accounted optimistic runtime.
