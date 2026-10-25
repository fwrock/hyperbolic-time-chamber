# Measured walking distance

Walking distance uses the lengths of the actual route's road links, including
repeated links and supplied detours. Routing cost and endpoint separation are not
distance. The existing walking speed remains 1.4 m/s; duration is rounded upward
to whole ticks. Missing, negative, nonfinite or unrepresentable route measurements
fail the leg rather than silently reporting a shorter successful walk.

`WalkingLegProgress(distance, arrivalTick)` lives inside checkpointed
`TripExecutionState.Traveling`. Departure measures the path once; arrival uses
that stored value without reading the map again. An early wake retains the
planned arrival. A later walking leg cannot be completed by a duplicate wake at
the earlier leg's tick. Zero-distance walks still arrive at their departure tick.

On arrival, the walking completion report includes distance and Person emits the
same trip/leg metrics used for other modes. The completed distance joins the
trip's accumulated prefix. Starting another walking, transit or private-vehicle
leg preserves that prefix. Arrival at the next activity flushes it into daily
distance and increments completed trips once for the entire contiguous leg run.
An abort preserves completed-leg distance without claiming a completed trip.
An unfinished walk contributes no distance or completed-leg report at the horizon;
an arrival exactly at the inclusive horizon executes normally. There is no
estimate of a partial walk's position or distance.

The change adds no model message or reply obligation. Every walking result still
passes through Person's normal dispatch and `onFinishSpontaneous`. Static route
observations retain the existing replay journal; rollback restores both the
measured pending walk and completed prefix through PersonState snapshots. Output
uses `report()` and the existing cancellation, strict-below-GVT commit, sink
receipt and final storage-flush rules. Migration remains disabled.

## Reproduction

Generate a bounded seed-42 scenario using the existing grid generator through
`generate_walking_validation_scenario.py`:

```bash
sbt test assembly
python3 scripts/scenario/generate_walking_validation_scenario.py \
  --output-dir /tmp/htc-walking/meso --mode MESO
python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --simulation /tmp/htc-walking/meso/discrete-event.json \
  --output /tmp/htc-walking/meso-conservative --base-port 24600 --nodes 1 --timeout 180
python3 scripts/scenario/validate_walking_reports.py \
  --simulation /tmp/htc-walking/meso/discrete-event.json \
  --reports /tmp/htc-walking/meso-conservative/reports \
  --log /tmp/htc-walking/meso-conservative/runtime.log \
  --output /tmp/htc-walking/meso-conservative/acceptance.json
```

Repeat with `time-warp.json` and a fresh output directory/port, then compare full
report multisets using `compare_policy_reports.py`. `--mode MICRO` changes only
road execution mode, keeping geometry and plans fixed. These scenarios exercise
walking and a MICRO Bus, with no private cars; they do not establish car MICRO
coverage. `--case boundary --horizon 152` ends one tick before a 200 m walk arrives;
`--horizon 153` includes its arrival. Delayed two-JVM validation uses `--nodes 2
--delay-ms 3`; space simultaneous base ports at least 300 apart.

The mixed case independently expects 800 m for a supplied detour, 800 m for a
computed route, 1,700 m for walk/Bus/walk/Subway/walk, and 400 m for consecutive
walks. All four are single trips. Seven completed walks total 2,600 m; Bus and
Subway add 400 m and 700 m respectively. The validator derives route distances
from input records, checks walking arrival times, every leg metric, boarding and
alighting counts, final activities, daily totals and acknowledged engine stop.

## Accepted validation — 2026-10-24

`sbt test assembly` passes **614 tests across 131 suites**. Focused tests cover
repeated route links, routing cost versus distance, invalid measurements,
checkpoint JSON restoration, successful and failed PT timeout replans, failed
subsequent walks, early wakes, consecutive legs and real Person rollback/replay
before and between nonzero walking arrivals.

All ten simulator cases pass independent physical acceptance. Every pair below
has identical full committed report multisets, retaining physical payloads and
multiplicity while removing only wall-clock/scenario identity and the existing
SUMO summary processing duration.

| Case | JVMs | Reports per policy | Walking result |
|---|---:|---:|---|
| MESO mixed | 1 | 360 | Seven walks, 2,600 m; daily totals 800/800/1,700/400 m |
| MICRO mixed | 1 | 1,984 | Same distances; Bus enters 73 MICRO links and exits 72 before the cap |
| One tick before arrival | 1 | 31 | No walking completion or credited distance |
| Arrival exactly at cap | 1 | 35 | One completed walk, 200 m at tick 153 |
| MESO mixed, 3 ms TCP delay per read | 2 | 360 | Same journeys, daily totals and arrival ticks as single-JVM MESO |

The five policy comparisons plus each delayed policy against its single-JVM
reference give seven equal comparisons. Optimistic MICRO performs 199 rollbacks;
its seven final walking completions and all other committed reports still match.
The mixed Person arrives at tick 567 in MESO and 691 in MICRO, reflecting the
different Bus execution model. Other arrivals are 582, 582 and 296 in both modes.

The executable SHA-256 is
`8e23aece11ae8dea46f3d2dc67a954e0c1b621857eaf43d83861fd31f82d94b3`.
The [machine-readable record](validation/walking-distance-2026-10-24.json) retains
input hashes, generation commands, per-run launch metadata, physical acceptance
and all comparisons. Raw inputs, node launch commands and logs live under
`/tmp/htc-walking-20261024/`; the build log is `/tmp/htc-walking-full-build.log`.
The harness terminates JVMs after acknowledged engine stop, so process return
codes are not evidence of clean application shutdown. These bounded loopback
cases establish no city-scale memory or throughput result. Replicated maps,
checkpoint cost, external feedback, recovery and migration remain separate work.
