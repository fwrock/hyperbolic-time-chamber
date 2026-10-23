# Conservative channel validation — 2026-10-23

The later [distributed optimistic validation](DISTRIBUTED_OPTIMISTIC_VALIDATION_2026_10_23.md)
repeats the separate-policy cases, fixes Link's checkpointed report counters and
replaces production optimistic central routing. The MICRO summary gap described
below records the earlier artifact; it is resolved in the later bounded checks.

This validates the [distributed conservative protocol](CONSERVATIVE_CHANNEL_RUNTIME.md).
Each process selects one policy before startup. The optimistic runs are separate
regressions; they still use the central accounted coordinator.

## Protocol evidence

`sbt test assembly` passed **487 tests across 102 suites**. The log is
`/tmp/htc-conservative-channel-guards-build.log`. The artifact is
`/tmp/htc-conservative-channel-final-20261023.jar`, assembly hash
`c6329b4ea1623d9c0e6bdc7ed5f8f771925c9f8e`, SHA-256
`6073287d81fbff6eb8b9dbd710d4c4350f5dd71ea3f3e7f6fdb4ea0b3e639aef`.

`ConservativeChannelSpec` runs real simulation actors, two real LTM adapters and
the production GTM protocol with controlled transport and sink probes. It proves:

- A fire-and-forget source can finish its callback while its transfer is held;
  the receiver cannot execute a later wake, the source cannot physically retire,
  and the simulation cannot finish before delivery acceptance.
- Two senders on different LTMs deliver noncommuting add/multiply events in reverse
  logical order. The receiver queues both and reaches the sequential-reference
  result in stable event order.
- Delayed report receipts prevent the next batch, and a separately delayed final
  sink flush prevents successful completion. The drain includes the actual sink.
- Progressive loading accepts a later window during execution, does not pass the
  ready frontier, and waits for explicit producer exhaustion. A late finite ready
  notification after exhaustion cannot reopen the source.

`ConservativeProtocolSerializationSpec` checks every conservative command variant
through the configured Kryo serializer, including ActorRefs, model payloads and
full logical event orders. This is serialization evidence in one JVM, not a
multi-node transport campaign.

## Simulator inputs and MESO results

The preserved seed-42 inputs are the same two-person, two-owned-Car scenario used
in [the MESO model validation](MESO_POLICY_VALIDATION_2026_10_23.md). Static routing,
two LTMs and one JSON sink are configured. One-link routes require four 200 m trips;
two-link routes require four 400 m trips. Both require two daily summaries.

`scripts/scenario/validate_meso_reports.py --free-flow` checks actual plans and
topology, destination/activity sequence, matched traversal, per-person and SUMO
distance, cumulative odometers, model density/speed law, and independently derived
completion ticks. Errors, null-state discards, stalls and missing engine stop fail
acceptance. Conservative logs additionally contain acknowledged
`[CONSERVATIVE-COMPLETE]` before reporter shutdown; optimistic logs contain the
accounted completion marker and monotone commitment rounds.

| Check | Conservative one link | Conservative two links | Optimistic one link | Optimistic two links |
|---|---:|---:|---:|---:|
| Trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 4 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 |
| MESO entries / exits | 4 / 4 | 8 / 8 | 4 / 4 | 8 / 8 |
| Person and SUMO trip distance (m) | 200 | 400 | 200 | 400 |
| Completion ticks, both persons | 30 / 220 | 50 / 240 | 30 / 220 | 50 / 240 |
| Errors / null-state discards / stalls | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |
| Rollbacks | 0 | 0 | 9 | 31 |
| Strict acceptance | Pass | Pass | Pass | Pass |

Physical report multisets match between policies: 52 rows for one link and 60
for two links, including per-vehicle density assignments and speed values that
differed under the old barrier. Comparison removes run identifiers and wall-clock
`real_time`; SUMO diagnostic `duration` would also be excluded if present. It does
not require file arrival order to match.
Evidence: `/tmp/htc-channel-policy-comparison-20261023.json`.

## MICRO regression and remaining report gap

Two-link MICRO cases also pass independent outcome acceptance on the same artifact:

| Check | Conservative | Optimistic |
|---|---:|---:|
| Trips / expected | 4 / 4 | 4 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 |
| MICRO entries / exits | 8 / 8 | 8 / 8 |
| Errors / replay mismatches / null-state discards / stalls | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| Rollbacks | 0 | 78 |
| Engine completion before launcher teardown | Yes | Yes |

Directories are `channel-final-cons-micro-multi` and
`accounted-channel-final-opt-micro-multi`; each has `analysis.json` and outcome
verification artifacts. All non-summary physical reports match between policies.
Full MICRO payload parity remains **open**: of 152 rows per case, 67 SUMO summary
rows differ in cumulative `loaded`; two of those also differ in cumulative `ended`.
For example, link 11 reports `loaded=2` conservatively and `loaded=5` optimistically
at tick 36, while its physical speed and running count agree.

Source inspection identifies a missing rollback adapter: `LinkMetricsReporter`
keeps cumulative and per-tick counters in mutable helper fields, while Link's
model snapshot captures scheduling/signal/grace fields only. Re-executed entries
and exits can therefore accumulate in those report counters. These counters need
checkpointed state and a regression that restores an earlier Link event before
this gap can be called fixed. The conservative channel change does not fix them.

Run directories under `/tmp/htc-person-replay-20261022/` are:

- `channel-final-cons-meso-single`
- `channel-final-cons-meso-multi`
- `accounted-channel-final-opt-meso-single`
- `accounted-channel-final-opt-meso-multi`

Each contains `run.conf`, `run.log`, `result.json`, `reports/` and `strict-meso.json`.
The bounded launcher stops the remaining cluster JVM after the engine reports
completion; engine completion and natural JVM exit are separate observations.

Reproduction uses the preserved launcher with a fresh label and port offset:

```bash
python3 /tmp/htc-person-replay-20261022/run.py fresh-label \
  /tmp/htc-conservative-channel-final-20261023.jar \
  /tmp/htc-person-replay-20261022/meso/discrete-event-multi-meso.json 181
python3 scripts/scenario/validate_meso_reports.py \
  --simulation /tmp/htc-person-replay-20261022/meso/discrete-event-multi-meso.json \
  --reports /tmp/htc-person-replay-20261022/fresh-label/reports \
  --log /tmp/htc-person-replay-20261022/fresh-label/run.log \
  --output /tmp/htc-person-replay-20261022/fresh-label/strict-meso.json --free-flow
```

These bounded cases establish neither arbitrary-model parity nor city-scale
performance. Real multi-node runs, fault recovery, Kafka/ClickHouse integration,
optimistic progressive enrollment, live model birth and bounded optimistic memory
remain separate work. In particular, distributing conservative channel ledgers
does not remove the central optimistic coordinator's potential throughput limit.
