# Progressive optimistic input validation — 2026-10-24

This validates [acknowledged source windows](PROGRESSIVE_OPTIMISTIC_RUNTIME.md)
on the production LTM-owned optimistic runtime. Each process selects conservative
or optimistic execution before startup and keeps that policy for its entire run.
Loading and execution alternate at enrollment barriers; background prefetch and
speculative model-caused birth are outside this change.

## Tested executable and regressions

`sbt test assembly` passed **532 tests across 112 suites**. Build log:
`/tmp/htc-progressive-stream-final-build.log`. Validated executable:
`/tmp/htc-progressive-validation-20261023/htc-progressive.jar`.
Assembly hash: `aa12190a29a19e6b8601a9070063c8b93617e954`.
SHA-256: `c4de4b58b26064daf90b4af636e2cada500f67c1e5f81cca765b49337e18d9fe`.
The artifact directory retains the date when the scenario preparation began.

New focused coverage includes:

- An open producer holds GVT at tick 11 despite later model input or entirely
  empty model queues. No settled completion occurs until explicit source closure,
  LP retirement and final sink drain.
- A cohort can enroll exactly at GVT, joins frozen, and appears in the next fresh
  snapshot. Enrollment below GVT or outside a loading barrier fails explicitly.
- Legacy forwarded EOF cannot overtake the final producer acknowledgement.
  Stale replies, producer loss and source failures cannot produce successful completion.
- JSON and SQLite wait for both creator batch acknowledgement paths, ignore
  unrelated/duplicate batch ACKs, and leave later source actors for later windows.
- Empty sources are valid. Missing sources, failed reads/queries and index/range
  count mismatches fail rather than acknowledge successful empty input.
- The JSON parser decodes 1000 UTF-8 records across repeated buffer refills without
  `readAllBytes`; an instrumented stream forbids that method and checks that parser
  creation leaves most input unread. Empty, truncated and malformed arrays are tested.
- New distributed window/enrollment commands survive the production Kryo codec.

The existing thousand-tick, one-tick-window retention regression also passes.
An earlier full-suite run exceeded its 20-second test deadline while still making
GVT progress; its assertion deadline is now 45 seconds. No runtime timeout was
added to complete an actor obligation.

## Scenario and independent expected outcomes

Inputs, source databases, launchers, logs and comparisons are under
`/tmp/htc-progressive-validation-20261023/`. Generate the base using:

```bash
python3 scripts/scenario/generate_hybrid_scenario.py \
  --output-dir /tmp/htc-progressive-validation-20261023/scenario \
  --rows 2 --cols 2 --num-persons 2 --num-cars 2 \
  --num-bus-lines 0 --num-subway-lines 0 --micro-pct 100 --seed 42
```

The preserved derived manifests use the generated IDs and graph with deterministic
Car-only plans `1 -> 4 -> 1`. Person/car pair 14/13 starts at tick 0; pair 16/15
starts at tick 1200. Departures are 10/200 and 1210/1400. Final activities end at
800/2000; the finite horizon is 2200. Each trip follows two 200 m links, so expected
outcomes are **4 completed trips, 2 daily summaries and 8 matched link entries/exits**.
Distances must be 400 m per trip and 800 m per person. All links use MESO or all use
MICRO in separate cases. Static routing is enabled; transit and shared feedback
are absent. Generated empty transit sources remain configured, exercising empty
array loading without errors.

SQLite Person/Car files were converted with
`../tools/scenario-db-converter/convert.py actor-simulation json-to-db`. Each SQLite
case validates against the corresponding original JSON plans and topology. The
preloaded reference changes Person/Car sources to EAGER; other manifests use
PROGRESSIVE. Each case runs two LTMs in one cluster JVM with four active processors
and a 2 GiB maximum heap. This is a correctness check, not a capacity benchmark.

The current conservative bootstrap chooses a 5000-tick look-ahead for this horizon,
so its progressive-source cases load both pairs initially. Those cases validate
source-format and policy parity; they are not new conservative late-enrollment
proofs. Optimistic execution caps its window at 1000 ticks and demonstrably starts
with only the first person/car pair loaded. Conservative window protocol tests
remain separate from this optimistic enrollment change.

## Results

Columns marked C / O list conservative / optimistic results. Outcome columns show
actual / expected counts per run, or matched entries / exits per run. All 12 cases
pass independently expected outcomes.

| Link mode | Person/Car input | Trips / expected, each policy | Daily summaries / expected, each policy | Entries / exits, each policy | Rollbacks C / O | Physical rows C / O | Strict acceptance |
|---|---|---|---|---|---|---|---|
| MESO | JSON progressive | 4 / 4 | 2 / 2 | 8 / 8 | 0 / 0 | 60 / 60 | Pass / Pass |
| MESO | JSON preloaded | 4 / 4 | 2 / 2 | 8 / 8 | 0 / 0 | 60 / 60 | Pass / Pass |
| MESO | SQLite progressive | 4 / 4 | 2 / 2 | 8 / 8 | 0 / 0 | 60 / 60 | Pass / Pass |
| MICRO | JSON progressive | 4 / 4 | 2 / 2 | 8 / 8 | 0 / 7 | 228 / 228 | Pass / Pass |
| MICRO | JSON preloaded | 4 / 4 | 2 / 2 | 8 / 8 | 0 / 7 | 228 / 228 | Pass / Pass |
| MICRO | SQLite progressive | 4 / 4 | 2 / 2 | 8 / 8 | 0 / 8 | 228 / 228 | Pass / Pass |

Every case has zero exception/error lines, replay mismatches, null-state discards
and stall warnings. MESO completion ticks are 50/240 and 1250/1440 for both policies;
its validator checks free-flow handoffs, per-link speed/travel-time laws, connected
routes, journey/SUMO distances and daily plans. MICRO checks actual per-person plans,
distances, activity order, daily summaries and matched positive-duration traversals.

Ten multiset comparisons against the corresponding preloaded conservative reference
match **all committed report contents**, including MICRO cumulative SUMO counters.
Only run ID, wall-clock report timestamp and SUMO summary CPU duration are excluded.
Report order is not compared. No surviving physical field is omitted to force parity.

Both optimistic progressive formats produce this accepted-window sequence:

| Window | Accepted through | Source closed | Newly initialized LPs |
|---|---|---|---|
| 1 | 1000 | No | 2: first Person and Car |
| 2 | 2001 | Yes | 2: second Person and Car |

Logs place runtime startup and GVT rounds before window 2, with GVT reaching 1001
before that cohort is accepted. It never passes the open source bound. Final GVT
is `Long.MaxValue`, status `completed`, and report receipt/retirement/drain finish
before ReportManager's stop marker. The bounded launcher then terminates the dev
cluster process if necessary; forced JVM teardown is not counted as engine completion.

Reproduction and machine-readable evidence:

- `generation.txt`, `scenario/data/`, `meso-*.json` and `micro-*.json`: inputs.
- `run.py`, `run_matrix.py`, `verify_micro.py`: bounded launch and validation recipes.
  Use a fresh label prefix/output directory on rerun to avoid mixing old report files.
- `matrix-verified.log`, `matrix.json`, `complete.json`: all 12 acceptances and 10 comparisons.
- `progressive-verified-*` and `accounted-progressive-verified-*`: accepted run directories,
  each containing `run.conf`, `run.log`, `result.json`, reports and outcome checks.
- `scripts/scenario/validate_meso_reports.py` and
  `scripts/scenario/compare_policy_reports.py`: repository validators.

The earlier `progressive-final-*` directories retain the rejected pre-streaming
runs: generated empty arrays triggered parser errors. Their executable is preserved
as `htc-progressive-before-streaming.jar`; those runs are not accepted evidence.

## Scope still open

This closes deterministic source-window enrollment for the tested sharded Car/Person
MESO/MICRO scenarios, with core creator ACK paths covered by focused tests. It does
not validate arbitrary models, live external streams, future-address buffering,
reversible child birth, shared speculative feedback, independent recovery/migration,
real multi-JVM transport, durable sink retry or one million actors. JSON still
rescans files per window; count indexes, live model state, queues, global pauses
and actor population remain resource costs. Earlier bounded retention measurements
refer to their own artifact and are not new throughput measurements of this build.
