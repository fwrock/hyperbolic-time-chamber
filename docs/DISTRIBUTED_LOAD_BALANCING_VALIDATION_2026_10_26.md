# Production conservative load-balancing acceptance — 2026-10-26

Custom balancing now uses the production conservative runtime, real creation
workers, native Pekko handoff, acknowledged snapshot restore and reporting. It
remains disabled by default. This is bounded engineering acceptance; it does not
establish city-scale performance or empirical traffic accuracy.

The machine-readable record is
[load-balancing-completion-2026-10-26.json](validation/load-balancing-completion-2026-10-26.json).
The final executable is retained as `/tmp/htc-load-balancing-final-20261026.jar`
(SHA-256 `8e66d7a3c81dd2d96e091890248be2c54542179951e44e19383783c055929791`).
Raw scenarios, per-node commands/configurations, execution logs, reports and
measurements are retained under
`/tmp/htc-balancing-traffic-validation-20261025`. Executable hashes are recorded per
case; the harness verifies that the JAR did not change during each run.

## Accepted behavior

| Case | Expected and observed physical outcomes | Migration/output evidence |
|---|---|---|
| Initial two-JVM cohort, seed 413 | 60 persons, 120 completed car trips, 68,800 m | Four verified handoffs before trip departure; all 2,008 report rows match the fixed-placement reference. |
| Progressive cohort, seed 413 | 60 persons in cohorts at ticks 0/10050, 120 completed car trips, 19,840,000 m | Three verified handoffs; five Cars restored while occupying MESO roads at tick 7377; all 2,312 report rows match. |
| Startup-cleanup build, progressive cohort with 5 ms TCP-read delay | Same 120 trips, endpoints and distances | Two verified handoffs at ticks 10050/10300; five Cars restored during road occupancy at tick 10050; all 2,312 report rows match the final-build reference. |
| Final settings-validated build, progressive cohort with 1 ms TCP-read delay | Same 120 trips, endpoints and distances | Two verified handoffs at tick 7377; five Cars restored during road occupancy; all 2,312 report rows match the same-build reference. |
| Station-created transit, seed 42, two JVMs | Two Bus births and two Subway births; both planned PT trips complete with 400/1400 m and one boarding/alighting each | MICRO Bus and cyclic rail traversal checked against inputs, including all four censored horizon reports. All 1,886 report rows match normal hash placement. This case validates birth publication and transit behavior; it does not claim transit-vehicle relocation. |
| Independent directory/allocation fixtures | Published spatial and hash IDs, both routing JVMs hydrated before creation, counter 23 preserved at planned physical target | Both fixtures pass without JVM-local allocator ownership and with zero execution errors. Their Time Manager is an explicit pause probe; traffic acceptance above supplies the production runtime evidence. |

Every accepted positive traffic run completes through its runtime marker and
reporter drain, has zero execution errors, and retains its executable identity.
The long-road progressive fixture deliberately keeps existing Cars active during
the next input window. Its 40 km links are an artificial protocol stress case,
not a calibrated urban network.

`validate_balancing_traffic.py` derives trip identity, endpoints and shortest road
distance from the input plans and directed graph. It checks Person trips/daily
totals, vehicle lifetime odometers, completed Link distances, each SUMO trip's
endpoints/distance and completion before the horizon. Active migration additionally
requires an actual snapshot restore offer for a Car whose enter/leave interval
contains that wave's closed runtime tick. A shard count or a handoff log alone is
insufficient. Successful runtime completion, verified placement/restore and
whole-report parity are separate requirements.

## Failure and lifecycle checks

Worker-loss injection kills the second owned JVM after the real migration-freeze
marker. The run must explicitly fail without a conservative/optimistic completion
marker; reporter flushing during cleanup is allowed and is not classified as
success. Test-only cluster failure-detector settings accelerate fault observation;
the simulation protocol adds no timeout recovery or elapsed-time success.

Custom Time Warp startup is rejected with its conservative-runtime requirement.
The CLI logs failure, stops its metrics server and terminates its ActorSystem.
The shared startup reader also rejects invalid strategy/capacity/depth settings,
nonpositive metrics intervals and unusable world bounds before creating regions.
There is no hash fallback or partially accepted optimistic migration.

Protocol tests cover accepted queued input and an unfinished reply across physical
replacement, stable owner/schedule identity, every freeze/resume ACK, requests
arriving during resume, manual pause, initial progressive loading, authorized
source stop, unexpected source loss, stale correlations and terminal decline.
The manager regression verifies that a decline clears admission without snapshot
work, model resume or acceptance of further plans.

The full Scala suite passes **769 tests in 152 suites** using
`sbt -J-XX:ActiveProcessorCount=4 test`; Python discovery passes **28 tests**. Cold
codecs preserve the new protocol messages and checkpoint frames on independent
serializer instances. `sbt assembly` and `git diff --check` pass.

## Reproduction

Generate a fresh fixture; the generator also writes the two balancing configs:

```bash
python3 scripts/scenario/generate_balancing_validation_scenario.py \
  --output-dir /tmp/htc-balancing-reproduction --late-cohort
sbt -J-XX:ActiveProcessorCount=4 test assembly
python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-3.2.0.jar \
  --simulation /tmp/htc-balancing-reproduction/simulation.json \
  --extra-config /tmp/htc-balancing-reproduction/reference.conf \
  --output /tmp/htc-balancing-reference --nodes 2 --base-port 21000 --timeout 240
python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-3.2.0.jar \
  --simulation /tmp/htc-balancing-reproduction/simulation.json \
  --extra-config /tmp/htc-balancing-reproduction/migration.conf \
  --output /tmp/htc-balancing-migration --nodes 2 --base-port 21200 \
  --timeout 240 --delay-ms 5
python3 scripts/scenario/validate_balancing_traffic.py \
  --simulation /tmp/htc-balancing-reproduction/simulation.json \
  --run /tmp/htc-balancing-migration --require-migration --require-active-migration \
  --output /tmp/htc-balancing-physical.json
python3 scripts/scenario/compare_policy_reports.py \
  --conservative /tmp/htc-balancing-reference/reports \
  --optimistic /tmp/htc-balancing-migration/reports \
  --output /tmp/htc-balancing-parity.json
python3 -m unittest discover -s scripts/scenario -p 'test_*.py'
```

The report comparator's historical `--optimistic` argument accepts the migrating
conservative output here. Both runs use the same conservative policy. Comparison
normalizes only nondeterministic run identity/wall-clock fields already excluded
by the comparator; physical model output is not relaxed. The low prediction
threshold and short collection/rebalance intervals deliberately exercise protocol
transitions and are not recommended performance settings.

## Rejected evidence and remaining scope

Earlier no-move runs establish initial placement only. An uneven fixture exposed
the proxy/sender mismatch and first-window request race; it is retained as failed
evidence. A later run completed every physical report but failed on a rebalance
request after terminal retirement began. Its report parity is not counted as
runtime acceptance; the correlated terminal-decline repair is required.

An overlapping full-suite run hit logger initialization, cluster-membership and
handoff timeouts. The isolated full-suite pass supplies test acceptance. A short
startup measurement expired before reaching policy validation, and an aggressively
configured failure detector observed an unreachable member before the requested
kill; neither establishes the intended injection/rejection test. All exclusions
are listed in the machine-readable record rather than folded into successful
results.

Independent process restart, singleton failover, changing cluster membership,
optimistic migration and rewriting live geographic memberships remain unsupported.
Spatial membership stays fixed after creation; overflow opens new buckets. The
published directory is replicated to every routing JVM, migration pauses the
conservative run globally, and checkpoint queues add transfer/storage cost. Live
entity counts are a workload proxy, not CPU measurements. No speedup, memory bound,
prediction-quality result, adaptive live repartition or new scientific novelty is
established by these tests.
