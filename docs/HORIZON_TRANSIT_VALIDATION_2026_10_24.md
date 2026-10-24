# Horizon and transit reporting validation — 2026-10-24

The [reporting contract](HORIZON_AND_TRANSIT_REPORTING.md) closes missing final
vehicle reports and unmeasured public-transport passenger distance. Acceptance uses
physical expectations from the generated inputs, followed by full report comparisons
within each road mode. An engine stop or policy agreement alone does not pass a case.
All **14 scenarios** pass independent physical acceptance, and all **eight** full
report comparisons match. The retained
[machine-readable record](validation/horizon-pt-2026-10-24.json) includes artifact
hashes, exact input paths, ports, relay traffic and per-case outcomes.

## Executable and regressions

`sbt test` passes **605 tests across 130 suites**. Focused build/assembly log:
`/tmp/htc-horizon-pt-focused.log`; full-suite log:
`/tmp/htc-horizon-pt-full-tests.log`.
Executable: `target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar`.
SHA-256: `42c96c0cfb6b23881f9287fc0f3c29e5d7487252be7d51480651aafd66f9d049`.
All accepted cases use this artifact.

New regressions cover owner-authorized horizon reporting strictly below GVT,
report-before-retirement ordering, rejection of model scheduling during reporting,
and censoring an outstanding reply without pinning the final cut at its send tick.
A conservative owner test withholds the actual final report receipt after the LP's
retirement ACK and proves stop remains blocked until receipt. Independent cold codecs
preserve horizon grants and the added boarding odometer. Model tests check typed
vehicle matching, boarding-baseline JSON restoration, backwards-odometer rejection,
and rail arrival counting that survives restore without being repeated at cleanup.

## Inputs and independent expectation

The tracked seed-42 generator uses a 2x2 grid with eight 200 m road links. Both road
modes retain identical nodes, stops and service placement; it sets the requested
road mode after generation to avoid consuming RNG draws that would move stops.
Bus/Subway types are explicitly pre-registered on every JVM through
`dynamicActorTypes`. There are no source-loaded buses or trains.

BusStation 15 creates buses 16 and 1001, with departures 20 ticks apart. Person 22
boards the return service from road node 4 to 1, a 400 m ride. Subway garage 17
creates trains 21 and 1002. The first rail link (3→1) is 1000 m; the return link
(1→3) is 1400 m. Person 23 boards at node 1 and rides 1400 m. Reversed RAPTOR route
records describe those in-service return directions, and access radius 1 m ensures
boarding/alighting at the specified stops without an invented walking shortcut.

Trains travel at 22.22 m/s and dwell for 20 ticks. Their first departures are ticks
20 and 40; rail travel requires `ceil(1000/22.22)=46` and `ceil(1400/22.22)=64`
ticks. The first train reaches node 1 at 66, cleans up at 86, re-enters at 87 and
reaches node 3 at 151. The validator derives every subsequent entry, physical
arrival and cleanup from these input values rather than copying output counts.

At horizon 1000, each Person must have exactly one boarding, alighting, PT completion
and daily summary, with distances 400 m and 1400 m. Every departed service must have
one final journey and SUMO record, including buses with their next exit beyond the
cap. Final records are censored: no destination completion, null final node, SUMO
arrival -1, and confirmed physical distance only.

The boundary variant uses one bus/train and horizon **151**. The train's 1400 m
arrival and passenger alighting must execute exactly at the cap; cleanup at 171
must not execute. Its final confirmed distance is 2400 m. Persons' final activity
end is 500, so zero daily summaries are expected in this censored case.

Owned-vehicle variants use one Person and one Car, Bicycle or Motorcycle. The
Person departs at 300 for a different node requiring two links; horizon 310 falls
inside the first link. Expected output is one entry, zero exits, zero completed
trip/daily-summary events, and one censored journey/SUMO pair with zero confirmed
distance. The owner is still waiting for the vehicle's trip reply at retirement.

## Single-JVM results

Both production policies pass every row below. Physical report multisets match
exactly between conservative (C) and optimistic (O), retaining simulation ticks,
physical payloads and duplicate multiplicity. Only scenario ID, wall-clock report
time and summary processing duration are excluded.

| Variant | PT trips | Daily summaries | Final journey/SUMO pairs | Physical rows C / O | Result |
|---|---:|---:|---:|---:|---|
| MESO, two buses + two trains | 2 | 2 | 4 | 339 / 339 | Pass / match |
| MICRO, two buses + two trains | 2 | 2 | 4 | 3477 / 3477 | Pass / match |
| Arrival exactly at horizon 151 | 2 | 0 | 2 | 48 / 48 | Pass / match |
| Car first link censored at 310 | 0 | 0 | 1 | 7 / 7 | Pass / match |
| Bicycle first link censored at 310 | 0 | 0 | 1 | 6 / 6 | Pass / match |
| Motorcycle first link censored at 310 | 0 | 0 | 1 | 6 / 6 | Pass / match |

Transit completion ticks are 100 (MESO Bus), 120 (MICRO Bus) and 151 (Subway),
identically in both policies. Each cyclic train reports 15400 m at horizon 1000.
MICRO buses each report 7800 m of completed-link distance; MESO buses report
10000 m and 9800 m. The first MESO bus can complete its final link at exactly the
cap, while the other remains inside its next link. All cases reject errors,
null-state discards, missed services, duplicate completions and stall diagnostics.

## Delayed two-JVM results

The MICRO transit fixture also passes under each policy with two separate JVMs,
one LTM per JVM and 3 ms delay per TCP-relay read. Both runs have two Bus births,
two Subway births, two measured PT completions (400 m and 1400 m), four censored
journey/SUMO pairs and 3477 physical reports. Each full multiset matches the
single-JVM conservative MICRO reference, with zero missing or extra records.
This checks the new final-horizon grants and odometer payloads over real remote
delivery and acknowledged sink flushing.

The accepted network cases are `network-micro-discrete-event-2jvm` and
`network-micro-time-warp-2jvm`, using inputs under `network-micro/`. Both declare
Bus/Subway shard types before startup on every JVM. A prior distributed fixture
omitted those declarations and buffered child creation at startup; it is excluded
from accepted evidence. The runs take 146.89 s (C) and 88.78 s (O), including cluster
startup and harness teardown. Optimistic execution performs 1741 rollbacks. These
are bounded validation diagnostics, not a throughput or performance claim.

## Reproduction and artifacts

```bash
python3 scripts/scenario/generate_transit_reporting_validation_scenario.py \
  --output-dir /tmp/transit-reporting-micro --mode MICRO --vehicles 2 --horizon 1000
python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --simulation /tmp/transit-reporting-micro/time-warp.json \
  --extra-config /tmp/transit-reporting-micro/reporting.conf \
  --output /tmp/transit-reporting-micro-tw --base-port 38300 \
  --nodes 2 --delay-ms 3 --timeout 240
python3 scripts/scenario/validate_transit_birth_reports.py \
  --simulation /tmp/transit-reporting-micro/time-warp.json \
  --reports /tmp/transit-reporting-micro-tw/reports \
  --log /tmp/transit-reporting-micro-tw/runtime.log \
  --output /tmp/transit-reporting-micro-tw/acceptance.json

python3 scripts/scenario/generate_horizon_vehicle_validation_scenario.py \
  --output-dir /tmp/horizon-bicycle --vehicle bicycle
python3 scripts/scenario/validate_horizon_vehicle_reports.py \
  --simulation /tmp/horizon-bicycle/discrete-event.json \
  --reports /tmp/horizon-bicycle-de/reports --log /tmp/horizon-bicycle-de/runtime.log \
  --output /tmp/horizon-bicycle-de/acceptance.json
```

Launch the owned-vehicle case with the same runner and its chosen policy file.
Use fresh output directories and non-overlapping port ranges. A delayed runner
also binds `base-port + 100`. Compare cases using
`scripts/scenario/compare_policy_reports.py`.

Raw root: `/tmp/htc-horizon-pt-20261024/`. Accepted single-JVM directories are
`accepted-meso-*`, `verified-micro-*` without a `2jvm` suffix,
`accepted-boundary-*`, and `verified-{car,bicycle,motorcycle}-cap-*`.
Each run retains per-node commands/configuration, raw and pre-teardown logs, reports,
`result.json` and `acceptance.json`. Earlier fixture attempts are excluded: their
return RAPTOR routes, stop placement or cross-JVM child-region declarations did not
match the intended physical test. Generation now preserves those requirements.

## Scope

This validates bounded loopback/JSON scenarios and explicit censor semantics.
It does not establish multi-host fault recovery, migration, city-scale throughput,
or full SUMO terminal statistics. This milestone excluded walking distance from
daily totals; the subsequent [walking follow-up](WALKING_DISTANCE.md) closes that
gap. Cyclic Subway finalization is a censored service summary, not a fabricated
destination-completed train journey. Raw `/tmp` artifacts can disappear on reboot;
tracked generators, validators and the final validation summary preserve reproduction.
