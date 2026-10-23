# Event ordering and revisioned schedules — 2026-10-23

Related design: [Extensible Time Warp Runtime](TIME_WARP_EXTENSIBLE_RUNTIME.md).
Every simulation selects one policy before startup and keeps it for all actors.

This records the earlier event-ordering milestone. Its GVT/retirement failures are
followed by [the accounted-runtime implementation and validation](TIME_WARP_ACCOUNTED_VALIDATION_2026_10_23.md).

## Scope and invariants

The core adapter now implements:

- `EventOrder(tick, microstep, key)`, with a causal delta for same-tick outputs.
  Initial keys use model class and entity ID, independent of shard placement.
  Child keys use the parent key and output ordinal; sibling order preserves that
  ordinal. Transport resend counters do not influence logical ordering.
- One ordered actor input queue for interactions, spontaneous events, and surviving
  inputs requeued after rollback. A known earlier self-event prevents execution of
  a received interaction from its future. Optimistic stragglers compare full order.
- Checkpointed logical position and pending self-event. Coast-forward reconstructs
  scheduling decisions and effect ordinals without repeating transport effects.
  Live transport generations and schedule revisions never rewind with model state.
- Atomic schedule replacement/cancellation with monotone revisions, replacing the
  rollback clear/reschedule pair and its privileged `Long.MaxValue` generation.
  Versioned scheduling preserves the requested physical tick. An obsolete dispatch
  is acknowledged by exact generation without executing the model; an unseen
  in-flight dispatch retains its TM slot until that acknowledgement arrives.
- Queue and active-dispatch minima in the optimistic LTM, including an active
  replacement behind its old high-water mark. This is not full channel accounting.
- A late-joining conservative LTM treats its first global barrier permission as
  startup, so a versioned completion can report its next schedule normally.

`ActorInteraction` protobuf fields and `ScheduleEvent` gained additive metadata;
both interaction serializer paths and CBOR dispatch/control messages are tested.
All workers in a run must use the same binary. The unstamped legacy adapter remains
available and retains its old watermark behavior; it has no full-order guarantee.

The current key encoding uses SHA-256 and a fixed-width ordinal, costing allocation,
CPU, and wire bytes per effect. Queue work is logarithmic in the queued input count; schedule replacement uses an
actor-to-schedule index instead of scanning unrelated tick buckets;
histories and cancellation indexes are still unbounded. There is no scale or speedup
claim. A compact key representation and budgets require separate measurement.

## Regression evidence

The new same-tick regression was run before runtime integration. It failed with
`I@10:202, I@10:201` instead of the defined order `I@10:201, I@10:202`.
Log: `/tmp/htc-event-order-before.log`.

The controlled `PersonCarLinkOrderingSpec` uses minimal protocol adapters over the
real `SimulationBaseActor`, history, send, cancellation, and replay machinery.
Noncommuting Link updates (`+3`, then `*2` from an initial value of 1) must produce
8 at Person after travelling through Car. Tests inject reversed same-tick delivery
and both anti-before-replacement and replacement-before-anti delivery. The retained
state must contain one completion, with stable logical identity and fresh transport
identity after re-execution. This is a core protocol regression; real mobility
behavior is checked separately below.

Additional tests cover causal overflow/past-time rejection, mixed input ordering,
known-self-event gating, immediate stale-dispatch acknowledgement, atomic schedule
replacement, duplicate/older revisions, retained in-flight slots, actual LVT after
rewind, and serializer round-trips. Existing replay fixtures normalize keys belonging
to distinct fixture LP IDs while preserving their logical tick/microstep; the new
protocol tests check stable keys directly on the same LP history.

The first full build passed **445 tests in 90 suites**, and assembly succeeded.
`/tmp/htc-event-order-full.log`; jar hash
`dd78366e0ec77f4f017abef8dc6f4bf7d36114a5`.
A subsequent focused run against that final key encoding passed **55 tests**:
`/tmp/htc-event-order-final-focused.log`.
The archived jar is `/tmp/htc-event-order-20261023.jar`.

The final review also adds a 501-actor conservative-batch regression. A pending
actor's old batch entry is replaced before dispatch; once the first 500 actors
finish, the obsolete entry must be skipped and the replacement dispatched at its
requested tick. An actor-to-schedule index limits replacement cleanup to that LP's
own schedules. Test harnesses wait for persistence recovery before mixing mailbox
traffic with direct test calls; a pre-readiness attempt timed out and is not treated
as successful validation.

## Bounded real mobility runs

Inputs and harness are preserved under `/tmp/htc-person-replay-20261022/` and
`/tmp/htc-time-warp-review-20261022/`. The generation command and exact adaptations
are documented in [the preceding record](TIME_WARP_VALIDATION_2026_10_22.md#reproducible-bounded-model-scenario).
The generator's `--help` was inspected again before this follow-up; the same
seed-42 generated inputs were reused to preserve the previously reproduced failure.

There are two persons and two owned cars, eight MICRO links, no transit, three
activities at ticks 10/200/800, and a horizon of 1000. Dynamic routing feedback is
disabled. The one-link plan is 1 -> 2 -> 1 (200 m per trip); the two-link plan is
1 -> 4 -> 1 (400 m per trip). Every case expects four person trips, two unique daily
summaries, and balanced MICRO traversals (four or eight respectively).

Commands for the archived first build, from repository root:

```bash
python3 /tmp/htc-person-replay-20261022/run.py order-conservative-single \
  /tmp/htc-event-order-20261023.jar \
  /tmp/htc-person-replay-20261022/discrete-event-single.json 30
python3 /tmp/htc-person-replay-20261022/run.py order-optimistic-single \
  /tmp/htc-event-order-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-single.json 31
python3 /tmp/htc-person-replay-20261022/run.py order-conservative-multi \
  /tmp/htc-event-order-20261023.jar \
  /tmp/htc-person-replay-20261022/discrete-event-multi.json 32
python3 /tmp/htc-person-replay-20261022/run.py order-optimistic-multi \
  /tmp/htc-event-order-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-multi.json 33
python3 /tmp/htc-person-replay-20261022/analyze.py \
  order-conservative-single order-optimistic-single \
  order-conservative-multi order-optimistic-multi
```

Choose fresh labels for reruns because JSON output appends. Each directory contains
`run.conf`, `run.log`, `result.json`, `analysis.json`, verification, and JSONL reports.

| Observation | Conservative one link | Conservative two links | Optimistic one link | Optimistic two links |
|---|---|---|---|---|
| Completed person trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 3 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 1 / 2 |
| MICRO entries / exits | 4 / 4 | 8 / 8 | 4 / 4 | 8 / 8 |
| Vehicle journey completions | 4 | 4 | 4 | 4 |
| Exception lines / unused-read errors | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Null-state late-message discards | 0 | 0 | 2 | 19 |
| Rollbacks / stall warnings | 0 / 0 | 0 / 0 | 50 / 0 | 242 / 0 |
| Engine stop before harness teardown | Yes | Yes | Yes | Yes |
| Expected model outcomes | Pass | Pass | Pass | Fail |
| Strict runtime acceptance | Pass | Pass | Fail | Fail |

The verifier derives distances and destinations from the actual plans and topology;
it checks per-person trip counters, activity sequence, unique summary, completion
reasons, and matching positive MICRO traversals. Completed trip counts alone do not
establish acceptance or output parity.

Both optimistic runs terminate via the existing heuristic plateau mechanism. The
one-link late discards are anti-messages reaching Car 13 after its state is gone;
the two-link case discards 19 anti-messages at Car 15. The two-link missing Person
completion and these lifecycle failures remain open; this experiment does not prove
that all remaining output loss has one cause. The prior build stalled at its bound;
this build terminating does not establish safe termination.

The harness bounds each run at 75 seconds and tears the JVM down after observing
report-manager stop. Exit `-9` reflects that cleanup, not natural JVM termination.
There is no proof here of durable sink flush, distributed GVT, or city-scale behavior.

## Intermediate indexed build repeat

After the late-start fix and schedule index, the full suite passed **446 tests**;
assembly hash `66f39460bc4deb34538abff92b4744dba1195540`.
Logs: `/tmp/htc-event-order-indexed.log` and `/tmp/htc-event-order-index-test.log`.
The latter reruns the replacement case with two superseded future buckets and
passes. Archive: `/tmp/htc-event-order-indexed-20261023.jar`.

`indexed-conservative-single` passes all outcome/runtime checks. The separate
`indexed-optimistic-single` again reports four person trips and two summaries, but
has four MICRO entries against five exits, five vehicle completions, 42 null-state
discards, and 43 rollbacks. It stops without exception/stall warnings, yet fails
model outcomes and strict acceptance. Artifact directory names use the same harness
root as above. This repeat reinforces that optimistic output is not reliable; the
first one-link result must not be used as a parity claim.

## Final batch-aware build

`sbt test assembly` passes **447 tests in 90 suites**, with zero failures or
pending tests. The final assembly hash is `a0701dbcc38e848746103c43c8f96e87c8814770`.
Log: `/tmp/htc-event-order-batch.log`.
Archive: `/tmp/htc-event-order-batch-20261023.jar`.
This includes the batch revision check and late-start regression in addition to the
ordering, indexed schedule replacement, and wire metadata changes above.

Final packaged-build results (`batch-*`, seed 42):

| Observation | Conservative one link | Conservative two links | Optimistic one link | Optimistic two links |
|---|---|---|---|---|
| Person trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 3 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 1 / 2 |
| MICRO entries / exits | 4 / 4 | 8 / 8 | 4 / 4 | 8 / 8 |
| Exception lines / replay mismatches | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Null-state discards | 0 | 0 | 21 | 0 |
| Rollbacks / stalls | 0 / 0 | 0 / 0 | 39 / 0 | 195 / 0 |
| Engine stop before teardown | Yes | Yes | Yes | Yes |
| Strict acceptance | Pass | Pass | Fail | Fail |

Both conservative cases pass all derived expectations. The final optimistic
one-link case has correct outcome counts but discards 21 late anti-messages at
retired Car state. The final two-link case has no null-state discards but still
misses one Person trip and one daily summary. It terminates without exception or
stall warnings. Thus neither strict optimistic acceptance nor mode parity is
established; lifecycle failure is not a proven sole cause of the missing completion.

Reproduce this build with the same commands above, substituting
`batch-` for `order-` in each label, `/tmp/htc-event-order-batch-20261023.jar` for
the jar, and port offsets 36/37/38/39 for conservative-single/optimistic-single/
conservative-multi/optimistic-multi respectively. Fresh labels are required for a
new run. Analyze all four resulting labels using the preserved `analyze.py`.
The harness teardown and durable-flush limitations remain the same.

## Remaining acceptance gates

The core ordering/scheduling foundation is implemented, but Stage 1 remains partial:
conservative mode still needs a channel/frontier proof excluding earlier independent
same-tick arrivals and a sequential-reference comparison under delayed transport.
One received-input queue alone cannot supply that proof. The conservative ordered
protocol test and bounded mobility runs cover the tested paths only.

Next, retain logically completed LPs for rollback instead of physically retiring them
and flushing speculative reports immediately. Safe physical retirement must be tied
to a proven commit frontier, requiring accounted positive/anti/lifecycle transport
and GVT/quiescence rounds. Also pending are run/incarnation identity, complete model
snapshot coverage, timestamped dynamic feedback, explicit run failure on reconstruction
errors, and acknowledged sink drain. Restart/migration recovery is outside this patch;
those operations remain disabled in the validation configuration.
