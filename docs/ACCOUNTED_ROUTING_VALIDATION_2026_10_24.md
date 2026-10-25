# Accounted routing validation — 2026-10-24

The final executable passes **596 tests across 128 suites** and all **eight**
bounded routing scenarios below. Six complete physical-report multiset comparisons
match the one-JVM conservative references. Machine-readable results, exact inputs,
ports, artifact hashes, relay traffic and per-case acceptance are retained in
[`validation/accounted-routing-2026-10-24.json`](validation/accounted-routing-2026-10-24.json).

## Executable and focused regressions

Command: `sbt test assembly`.
Build log: `/tmp/htc-routing-recovery-final-build.log`.
Executable: `target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar`.
SHA-256: `3309f4a90aa118b6ddfe691f4b7b3b2bf67774c063b03f6d143303b095589e89`.
Every accepted scenario uses this same artifact.

- `RoutingServiceRollbackSpec`: cancelling a processed cost update restores its
  predecessor, retracts the old reply by identity, and reprocesses the surviving
  query to emit a replacement reply.
- `RoutingCheckpointSpec`: numeric cost-map keys, causal versions and pending
  Person/vehicle routing state survive JSON restore.
- `AccountedRoutingSourcesSpec`: exact membership, duplicate IDs within/across
  sources, lifecycle/loading requirements and sharded creation are enforced.
- `KryoEventDataSerializationSpec`: route and mode-decision payloads decode on an
  independent cold reader in a different first-use order.
- `LinkRoutingCostSpec`: empty MICRO links recover free-flow cost, equivalent
  physical speeds give equal MESO/MICRO penalties, and occupied stopped links
  retain their penalty. Existing MESO publication checks assert normalized m/s.
- Plan-manager and scoped-cache tests cover deferred decisions, supplied replies
  and nested cost-view isolation.

## Independent physical expectation

The seed-42 generator creates a 2x2 bidirectional grid, one Person, its Car,
three standalone Cars and two EAGER/STATIC routing workers (`9000001`, `9000011`).
The shorter outbound path is `1→2→4` (links `5,9`, 400 m); the alternate is
`1→3→4` (links `7,11`, 500 m). The reverse is `4→2→1` (links `10,6`, 400 m).
Link `1→2` has capacity 1; other links have capacity 100.

The precomputed background Car enters the short path at tick 0. Early queries must
select the alternate; after clearance, fresh queries must recover the short path.
The Person therefore completes three trips totalling `500 + 400 + 400 = 1300 m`.
The early and late standalone Cars exercise `RouteQueryData` directly, while the
Person exercises `ModeDecisionQueryData`. Their IDs select both routing workers.
Each run must have six destination-reaching vehicle journeys, six SUMO trip records
and 12 matched entries/exits on the exact expected links, with one daily summary.

The early query timing is mode-specific, derived from the physical model. MESO's
capacity-1 speed floor is 1 km/h: the background Car occupies its first 200 m link
for 720 ticks. The Person departs at tick 10 and the standalone query is at 20.
MICRO accelerates toward its speed limit and clears much sooner; its queries are
at ticks 2 and 3, while startup speed still makes the short path more expensive.
Both modes use the Person's final departure at 900 and the late standalone Car at
1700, with an execution horizon of 2600. MICRO position reporting is enabled at
every update. These cases do not assume that the two physical models have equal
travel times; comparisons are within each mode.

## Accepted matrix

All rows use two routing workers and one LTM per JVM. Two-JVM runs have separate
heaps and a TCP relay delaying each read by 3 ms; the result files record actual
relay connections/bytes. All cases complete without runtime errors, null-state
discards or stall diagnostics, and pass every physical acceptance check.

| Mode | Policy | JVMs | TCP delay | Physical rows | MICRO updates |
|---|---|---:|---:|---:|---:|
| MESO | conservative | 1 | 0 | 71 | 0 |
| MESO | optimistic | 1 | 0 | 71 | 0 |
| MESO | conservative | 2 | 3 ms/read | 71 | 0 |
| MESO | optimistic | 2 | 3 ms/read | 71 | 0 |
| MICRO | conservative | 1 | 0 | 551 | 258 |
| MICRO | optimistic | 1 | 0 | 551 | 258 |
| MICRO | conservative | 2 | 3 ms/read | 551 | 258 |
| MICRO | optimistic | 2 | 3 ms/read | 551 | 258 |

For each mode, the other three rows match its conservative one-JVM reference:
zero missing or extra records. Comparison excludes wall-clock timestamps,
scenario IDs and summary processing duration, retaining physical timestamps,
payloads and duplicate multiplicity. Successful engine shutdown alone is not an
acceptance criterion. The harness terminates development JVMs after acknowledged
report flushing; its process exit codes are not independent clean-shutdown evidence.

## Reproduction and retained artifacts

```bash
python3 scripts/scenario/generate_dynamic_routing_validation_scenario.py \
  --output-dir /tmp/routing-meso --mode MESO --workers 2
python3 scripts/scenario/generate_dynamic_routing_validation_scenario.py \
  --output-dir /tmp/routing-micro --mode MICRO --workers 2

python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar \
  --simulation /tmp/routing-micro/time-warp.json \
  --extra-config /tmp/routing-micro/routing.conf \
  --output /tmp/routing-micro-tw-2jvm --base-port 29300 \
  --nodes 2 --delay-ms 3 --timeout 150

python3 scripts/scenario/validate_dynamic_routing_reports.py \
  --reports /tmp/routing-micro-tw-2jvm/reports \
  --expected /tmp/routing-micro/expected.json

python3 scripts/scenario/compare_policy_reports.py \
  --conservative /tmp/routing-micro-de-1jvm/reports \
  --optimistic /tmp/routing-micro-tw-2jvm/reports \
  --output /tmp/routing-micro-comparison.json
```

Generate/run the conservative reference with `discrete-event.json`, one JVM and
zero delay. Use fresh output directories. Parallel runs need disjoint port ranges:
with delay enabled the harness binds transport at `base-port + 100` as well as
advertising `base-port`. The accepted conservative MESO rerun uses base 28000 after
an earlier parallel harness attempt collided with another run's advertised ports.

Raw evidence root: `/tmp/htc-routing-recovery-20261024/`. Accepted case directories:

- `final-meso-discrete-event-1jvm`, `final-meso-time-warp-1jvm`;
- `accepted-meso-discrete-event-2jvm`, `final-meso-time-warp-2jvm`;
- `accepted-micro-{discrete-event,time-warp}-{1,2}jvm`.

MESO inputs are in `final-meso-input/`; MICRO inputs are in
`accepted-micro-input/`. Each accepted run retains `result.json`,
`acceptance.json`, reports, per-node `command.json`, `run.conf`, raw logs and
execution-only logs. Failed earlier fixture/build/port attempts are excluded.
The repository retains the summary JSON and generator/validator dependencies even
if reboot removes `/tmp`.

## Limits

These are bounded car scenarios over loopback with JSON reporting, replicated
road maps and publication interval 0 to expose every feedback change. They do not
validate city-scale snapshot cost, throughput, default-interval fidelity, external
live feedback, physical multi-host partitions, coordinated recovery or migration.
Transit route initialization stays static. PT-timeout deferral is wired into the
adapter but is not exercised by this car-only end-to-end matrix. The forced
anti-message regression is a focused actor test; no rollback count is assumed for
the scenario runs. See [the protocol](ACCOUNTED_ROUTING.md) for supported scope.
