# Node-local tick scheduler: bounded validation

Date: 2026-11-17. This validates the initial opt-in implementation, not throughput,
city-scale behavior or new physical traffic-model skill. See the
[runtime guide](NODE_LOCAL_TIME_MANAGER.md) and
[design/literature record](NODE_LOCAL_TIME_MANAGER_DESIGN.md).

## Executable and cases

Assembly: `target/scala-3.3.5/hyperbolic-time-chamber-3.9.0.jar`.
SHA-256: `a4cdb34d289fe49653640a23307e9df93fb3d66f66208a003971683ee3396d19`.
Both strategies used this exact executable in the final comparisons.

Both generated cases use a 3x3 grid, 9 Nodes, 24 road Links, 8 Persons owning
8 Cars, three daily activities per Person, no transit services and generator seed
71. The simulation policy is explicitly `discrete-event`, with `randomSeed=71` and
the generated one-second clock/horizon. Plans imply 16 completed car trips and
8 daily summaries. MESO uses zero MICRO Links; MICRO uses all 24.

Both cases ran in two separate JVMs with actual Pekko remote deployment, sharding,
progressive JSON input and JSON reporting. New strategy: one dispatcher and three
private partition workers per JVM. Reference: `cluster-pool`, one LTM per JVM,
direct conservative transport. Live load balancing and shared routing cache are
disabled. The launcher's `localTimeManagersPerNode=1` counts node representatives
for the new option; it does not describe the three child partition workers.

## Outcomes

| Case and strategy | Completed trips | Daily summaries | Road entries/exits | Runtime errors/stalls |
|---|---:|---:|---:|---:|
| MESO, cluster-pool | 16 | 8 | 30 / 30 | 0 / 0 |
| MESO, node-local-tick | 16 | 8 | 30 / 30 | 0 / 0 |
| MICRO, cluster-pool | 16 | 8 | 32 / 32 | 0 / 0 |
| MICRO, node-local-tick | 16 | 8 | 32 / 32 | 0 / 0 |

Within each case, strategies match every Person's trip completion ticks and trip
distances. The validator also checks connected routes, actual input geometry,
matching entries/exits, journey/SUMO completion, complete activity sequences and
daily totals. MICRO checks physical traversal fields and positive travel time.
This is stronger than counting events or checking exit status alone.

Both GTMs report completed at tick 86400 and reporters drain before launcher
teardown. The launcher subsequently stops the remaining cluster processes; their
teardown return codes are not simulation success criteria. Final runtime logs and
outcome validation establish success before teardown.

This is one seed and a bounded car/person cohort. It does not validate transit
birth, live migration, membership changes, independent recovery, fractional clocks,
all traffic-control combinations or performance. The runs were not a controlled
equal-resource timing experiment; do not interpret elapsed time as a speedup result.

## Protocol and regression checks

Targeted ScalaTest suites passed:

- `TickScheduleAssignmentsSpec`: independent per-tick counts (900/600 events across
  three workers), wake-up coalescing, claim authority, acceptance and reclamation.
- `NodeLocalTimeManagerSpec`: independent tick-11/tick-12 schedules across finishes,
  per-tick worker distribution, delayed descendants, duplicate input, dispatch-window
  refill, current-tick normalization and failure on partition loss.
- `DirectConservativeCodecSpec`: old and new protocol messages through independent
  cold codecs, including actor references and partition root envelopes.
- Existing direct conservative execution, input fences, physical migration,
  channel-closed/progressive barriers, horizon reporting, schedule indexes, settings
  overlay, rollback resynchronization and distributed optimistic runtime regressions.
- `CarMesoTraversalSpec` and `CarMesoCapacitySpec`: existing conservative/optimistic
  traversal and origin-end handoff behavior after the conditional model adapter.

The final model/runtime batch ran 16 tests in four suites successfully. A separate
compatibility batch ran 24 tests in seven suites successfully. Earlier focused
accounting/closure checks also passed. These are targeted suites, not the full
repository test suite. Main/test compilation and assembly passed. Scalafmt checks
passed for the five new production and two new test files; legacy files were not
reformatted wholesale. `git diff --check` passed.

Protocol review followed `AGENTS.md` and the protocol-review skill:

| Exchange | Obligation and closure rule |
|---|---|
| Dispatcher to worker: store wake | Ownership is recorded first; worker stores before ACK, including an exact duplicate |
| Dispatcher to worker: grant | Worker publishes bounded claims before its grant receipt; queued due events still fence closure |
| Worker to model: root | Node records the pending root, then worker enrolls its ledger before sending the model event |
| Receiver callback to node | Node validates source/incarnation, applies effects and holds accounting completion until schedule storage ACKs |
| Node to cascade worker | Parent/child ledger handles early receipts and exact duplicates without dropping obligations |
| Worker to node: roots settled | New claims precede settlement notification; node retains all root obligations until acknowledged settlement |
| Node to GTM: completion/frontier | Existing global settlement followed by fresh summaries, with additional partition/handoff fences |

Business reply waits remain conditional. No model handler sends runtime ACKs, and
no timeout authorizes completion. Partition failure is fatal rather than retried
against lost state.

The bounded mobility check exposed an important integration constraint: the existing
versioned model path uses same-tick continuation after verified Node admission.
Simply discarding that request loses progress; simply moving it to the following
tick adds intersection dwell. The new option continues verified zero-delay vehicle
transitions inside their tracked callback. Generic late wake requests normalize to
the next tick, while pending effective actor/tick wake-ups coalesce. Other strategies
keep their original paths. The final mobility parity checks exercise this distinction.

## Reproduction and artifacts

Artifacts are in `/tmp/htc-node-local-20261117/`:

- `mobility/`, `micro/`: generated scenarios and actor input files.
- `node-local.conf`, `cluster-pool.conf`: strategy overrides.
- `meso-final/`, `meso-baseline-final/`, `micro-final/`, `micro-baseline/`: per-JVM
  configuration, launch commands, runtime logs, reports and executable hashes.
- Corresponding `*-outcome.json` files and `validation-summary.json`: validated
  outcomes and strategy parity.

Temporary artifacts may be removed by OS cleanup. The commands and results here
are retained in the repository. Use a fresh output directory for every rerun.

Generate each case with:

```bash
python3 scripts/scenario/generate_hybrid_scenario.py \
  --output-dir /tmp/htc-node-local-recheck/meso \
  --rows 3 --cols 3 --num-persons 8 --num-cars 8 \
  --num-bus-lines 0 --num-subway-lines 0 --micro-pct 0 --seed 71
```

For MICRO, use another directory and `--micro-pct 100`. Set the resulting
`simulation.json` fields `timeManagerType` to `discrete-event` and `randomSeed` to
71. The generated files use absolute paths, so their hashes depend on the chosen
output directory. Use the runtime guide's node-local configuration in an override
file, then run:

```bash
python3 scripts/scenario/run_cluster_validation.py \
  --jar target/scala-3.3.5/hyperbolic-time-chamber-3.9.0.jar \
  --simulation /tmp/htc-node-local-recheck/meso/simulation.json \
  --output /tmp/htc-node-local-recheck/meso-node-local \
  --base-port 27900 --nodes 2 --timeout 150 \
  --extra-config /tmp/htc-node-local-recheck/node-local.conf
python3 scripts/scenario/validate_meso_reports.py \
  --simulation /tmp/htc-node-local-recheck/meso/simulation.json \
  --reports /tmp/htc-node-local-recheck/meso-node-local/reports \
  --log /tmp/htc-node-local-recheck/meso-node-local/runtime.log \
  --output /tmp/htc-node-local-recheck/meso-outcome.json
```

Use `--mode MICRO` for the MICRO validator. Repeat with `scheduling-strategy` set
to `cluster-pool`, fresh output/ports, and compare outcome counts, distances and
completion ticks. Preserve raw failures independently from scientific model claims;
these runs do not modify any frozen scientific protocol or acceptance threshold.

## Release 3.10.0 default regression

The release candidate changes `scheduling-strategy` to `auto`: node-local for
direct conservative execution without live load balancing, and the previous pool
for Time Warp, channel-closed and live load balancing. Explicit selection remains
available. `node-local.nodes=0` discovers and freezes the Up startup cohort.

`TimeManagerSchedulingStrategySpec` and the default production-factory test cover
selection, compatibility and catalog defaults. The complete sequential ScalaTest
run passed **1,084 tests in 212 suites**, with zero failures or aborted suites.
Assembly passed. All **12 Python benchmark/extractor tests** passed. These release
checks supersede the earlier targeted-only coverage statement for the older
comparison executable. Existing negative-path tests intentionally log runtime
failures; the suite result is the success criterion for those injected failures.

Release assembly SHA-256:
`0657eaa7d864096c4694189dc7173fb6da7a4392da7d79cd1fb7fc11e0d28cf4`.
Full test log: `/tmp/htc-release-3.10.0-tests.log`; Python log:
`/tmp/htc-release-python.log`.

Two-JVM release smoke runs used no scheduling-strategy, node-count or worker-count
override: `auto`, discovered two Up nodes and four partitions per node. MESO and
MICRO each completed 16 trips and eight daily summaries with zero runtime errors
or stalls; road entries/exits were 30/30 and 32/32 respectively. Every trip tick and
distance matched the original cluster-pool reference. Fresh report/input validators
passed. Artifacts: `/tmp/htc-release-3.10.0-default-meso/` and
`/tmp/htc-release-3.10.0-default-micro/`, including validated `outcome.json` files.
