# Model-created actor birth validation — 2026-10-24

This records the [ordered birth adapter](RUNTIME_ACTOR_BIRTH.md) on both production
policies. Each separate process selects conservative or optimistic execution before
startup. The existing GTM and LTM-owned ledgers remain the time-management architecture.

## Executable and focused regressions

`sbt test assembly` passes **571 tests across 121 suites**. Build log:
`/tmp/htc-birth-handoff-final-build.log`. Tested executable:
`/tmp/htc-birth-validation-20261024/htc-birth-handoff.jar`.
Assembly hash: `93aaa549fb94f8789de992d451ae3a4f5c393d4e`.
SHA-256: `9ac02455a7adb22245e20cbdcdef7a4c10ac60f45ee48a51b957f8b8ea8e66a7`.

New coverage includes:

- A child starts inert; scheduled and passive activation obey the full birth order.
  Inputs before that order fail rather than indefinitely pinning the dormant queue.
- Cancellation before activation, cancellation after effects, duplicate positives,
  replacement-before-anti delivery and coast-forward activation preserve identity
  and suppress canceled or duplicate output.
- Parent/child/grandchild execution uses the real production managers and LP adapter.
  Injecting an earlier input after both descendants execute retracts both births;
  only the surviving parent's report commits.
- A birth overtaking an already-frozen destination owner enrolls/freezes its child
  before the source channel closes. Unknown-target antis may precede initialization.
  Initializer loss fails the run. New protocol and initialization messages round-trip
  through the configured serializer.
- Both policies pass only their selected time-manager handle to children, even
  when a parent has legacy handles for another mode.
- A canceled child's latest accepted-input retirement fence never rewinds: receiving
  an input at tick 20 forbids retirement at GVT 20, and permits it at 21 once settled.
- Populated station routes/queues restore with their correct types. Bus cumulative
  SUMO counters and completion guards follow restored model state, and string
  interning preserves its proactive link-exit guard. Bus MICRO exit requests
  signal/cleanup once instead of scheduling Ready on an unconsumed path; duplicate
  exits do not add distance. Cyclic route peeking follows the actual cursor,
  wraps without consuming it and handles an empty route.
- Subway dwell waits for load and unload completion in either reply order under
  both policies. Correlated link exits work from the actual Stopped state; stale
  replies cannot add distance. Subway speed conversion and Person unload routing
  have direct regressions.

## Inputs and acceptance

Artifacts, derived manifests, launchers and logs are under
`/tmp/htc-birth-validation-20261024/`. Base generation:

```bash
python3 scripts/scenario/generate_hybrid_scenario.py \
  --output-dir /tmp/htc-birth-validation-20261024/scenario \
  --rows 2 --cols 2 --num-persons 2 --num-cars 0 \
  --num-bus-lines 1 --num-subway-lines 1 --micro-pct 0 --seed 42
```

The preserved derived scenario has four road nodes, eight 200 m road links and two
1000 m rail links. No Bus or Subway is source-loaded. BusStation 15 creates bus 16;
Subway garage 17 creates train 21. Person 22 takes the Bus from node 4 to 1, and
Person 23 takes the Subway from node 1 to 3. Departures are requested at tick 10,
vehicle intervals are 20 ticks, final activities end at 500, and the horizon is
1000. Boarding uses the in-service return legs of the generated routes. The train
uses 22.22 m/s, requiring ceil(1000/22.22) = 46 ticks per rail traversal plus dwell.

Expected outcomes per run are two PT completions, two daily summaries, one Bus
boarding/alighting and one Subway boarding/alighting, plus the exact station-created
vehicle IDs and the train's cyclic timetable (alternating two 1000 m rail links).
Both transit services are cyclic and run to the horizon: any unfinished final traversal is explicitly censored and excluded
from completed distance. A horizon-censored service is not counted as a
destination-completed vehicle trip.

Variants use all-MESO or all-MICRO road links, a late cohort at tick 1200 with horizon
2200/vehicle interval 1200, or two buses and two trains at successive departure slots.
The late optimistic source starts empty through tick 1000, while stations already
create their first vehicles; the later Person cohort enrolls through the progressive
source protocol. Conservative's larger bootstrap preloads these persons, so that
case is a policy reference rather than new conservative late-enrollment evidence.

All cases use static routing, two LTMs in one JVM, four active processors and a
2 GiB maximum heap. `scripts/scenario/validate_transit_birth_reports.py` derives
counts, modes, routes and distances from each input manifest, checks actual
boarding/alighting, physical traversal and acknowledged completion, and rejects
exceptions, replay mismatches, null-state discards and stalls. Policy comparison
uses the full committed report multiset, excluding only run ID, wall-clock report
timestamp and SUMO summary CPU duration. No physical field is removed for parity.

## Results

All eight transit cases pass the expected person outcomes and physical service
checks. C / O means separate conservative / optimistic processes. Counts below
are actual / expected **in each process**; entries/exits include explicit final
horizon-censored entries.

| Variant | Bus / Subway births, each run | PT trips / expected | Daily summaries / expected | Bus entries / exits | Rail entries / exits | Physical rows C / O | Rollbacks C / O | Acceptance / parity |
|---|---|---|---|---|---|---|---|---|
| MESO | 1 / 1 | 2 / 2 | 2 / 2 | 50 / 49 | 15 / 14 | 168 / 168 | 0 / 5 | Pass / Match |
| MICRO roads | 1 / 1 | 2 / 2 | 2 / 2 | 40 / 39 | 15 / 14 | 1061 / 1061 | 0 / 125 | Pass / Match |
| Late Persons, MESO | 1 / 1 | 2 / 2 | 2 / 2 | 51 / 50 | 15 / 14 | 171 / 171 | 0 / 3 | Pass / Match |
| Two buses + two trains, MESO | 2 / 2 | 2 / 2 | 2 / 2 | 100 / 98 | 30 / 28 | 312 / 312 | 0 / 259 | Pass / Match |

Every transit case has zero exception/error lines, replay mismatches, null-state
discards and stalls. Each has one boarding and alighting per mode. PT completion
ticks for Bus / Subway are 100 / 133 in MESO, 120 / 133 with MICRO roads, and
1280 / 1313 for the late cohort, identically under both policies.

The finite cyclic services end with acknowledged `horizon-truncated` status; this
is explicitly distinguished from destination completion. Each vehicle has one
unfinished final link. In the multiple-birth case, bus 1001 last enters at tick
995 and its MESO exit is due at 1013, beyond the horizon 1000. It has no terminal
wake at 1000, so it emits neither a final journey report nor SUMO tripinfo. Both
policies expose this same reporting limitation. Acceptance requires the known
pending exit to be strictly beyond the cap; a missing in-horizon exit still fails.
The other bus emits its horizon report at 1000 with 9800 m of completed distance.
The validator records missing horizon journey reports separately instead of
inventing a completion. Its original stricter wake assumption is preserved in
`acceptance-horizon-wake-assumption.json` for the multi cases.

The late optimistic log accepts window 1 through 1000, open, with zero Persons;
GVT reaches 1001 before window 2 accepts two Persons through 2001 and closes the
source. Both vehicles were born at tick 0 with scheduled departure 1200. This
checks coexistence of model-caused birth with later deterministic source enrollment.

Four existing car regressions also pass on the same executable:

| Road mode | Trips / expected, each policy | Daily summaries / expected | Entries / exits | Physical rows C / O | Acceptance / parity |
|---|---|---|---|---|---|
| MESO | 4 / 4 | 2 / 2 | 8 / 8 | 60 / 60 | Pass / Match |
| MICRO | 4 / 4 | 2 / 2 | 8 / 8 | 152 / 152 | Pass / Match |

The car checks retain per-person 400 m trips, 800 m daily distance, activity order
and acknowledged engine completion; MESO additionally checks free-flow timing.
All six policy-pair report multisets match, including cumulative MICRO SUMO rows.
Rollback counts are execution diagnostics, not a performance improvement claim.

Machine-readable evidence under the artifact directory:

- `complete-handoff.json`, `matrix-handoff.json`, `handoff-*-comparison.json`:
  transit acceptance, actual traversals, source windows and physical comparisons.
- `birth-handoff-{meso,micro,late,multi}-{discrete-event,time-warp}/`:
  configuration, reports, complete log, launcher result and `acceptance.json`.
- `run_matrix_handoff.py`, `run.py`, `matrix-handoff-final.log`: reproducible
  launcher/validator recipe. Existing outputs are revalidated without rerunning or
  appending reports; use fresh labels for new simulations.
- `car-handoff-regressions.json`, `car-handoff-*-comparison.json`, `car-handoff.log`:
  car results. Raw reports remain under `/tmp/htc-person-replay-20261022/` with
  `accounted-birth-handoff-*` labels. `run_car_handoff.py` launches these cases.

## Rejected intermediate runs

The preserved `birth-initial-*`, `birth-model-fixed-*` and `birth-verified-meso-*`
runs are not accepted evidence. They exposed a null Person unload creation type,
incorrect subway travel-time units, a conservative link-exit state guard and a
missing subway unload barrier. The last defect allowed both processes to announce
completion even though the train still carried its supposedly alighted passenger
and had not completed its rail route. Strict output validation rejected both runs;
the model barrier was fixed rather than relaxing acceptance.

The first validation of the pre-handoff executable also rejected an incorrect test
assumption: Subway, like Bus, explicitly repeats its route. Its entry/exit trace
had completed the circuit repeatedly rather than stopped after one circuit. The
validator now derives every expected rail entry/exit tick from the source queue,
headway, route lengths, speed, dwell and finite horizon, and checks the full cyclic
timetable. This required no engine change. The original failed acceptance files
remain preserved as `acceptance-one-circuit-assumption.json`.

The pre-handoff `birth-final-micro-*` runs also fail acceptance under both policies:
the bus re-entered road link 5 repeatedly, never reached its waiting passenger and
completed only one of the two expected person trips. The helper scheduled Ready
without passing through `Bus.leavingLink`, so `currentPath` never cleared. The
handoff fix uses the established signal/capacity and cleanup chain; it also fixes
Bus's next-link peek to consult its cyclic route cursor. These rejected outputs
remain available separately from the accepted artifact below.

## Limits

The child ID must belong to one deterministic unique birth slot. ID recycling,
shared speculative routing feedback, coordinated recovery/migration, multi-JVM
validation and durable external-effect retry remain open. These smoke runs provide
no million-actor or throughput claim. PT person reports still omit traveled distance,
and Subway has no journey-completion report. Horizon-censored MESO buses also lack
final tripinfo when their next wake is beyond the cap. Acceptance instead verifies
actual physical service and boarding/alighting; uniform horizon finalization remains
a reporting gap. Development-cluster JVM teardown after ReportManager
stop is separate from successful engine completion.
