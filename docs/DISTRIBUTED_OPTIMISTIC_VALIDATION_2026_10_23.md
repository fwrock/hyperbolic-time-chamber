# Distributed optimistic validation — 2026-10-23

This validates [LTM-owned optimistic accounting](DISTRIBUTED_OPTIMISTIC_RUNTIME.md).
Each process selects one policy before startup. Production optimistic execution
uses the existing GTM singleton for round aggregation and LTMs for model traffic;
it does not create the old coordinator child.

## Artifact and protocol checks

The full suite passed **496 tests across 106 suites** on the validated source.
Log: `/tmp/htc-ltm-node-fixed-build.log`. The executable is
`/tmp/htc-ltm-node-fixed-20261023.jar`, assembly hash
`37811b16290f6e0c4ce671ac7187719a2f05351c`, SHA-256
`3930226c6a115495506f42d53a32943be0686f505b69bc1458ad6325ddad2b00`.
The focused protocol/actor tests and assembly command are recorded in
`/tmp/htc-ltm-node-fixed-build.log`. Earlier focused protocol runs are in
`/tmp/htc-distributed-anti-build.log`.

`DistributedRuntimeSpec` uses the actual GTM and two actual LTM adapters, with
controlled LP, channel and sink probes. It holds a positive or an anti-message
after source freezing and proves that fresh collection cannot begin until the
destination accepts it. Its next cut retains the queued tick-10 minimum. The anti
case uses the real cancellation representation: original identity and timestamp,
without requiring a positive event-order key.

The same tests cover pre-start topology sends, abbreviated control identities,
independent report receipt and final flush delays, retirement acknowledgements,
and explicit failure on LP loss, LTM loss or sink flush error. These failures never
become successful completion. The real LP regression retains a pre-start tick-110
input beyond a tick-100 horizon without executing or reporting it, and accepts
only a final-cut retirement for that truncated work.

`DistributedProtocolSerializationSpec` round-trips every new distributed command
and the startup horizon/ownership messages through the configured Kryo binding.
Actor references, logical identity, cancellation metadata and sink sets survive.
This is one-JVM serializer evidence, not a multi-node transport campaign.

`LinkMetricsRollbackSpec` serializes an earlier `LinkState`, mutates its counters,
restores that state and replays entry/exit events. Cumulative and per-tick output
matches the original prefix. The reporter now accesses checkpointed metrics through
its state callback rather than keeping mutable counters in the helper.

`NodeCounterSnapshotSpec` reproduces the larger-cohort cast failure through the
actual JSON checkpoint path with populated capacity and signal-wait maps. It
fails before the fix (`/tmp/htc-node-counter-before.log`) and passes after explicit
Integer content annotations, including arithmetic on both restored maps. The
shared mapper's Long treatment for genuinely untyped numbers remains unchanged.

## Six simulator cases

Inputs are the preserved seed-42 two-person/two-owned-Car scenario described in
[the MESO record](MESO_POLICY_VALIDATION_2026_10_23.md) and
[the earlier channel record](CONSERVATIVE_CHANNEL_VALIDATION_2026_10_23.md).
All runs use static routing, two LTMs and an actual JSON sink with batch size 1.
One-link plans require four 200 m trips; two-link plans require four 400 m trips.
Both require two daily summaries. The MICRO case uses two links on each trip.

| Check | Conservative MESO one | Optimistic MESO one | Conservative MESO two | Optimistic MESO two | Conservative MICRO two | Optimistic MICRO two |
|---|---:|---:|---:|---:|---:|---:|
| Trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 4 / 4 | 4 / 4 | 4 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 |
| Model entries / exits | 4 / 4 | 4 / 4 | 8 / 8 | 8 / 8 | 8 / 8 | 8 / 8 |
| Person/SUMO trip distance (m) | 200 | 200 | 400 | 400 | 400 | 400 |
| MESO completion ticks, both persons | 30 / 220 | 30 / 220 | 50 / 240 | 50 / 240 | — | — |
| Errors / replay mismatches / null-state discards / stalls | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| Rollbacks | 0 | 32 | 0 | 43 | 0 | 125 |
| Engine completion before launcher teardown | Yes | Yes | Yes | Yes | Yes | Yes |
| Independent outcome acceptance | Pass | Pass | Pass | Pass | Pass | Pass |

MESO acceptance checks actual plans/topology, activity sequence, per-person and SUMO
distances, matched entries/exits, cumulative odometers, density-dependent speed and
independently derived free-flow completion/handoff ticks. MICRO acceptance checks
plans, completed trips, daily distance and matched positive-distance traversals.
Runtime checks reject errors, replay mismatches, null-state discards, stalls or
missing engine completion. Optimistic cuts must have increasing round IDs and
nondecreasing GVT, with completion after acknowledged report drain.

Committed physical report **multisets match in all three policy pairs**: 52 rows
for MESO one link, 60 for MESO two links and 152 for MICRO two links. Comparison
retains multiplicity and every physical field, excluding run ID, wall-clock
`real_time` and diagnostic SUMO CPU `duration`. File arrival order is not compared.
The earlier 67 MICRO summary differences, including cumulative `loaded` and
`ended`, are absent on this artifact.

Directories under `/tmp/htc-person-replay-20261022/`:

- `ltm-final-cons-meso-single` and `accounted-ltm-final-meso-single`
- `ltm-final-cons-meso-multi` and `accounted-ltm-final-meso-multi`
- `ltm-final-cons-micro-multi` and `accounted-ltm-final-micro-multi`

Each has `run.conf`, `run.log`, `reports/`, `result.json` and strict acceptance
artifacts. The bounded launcher terminates the remaining cluster JVM after engine
completion; successful simulation completion is distinct from natural JVM exit.

Reproduce with the preserved launcher, fresh labels/ports and the appropriate
simulation JSON. For a MESO case:

```bash
python3 /tmp/htc-person-replay-20261022/run.py fresh-label \
  /tmp/htc-ltm-node-fixed-20261023.jar \
  /tmp/htc-person-replay-20261022/meso/time-warp-multi-meso.json 250
python3 scripts/scenario/validate_meso_reports.py \
  --simulation /tmp/htc-person-replay-20261022/meso/time-warp-multi-meso.json \
  --reports /tmp/htc-person-replay-20261022/fresh-label/reports \
  --log /tmp/htc-person-replay-20261022/fresh-label/run.log \
  --output /tmp/htc-person-replay-20261022/fresh-label/strict-meso.json --free-flow
python3 scripts/scenario/compare_policy_reports.py \
  --conservative /tmp/htc-person-replay-20261022/ltm-final-cons-meso-multi/reports \
  --optimistic /tmp/htc-person-replay-20261022/fresh-label/reports \
  --output /tmp/htc-person-replay-20261022/fresh-label/comparison.json
```

## Bounded performance comparison

The preserved campaign is `/tmp/htc-distributed-benchmark-20261023/`. Its generator
command is:

```bash
python3 scripts/scenario/generate_hybrid_scenario.py \
  --output-dir /tmp/htc-distributed-benchmark-20261023/scenario \
  --rows 2 --cols 2 --num-persons 32 --num-cars 32 \
  --num-bus-lines 0 --num-subway-lines 0 --micro-count 0 --seed 42
```

The deterministic adaptation gives every generated car owner the existing
Car-only node 1 → 4 → 1 plan at ticks 10/200/800, preserving owned vehicle IDs.
Cars start parked at node 1. Empty transit data sources are omitted: an initial
attempt retained generated empty JSON arrays, which the existing loader logged as
errors; that attempt is excluded from timing results. Expected outcomes are 64
trips of 400 m, 32 summaries and 128 matched MESO traversals.

`run_comparison.py` alternates serial baseline/current runs for three repetitions,
validates every run, and records `results.json` and `summary.json`. The baseline is
`/tmp/htc-central-node-fixed-20261023.jar` (central optimistic accounting), derived
from `/tmp/htc-conservative-channel-final-20261023.jar` with the same Node counter
correction applied. The original baseline failed the larger cohort during rollback
with `Long incompatible with Integer`; those failed runs are excluded.
`baseline-provenance.json` verifies that only the three NodeState class/metadata
entries differ from the original baseline; every other JAR entry CRC is unchanged.
The derived baseline SHA-256 is
`dc737b52d1dec789add641ed257a0ef14e768d485879b91ff467e0ecf435bce2`.
Both use the same generated inputs, configuration, four available JVM processors,
2 GiB heap, two LTMs and one JSON sink. Timing spans first LTM execution start to
acknowledged engine completion, excluding cluster startup and launcher teardown.
Throughput is committed trips per second; it is not the count of speculative
executions or delivered anti-messages. Current logs also expose round phase times
and drained transfer/receipt counts.

The comparison is for this whole patch, including the model state correction;
it does not isolate transport cost from other effects or certify city-scale speedup.

| Measurement | Centralized baseline, shared Node fix | LTM-owned accounting |
|---|---:|---:|
| Successful serial repetitions | 3 / 3 | 3 / 3 |
| Trips / summaries / matched traversals per run | 64 / 32 / 128 | 64 / 32 / 128 |
| Execution times (s), iterations 1 / 2 / 3 | 6.436 / 8.006 / 7.080 | 7.568 / 5.350 / 6.394 |
| Median execution time (s) | 7.080 | 6.394 |
| Median committed trips/s | 9.040 | 10.009 |
| Median rollbacks | 3050 | 3938 |
| Median GVT round count | 10 | 10 |

All three baseline/current output pairs match in all 960 committed physical rows.
The observed median is about 9.7% shorter execution time / 10.7% higher committed
trip throughput. The ranges overlap and the first pair is slower on the new path;
three repetitions cannot establish a general speedup. The larger number of
rollbacks is a real cost of the current execution, not committed simulation work.

For the LTM path, median per-run accumulated phase wall times are 2.791 s freezing,
0.114 s collecting snapshots, 0.346 s committing/report receipt, and 0.041 s
retiring/draining. They include waiting for actors/channels/sinks, not only GTM CPU
time. The median per-run fraction spent in these phases is 51.3%. Equivalent
phase instrumentation is absent in the baseline, so no per-phase speedup is claimed.
Final LTM receipt counts range from 11,076 to 12,008 per run, with outgoing and
accepting ledgers empty at final retirement. These observations prioritize bounded
speculation and measuring pause/drain costs before asynchronous GVT optimization.

Evidence is in `results.json`, `summary.json`, `baseline-provenance.json` and
`pair-{1,2,3}-payloads.json` under the preserved campaign directory. Timing runs use
the `accounted-bench-fixed-{central,ltm}-{1,2,3}` directories under the launcher root.

## Remaining scope

Progressive input still fully preloads for optimistic execution. Live model birth,
timestamped shared routing feedback, bounded histories/speculation, distributed
recovery/migration, nonblocking GVT, a second unrelated model and real multi-node
transport validation remain open. These results close the tested data-path and
MICRO-counter defects; they do not close every policy or model gap.
