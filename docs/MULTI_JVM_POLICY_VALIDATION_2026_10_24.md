# Conservative and optimistic execution across JVMs — 2026-10-24

This follows the [actor-birth milestone](RUNTIME_ACTOR_BIRTH_VALIDATION_2026_10_24.md)
and tests the production managers over real Pekko Artery TCP. Every run selects
one policy before startup and keeps it for all participating processes.

## Defects reproduced and fixed

The first two-JVM launches failed before useful simulation work. All earlier codec
round trips had used the same codec instance for writing and reading. That warmed
its implicit registrations and concealed the following problems:

1. Kryo's incremental resolver emits numeric IDs for implicitly registered types.
   A cold codec in another JVM cannot recover an unlisted class from that ID.
   `IndependentRuntimeCodecSpec` reproduces the failure using independent writer
   and reader ActorSystems with different first-use histories. The configuration
   now uses `automatic`: existing ordered registrations keep compact IDs, while
   unlisted runtime, model and nested types carry class names.
2. Remote actor deployment needs a serializer for `java.lang.Class` constructor
   arguments. It now uses Kryo. Java serialization remains disabled.
3. Both production LTM factories used by-name creator closures in `Props`. Their
   captured class/function arguments were unsuitable for remote deployment. The
   factories now use explicit class constructors with duration and ActorRefs.

All three new regressions fail on the previous code. The existing protocol and
model payload codec regressions pass with the new configuration. Use the same
executable and registration list on every node; this does not establish mixed-build
rolling-upgrade compatibility or restore old implicit-ID serialized data.

The transit matrix then exposed a separate configuration defect under both
policies: the remote Person's JVM logged `No transit routes file configured`,
skipped the Bus leg, and produced only one of two expected PT completions. Only
the SimulationManager singleton installed transit settings in its process-local
registry. `SimulationUtil.startShards` now installs scenario duration and optional
city/transit-map/route paths on every node before creating shard actors or lazy
model resources. SimulationManager uses the same helper. This is startup-file
configuration; distributing later REST/API setting changes is outside this fix.

The late-input matrix also reproduced a startup race in the existing WarmUpManager:
a one-time pool broadcast could precede remote worker creation. The local worker
finished while the remote worker never received a request, leaving startup waiting
forever. Workers now announce readiness with canonical node addresses. The manager
sends targets once per registered worker, counts unique completions, rejects duplicate
node claims, and propagates worker loss/restart or target failure. It accepts ready
and start in either order. A late-worker regression completes the first worker
before introducing the second. No retry timer or synthetic completion is used.

No new global coordinator was introduced. Each LTM logs its accepted membership
once, and aggregate channel accounting includes cross-owner transfer counts.
With one LTM per process, these counts prove model traffic crossed JVM boundaries.

## Tested executable

`sbt test assembly` passes **582 tests across 124 suites**. Build log:
`/tmp/htc-multijvm-20261024/readiness-final-build.log`.
Executable: `/tmp/htc-multijvm-20261024/htc-ready.jar`.
Assembly hash: `7f98606e0bb2ae1731829de19380e53984461589`.
SHA-256: `b6ca887a82079abaa280058c463fe1d3ff6c2dad2331670718c5dc6e1ef2eb44`.

The earlier `*-initial` runs reproduce the codec/deployment failures, while
`final-transit-micro-*` reproduces the missing remote-node settings. They are
rejected evidence. `verified-transit-late-discrete-event` preserves the missed
warm-up broadcast. Final results use the separate `ready-*` labels.

## Method and reproduction

`scripts/scenario/run_cluster_validation.py` starts one or two JVMs with separate
canonical, management and metrics ports, one LTM per JVM and a minimum cluster
membership equal to the requested process count. It preserves exact commands,
configuration, raw node logs, execution-log prefixes and a SHA-256-tagged result.
Outputs must be fresh. Every process reads the same immutable scenario/resources.

```bash
python3 scripts/scenario/run_cluster_validation.py \
  --jar /path/to/htc.jar --simulation /path/to/simulation.json \
  --output /tmp/htc-two-jvm-example --base-port 24000
```

The bounded matrix reuses the seed-42 generated 2x2 scenarios from the preceding
milestone. Source files and topology are copied into
`/tmp/htc-multijvm-20261024/inputs/`; `manifest.json` preserves original paths and
single-JVM references. `prepare_inputs.py` preserves that copying recipe. The
independent expected outcomes remain:

- Car MESO/MICRO: two persons, four 400 m trips, two 800 m daily summaries and
  eight matched road traversals, including connected routes and per-trip SUMO distance.
- Transit MICRO: two PT completions/summaries, one boarding/alighting per mode,
  station-created Bus/Subway and their cyclic physical service to horizon 1000.
- Late transit: initial source window through 1000 contains no Persons; stations
  create vehicles for departure 1200, and the later two Persons complete their
  trips before horizon 2200.

The Car validator keeps its historical filename `validate_meso_reports.py`;
`--mode MICRO` additionally checks matched positive-duration MICRO traversals,
link geometry, route connectivity, trip/daily distances and journey/SUMO reports.
The transit validator retains the preceding milestone's explicit horizon-censor
rules. Neither validator accepts successful engine termination alone.

Each normal case must also show exactly one runtime membership per node, two Up
members, nonzero cross-owner traffic on both nodes, equal transfer/receipt totals,
and empty outgoing/acceptance ledgers at completion. All committed report contents
are compared with the single-JVM reference and the other policy. Only run ID,
wall-clock report timestamp and SUMO summary CPU duration are excluded.

The launcher can insert transparent TCP relays with `--delay-ms 10`. Each relay
preserves byte order and applies delay/backpressure in both directions. This
exercises slower Artery transport and cross-channel timing; it does not reorder
bytes within a TCP connection, simulate packet loss or establish partition safety.

Worker loss uses `--kill-node 1 --kill-after '<log marker>' --extra-config <file>`.
The harness kills only its own child JVM. A shortened Pekko failure-detector/SBR
configuration bounds the test duration; no model-reply timeout or recovery watchdog
is added. Acceptance requires explicit run failure after injection and forbids
successful policy completion/report drain. Partial outputs are not accepted as
completed simulation results.

Execution logs are captured before deliberate cluster teardown; full raw node logs
also retain shutdown messages. Killing remaining development JVMs after acknowledged
completion is separate from engine completion. Ordinary startup can include Pekko
shard-coordinator registration retries; this is not a runtime stall workaround.

## Results

All ten normal/delayed scenario runs pass independent physical acceptance. Both
worker-loss injections correctly fail the simulation, with no successful completion
marker or report-drain success. C / O denotes separate conservative / optimistic
runs. Trip and summary counts are actual / expected in each run.

| Case | Trips / expected | Daily summaries / expected | Road entries / exits | Rail entries / exits | Physical rows C / O | Rollbacks C / O | Outcome / report parity |
|---|---|---|---|---|---|---|---|
| Car MESO | 4 / 4 | 2 / 2 | 8 / 8 | — | 60 / 60 | 0 / 35 | Pass / Match |
| Car MICRO | 4 / 4 | 2 / 2 | 8 / 8 | — | 152 / 152 | 0 / 67 | Pass / Match |
| Bus MICRO + Subway | 2 / 2 | 2 / 2 | 40 / 39 | 15 / 14 | 1061 / 1061 | 0 / 227 | Pass / Match |
| Late Persons + transit MESO | 2 / 2 | 2 / 2 | 51 / 50 | 15 / 14 | 171 / 171 | 0 / 2 | Pass / Match |
| Car MESO, delayed TCP | 4 / 4 | 2 / 2 | 8 / 8 | — | 60 / 60 | 0 / 27 | Pass / Match |

All successful runs have zero engine errors, replay mismatches, null-state discards
and runtime stalls. Each normal case shows two distinct warm-up ready/done nodes,
exactly one LTM membership per JVM, actual cross-owner traffic from both LTMs, and
empty delivery/acceptance ledgers at successful completion. The Car cases finish
with `completed`; finite cyclic transit uses acknowledged `horizon-truncated` status
with one final censored road/rail traversal per vehicle, as in the preceding record.
Every transit case completes one boarding/alighting per mode and both planned PT trips.

| Case | Cross-owner transfers C: node 0 / node 1 | Cross-owner transfers O: node 0 / node 1 |
|---|---|---|
| Car MESO | 19 / 19 | 33 / 35 |
| Car MICRO | 19 / 101 | 31 / 151 |
| Bus MICRO + Subway | 137 / 566 | 137 / 566 |
| Late transit | 163 / 148 | 165 / 148 |

Cross-owner counts include optimistic anti-messages and re-execution. They are
transport evidence, not throughput or rollback-reduction measurements. Fifteen
full report-multiset comparisons pass: ten against the corresponding single-JVM
reference, four normal policy pairs and the delayed policy pair.

The delayed runs pass through four relay connections each, carrying 766 / 756
chunks (619228 / 892046 bytes) in C / O. The 10 ms delay is applied per read in
both directions, rather than asserted as a fixed measured packet RTT.

The late optimistic run accepts an empty Person window through 1000, reaches GVT
1001 before accepting the two Persons through 2001, and closes the source explicitly.
Both station-created vehicles already exist with departure 1200. PT completion
ticks remain Bus 1280 / Subway 1313 under both policies.

| Fault test | Trigger / outcome | Successful engine completion |
|---|---|---|
| Conservative worker JVM loss | Kill JVM 1 after the start request; `CONSERVATIVE-FAILED` reports `conservative worker lost`, followed by `SIMULATION-FAILED`. | No |
| Optimistic worker JVM loss | Kill JVM 1 after the first GVT round; `RUNTIME-FAILED` reports `runtime LTM lost`, followed by `SIMULATION-FAILED`. | No |

The shortened detector settings are preserved in `fast-failure-detector.conf`:
3 s heartbeat pause, threshold 8, 1 s expected response/heartbeat and 2 s SBR
stable-after. They affect participant-loss detection only; the runtime never turns
a missing reply into a successful finish or attempts independent recovery.

Machine-readable evidence under `/tmp/htc-multijvm-20261024/`:

- `complete-ready.json`, `matrix-ready.json`, `ready-*-policy-comparison.json`:
  eight normal cases and all policy comparisons.
- `transport-checks-ready.json`, `protocol-evidence.json`: delayed transport,
  source-boundary proof and both expected worker-loss failures.
- `ready-*/`: per-node raw/runtime logs, exact configuration and command, reports,
  SHA-tagged launcher results, physical acceptance and single-JVM comparisons.
- `manifest.json`, `inputs/`, `prepare_inputs.py`: copied scenarios and references.
- `run_matrix_ready.py`, `run_transport_ready.py`, `matrix-ready.log`,
  `transport-ready.log`: reproduction recipes and validation output. Use fresh
  labels for reruns; existing successful outputs can be revalidated without
  appending reports.

## Limits

The processes run on one host over loopback, with separate heaps and real TCP,
using shared input/output filesystem paths, static routing and JSON reporting.
This does not validate multiple physical machines, partitions, coordinated recovery,
ownership migration, live distributed API updates or Kafka/ClickHouse durability.
It provides no throughput or million-actor capacity claim. Paused GVT rounds, shared
speculative routing feedback and uniform horizon reporting retain their documented
limitations. The earlier one-JVM validation records keep their original scope.
