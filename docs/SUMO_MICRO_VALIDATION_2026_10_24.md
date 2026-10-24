# MICRO accuracy against real SUMO — 2026-10-24

This campaign audits the earlier sibling `../tools/sumo_validation` harness and
replaces its measurements with a tracked, reproducible comparison against real
SUMO 1.27.1. The tracked entry point is
`scripts/scenario/validate_sumo_micro.py`; raw inputs and outputs are preserved
under `/tmp/htc-sumo-audit-20261024/`.

## Measurement corrections

The previous metrics joined along-link positions on vehicle and integer time
without considering link identity, then estimated journey duration from the first
and last reported position. A car on the next link could appear almost an entire
link away; unreported startup or node waiting disappeared from its duration.
Its CLI also continued after HTC timeout or SUMO failure and reused output
locations. Its parser does not map the exporter's numeric HTC vehicle IDs back to
SUMO vehicle names, and its exporter writes string phase identities into fields
that now require numeric IDs. Earlier numbers cannot establish current accuracy.

The replacement uses the existing seed-42 grid generator with controlled routes,
a numeric identity manifest shared with SUMO, explicit `precomputedRoute`, current
numeric signal phases and correctly wired incoming/outgoing signal connections.
It refuses an existing output directory and fails on simulator failure, missing
vehicles/completions, mismatched departures, inadequate overlap and runtime errors.
SUMO driver imperfection and speed variation are disabled explicitly; both engines
start the front bumper at position zero with speed zero.

SUMO lane lengths are checked after netconvert. SUMO uses explicit point junctions
without internal links, matching HTC's current road-link/node representation.
This isolates boundary timing without introducing an extra SUMO junction lane
that HTC does not model. It does not validate finite junction geometry, conflict
areas, turn-dependent travel time or lane changing.

Trajectory comparison uses equal simulation timestamps with no fitted time shift.
Only SUMO's finer trace is sampled onto the whole-second HTC output grid.
Along-link position RMSE is calculated only on matching links; continuous route
distance RMSE also includes vehicles that have reached different links. HTC
completed trip reports and SUMO tripinfo supply actual departure/arrival durations.
Coverage counts, link entry/exit timestamps, node delay and exit speeds remain in
the results so an aggregate RMSE cannot hide missing or delayed movement.

SUMO's [FCD specification](https://sumo.dlr.de/docs/Simulation/Output/FCDOutput.html)
defines lane-local position, odometer and speed. The
[SUMO options](https://sumo.dlr.de/docs/sumo.html) describe internal links and
integration. Exported inputs and per-run commands retain the selected assumptions.

## Model corrections

The initial current-build baseline confirmed that every MICRO link boundary
reset physical lane speed to zero. A vehicle-side journey reporter suggested speed
carryover, but `EnterLinkData` carried no velocity and Link seeded zero regardless.
The live strategy also forced an exit velocity of zero, reused the exiting vehicle
through the remaining sub-ticks, and treated an uncontrolled exit as a leader that
caused braking. `Movable.leavingLink` inserted another whole tick at a point node.

Entry velocity now crosses the ordinary positive/anti transport, is capped by the
receiving link speed limit, and is acknowledged from the actual lane state. The
vehicle and link use the same value. Car/Bicycle/Motorcycle preserve their
checkpointed arrival speed; Bus clears that speed at an actual passenger stop.
MICRO departures retain crossing speed and leave the lane at the first crossing
sub-tick. Uncontrolled/green exits do not imply braking or a stop. A confirmed
MICRO arrival can continue through a point node at the same global tick, retaining
explicit signal and downstream-capacity obligations.

A dedicated phase-boundary case then exposed a second, larger error: SUMO crosses
the 200 m approach at 29.1 s while green; HTC represents that same MICRO arrival
at tick 30. Node queried its newly red phase and imposed a second 30-second wait,
despite Link already authorizing the physical crossing during the green interval.
The intermediate build measured a 31.7 s trip-time error and a 30-tick node wait.

`RequestLinkAccessData.signalAlreadyCleared` now carries that completed MICRO
crossing into Node's ordinary capacity protocol. MESO requests retain Node's signal
check. Node still decrements available capacity or replies Full and buffers the
original request identity. Buffered clearance is checkpointed in
`PendingLinkAccessRequest`; a later red phase cannot undo the already completed
crossing when capacity becomes available. No polling, timeout or new round-trip
was added. A vehicle stopped at the red stop line remains in its lane until a
green interval actually permits crossing. An actual signal/capacity wait clears
carried cruising speed, so a stopped vehicle does not restart at its old speed.

Within each sub-tick, follower calculations use the leader's state before either
vehicle advances and subtract the leader's length. A virtual red signal represents
the stop line, with no additional vehicle standstill gap. Newly entered vehicles
begin integrating after their entry tick. A phase effective at tick t governs the
interval after t; the MICRO batch ending at t uses the preceding phase. Both phase
values and their effective tick are checkpointed, removing a dependence on which
same-tick message reaches Link first.

MICRO link travel-time reports now use arrival minus entry, without adding a
second endpoint tick. Signal transitions exactly at the inclusive horizon execute.
All changes retain the existing owner ledgers, reply identity, checkpoint/replay,
report cancellation, strict-below-GVT commits and actual report-sink receipts.

## Reproduction and acceptance

```bash
sbt test assembly
python3 -m unittest discover -s scripts/scenario -p test_validate_sumo_micro.py -v
python3 scripts/scenario/validate_sumo_micro.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/sumo-micro-signal-discrete --case signal --policy discrete-event --base-port 31800
python3 scripts/scenario/validate_sumo_micro.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --output /tmp/sumo-micro-signal-optimistic --case signal --policy time-warp --base-port 32100
python3 scripts/scenario/compare_policy_reports.py \
  --conservative /tmp/sumo-micro-signal-discrete/htc/reports \
  --optimistic /tmp/sumo-micro-signal-optimistic/htc/reports \
  --output /tmp/sumo-micro-signal-policy-comparison.json
```

Cases are `free` (one car), `following` (four staggered cars, the earlier A
baseline), `crossing` (uncontrolled two-link route), `signal` (six cars spanning
green/red and forming a queue), and `signal-boundary` (departure at 12 s, reaching
the approach exit just before red). The signal case exercises constrained
following; the staggered cohort alone does not establish dense-platoon accuracy.
Both policies use the same physical inputs, seed 42, 0.1 s physics steps and
whole-second HTC reporting. Repeated case directories must be fresh.

Besides measurement validity, each selected case requires zero artificial node
delay, positive crossing speed with consistent downstream initialization, correct
elapsed link times, inclusive signal transitions at ticks 0 through 180, and no
crossing during a red physics interval. The trip-time tolerance is one HTC global
tick per traversed link plus one SUMO 0.1 s step. This is a bounded regression
tolerance for these controlled cases, not a claim of calibration for arbitrary
networks. `--measure-only` preserves measurements of an old model that violates
these invariants, while simulator or measurement failures remain fatal. The
record retains failed model checks instead of labeling them a passing comparison.

The remaining boundary error comes from rounding each sub-tick crossing up to a
whole runtime tick and starting the next link at that tick. Fractional-time
cross-link transport would be a separate runtime/model design change; this work
does not offset traces to hide that quantization or invent junction geometry.

## Accepted results

The accepted executable passes **626 Scala tests in 132 suites**, plus **seven
independent Python measurement regressions**. Its SHA-256 is
`6433e68ee8183fc1616f367880ce56bec77ad866f4185b2c2452e6f510f1e0bd`.
Build log: `/tmp/htc-sumo-accepted-build.log`.

All ten real-SUMO comparisons pass their measurement and bounded model checks:
all five cases under conservative and optimistic execution. Values below are the
same for both HTC policies.

| Case | Mean route-distance RMSE before → after | Largest trip-time error before → after |
|---|---:|---:|
| Free flow | 0.257 m → below 0.000001 m | 0.7 s → 0.7 s |
| Four staggered vehicles | No current-build baseline → below 0.000001 m | No baseline → 0.7 s |
| Uncontrolled crossing | 25.418 m → 7.194 m | 5.7 s → 1.7 s |
| Signal with queue | 25.124 m → 5.814 m | 8.7 s → 1.8 s |
| Green-to-red boundary | See measurement note below | 31.7 s → 1.7 s |

The first three available baselines use the previous pushed executable,
`85d9343` (SHA-256 `8e23aece11ae8dea46f3d2dc67a954e0c1b621857eaf43d83861fd31f82d94b3`).
The green/red baseline is an intermediate executable with the momentum/ordinary
node corrections already applied but without crossing clearance. It isolates the
additional 30-tick Node wait; its SHA-256 is
`59ab0ab7a6dba1da7a4c4dbb4020d1793af1a9d1a3f38fd94b517ec250b82eda`.
Initial parked-car fixture attempts produced no journeys and were rejected;
they are excluded from the evidence.

Measurement note: the intermediate boundary case had a seemingly small 3.119 m
RMSE over only 19 overlapping samples while hiding a 30-second node wait. After
fixing that wait, all 25 SUMO whole-second samples overlap; RMSE is 7.194 m and the
trip-time error is 1.7 s. Actual terminal times, coverage and explicit node-delay
checks prevent the misleading partial trace from being accepted. No trace was
shifted to improve the results. Signal-queue speed RMSE is 0.485 m/s; simplified
following and stop-line behavior are close in these cases, not identical to SUMO.

All five complete committed report comparisons between policies match, including
signal phases, MICRO updates, trip metrics and row multiplicity: 56 rows for free
flow, 167 for the staggered cohort, 314 for crossing, 354 for the signal queue and
66 for the phase boundary. Comparison excludes only run identity, wall-clock
report timestamps and SUMO summary CPU duration.

Two additional mixed MICRO Bus/Subway/walking regressions pass independent
input-derived distance, timing, boarding, activity and daily-total acceptance.
Both policies produce the same 2,077 physical reports, seven walking completions
(2,600 m), one Bus ride (400 m), one Subway ride (700 m), and daily distances
800/800/1,700/400 m. The Bus enters 85 MICRO links and exits 84 before the horizon;
Time Warp performs 226 rollbacks without changing final committed output.
This validates integration of the shared boundary changes, not Bus/Subway accuracy
against SUMO. The sixth complete policy comparison also matches.

The [machine-readable record](validation/sumo-micro-2026-10-24.json) preserves
baselines, per-vehicle measurements, model checks, exact executable identity,
input hashes, runner metadata and all six report comparisons. Raw data and launch
commands are under `/tmp/htc-sumo-audit-20261024/`. The harness terminates JVMs
after acknowledged engine stop; process return codes do not establish clean JVM
shutdown. This campaign is single-JVM and single-lane; independent cold codecs
cover the added wire fields, while unit tests cover checkpointed signal history
and pending capacity clearance. Multi-JVM calibration, high-demand insertion,
finite junction conflict geometry, lane changes and fractional cross-link timing
remain outside this acceptance. Migration remains disabled.
