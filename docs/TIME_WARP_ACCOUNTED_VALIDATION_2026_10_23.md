# Accounted runtime validation — 2026-10-23

Related design: [implemented protocol](TIME_WARP_ACCOUNTED_RUNTIME.md) and
[target architecture](TIME_WARP_EXTENSIBLE_RUNTIME.md). Each case runs one fixed
policy; separate processes are used for conservative and optimistic cases.

## Latest verified results — MESO follow-up

The MESO departure correction now waits for distance accounting before completing
a trip or entering its next link. Car SUMO route lengths use the checkpointed
trip-start odometer, including on repeat trips. The latest full build passes
**483 tests across 100 suites**.

| Check | Conservative, one link | Conservative, two links | Optimistic, one link | Optimistic, two links |
|---|---:|---:|---:|---:|
| Person trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 4 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 |
| MESO entries / exits | 4 / 4 | 8 / 8 | 4 / 4 | 8 / 8 |
| Person/SUMO distance per trip (m) | 200 | 400 | 200 | 400 |
| Completion ticks, both persons | 30 / 220 | 50 / 240 | 30 / 220 | 50 / 240 |
| Errors / replay mismatches | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Null-state discards | 0 | 0 | 0 | 0 |
| Rollbacks / stalls | 0 / 0 | 0 / 0 | 29 / 0 | 62 / 0 |
| Distance, traversal, timing, runtime acceptance | Pass | Pass | Pass | Pass |

The exact final artifact also passes two-link MICRO regressions under both
policies: four trips, two summaries, eight matching entries/exits, correct SUMO
trip distances, and zero errors or null-state discards. These are bounded
person-owned Car scenarios on static unsignaled routes. MESO trip ticks and
distances match, but some same-tick per-car density/speed estimates still differ
because conservative channel ordering remains open. No full report-parity or
other-vehicle claim is made.

See [the MESO validation record](MESO_POLICY_VALIDATION_2026_10_23.md) for reproduced
failures, protocol correction, strict timing expectations, artifact hashes, all
six final simulator cases, raw comparison evidence, and reproduction commands.

## Retirement correction — preceding MICRO validation

The rebuilt retirement-correction artifact passes all four bounded MICRO cases.
The actor now retains its state and speculative history until GVT is strictly
beyond both its terminal event and latest processed event, with no pending work;
ordinary physical retirement also requires coordinator authorization for the
same committed round. The older Time Manager adapter now uses the same strict
GVT boundary.

| Check | Conservative, one link | Conservative, two links | Optimistic, one link | Optimistic, two links |
|---|---:|---:|---:|---:|
| Person trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 4 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 2 / 2 |
| MICRO entries / exits | 4 / 4 | 8 / 8 | 4 / 4 | 8 / 8 |
| Exception lines / replay mismatches | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Null-state interaction discards | 0 | 0 | 0 | 0 |
| Rollbacks / stalls | 0 / 0 | 0 / 0 | 51 / 0 | 227 / 0 |
| Engine stop before launcher teardown | Yes | Yes | Yes | Yes |
| Strict acceptance | Pass | Pass | Pass | Pass |

`sbt test assembly` passed **476 tests across 99 suites**. The executable is
`/tmp/htc-retirement-checked-20261023.jar`, SHA-256
`27ffe6efde91cd8ef14466791e4597797dbc59f21cf51f44fbf114dafbb82d0a`.
See [retirement verification evidence](#retirement-boundary-verification-follow-up)
for the reproduced boundary failures, corrections, inputs, commands, and output
locations. These results validate the retirement correction in the tested MICRO
scope; conservative channel ordering and full event-tick parity remain open.
The later MESO validation is recorded above and in its dedicated document.

## Defects reproduced and corrected

### Historical failed acceptance matrix — before the corrections

The earlier `batch-*` run set has the following observed outcomes:

| Check | Conservative, one link | Conservative, two links | Optimistic, one link | Optimistic, two links |
|---|---:|---:|---:|---:|
| Person trips / expected | 4 / 4 | 4 / 4 | 4 / 4 | 3 / 4 |
| Daily summaries / expected | 2 / 2 | 2 / 2 | 2 / 2 | 1 / 2 |
| MICRO entries / exits | 4 / 4 | 8 / 8 | 4 / 4 | 8 / 8 |
| Null-state interaction discards | 0 | 0 | 21 | 0 |
| Rollbacks / stalls | 0 / 0 | 0 / 0 | 39 / 0 | 195 / 0 |
| Strict acceptance | Pass | Pass | Fail | Fail |

These are retained in `/tmp/htc-person-replay-20261022/batch-*`. The optimistic
one-link run's trip totals do not override its 21 discarded anti-messages; the
two-link run's engine stop does not override its missing trip and daily summary.
Both correctly fail strict acceptance. They predate the final queued-dispatch
correction and are not results for the final archived jar below. Later
`accounted-queue-*` cases were rerun against that jar and passed the strict outcome,
runtime, and completion checks. Keeping both sets makes the correction auditable;
the earlier failures remain regression evidence, not successful validation.

The earlier event-ordering baseline still stopped optimistic LPs before late
cancellations and lost daily completion reports. Those observations are preserved
in [the preceding record](TIME_WARP_VALIDATION_2026_10_23.md); a stop marker alone
was not accepted as proof.

The first accounted build passed the one-link case (four trips, two summaries,
four matching MICRO entries/exits, 105 rollbacks, zero null-state discards), but its
two-link case failed explicitly:

```text
RouteDispatch: requirement failed: work at 0 below committed frontier 10
```

`resyncScheduleAfterRollback` invented a tick-0 root wake when a restored passive LP
had no pending self-event. The new coordinator detected this old repair bug instead
of reporting success. The accounted path now restores exactly the checkpointed
pending wake; it also preserves an explicit wake when reply obligations coexist.
The new retirement regression failed on the fabricated wake before recompilation
and passes with the correction. Evidence: `/tmp/htc-accounted-typed.log`,
`/tmp/htc-person-replay-20261022/accounted-first-multi/`.

Review also found that the LTM's Long-keyed maps could merge equal numeric IDs from
different model classes. Private scheduler handles now isolate those indexes while
logical/transport identities retain the original model identity. The integrated
coordinator/real-LTM regression executes two different model namespaces with ID 1
on the same owner, retires one schedule, and independently dispatches the other.
It does not represent a completed second model implementation.

A repeated two-link run of the next build then remained blocked at tick 200 after
only two trips. The ledger kept it active; no successful completion was reported.
The old `isKnownSupersededReply` optimization could drop a late input before logging
it, based on request identity in the speculative suffix. That identity itself can
be undone. A controlled accounted-LP regression reproduced the lost input (count 1
instead of 2), then passed after disabling this shortcut in the accounted path.
Replies are now evaluated against their restored ordered prefix. Evidence:
`/tmp/htc-accounted-stale-before.log`, `/tmp/htc-accounted-stale-after.log`, and
`/tmp/htc-person-replay-20261022/accounted-repeat-multi/`.

After the correction, two traced and two untraced two-link runs each passed all
outcome and accounting checks. Their labels are `accounted-replies-multi-a`,
`accounted-replies-multi-b`, `accounted-replies-untraced-a`, and
`accounted-replies-untraced-b`; jar `/tmp/htc-accounted-replies-20261023.jar`, assembly
hash `68d77c28f0e2b317aeb185992e71a38161ffd044`. The evidence supports the corrected
input handling and these runs; it is not a general liveness proof for every model.

A later default-cadence two-link repetition (`accounted-release-multi`) still
blocked at tick 200, so the superseded-reply correction was not accepted as the
complete liveness fix. With 50 ms snapshot cadence, `accounted-diagnostic-fast-b`
reproduced the underlying dispatch wait cycle. Its stalled Link 11 snapshot showed:

```text
pendingSelf = tick 36
queued spontaneous dispatch = tick 38, generation 5, revision 5
```

Rollback had invalidated a dispatch **after** it was accepted into the frozen LP's
queue. The queue waited for its restored earlier self-event before dequeuing that
obsolete dispatch, but the obsolete dispatch still held the LTM slot needed to send
the replacement. Earlier acceptance-time stale checks did not cover this case.
A separate queued-dispatch index now finds and acknowledges obsolete dispatches
before applying the known-self ordering gate, including behind an earlier positive
input. Discarded heap entries are removed lazily without executing the model. A controlled freeze/queued-dispatch/anti/restore regression failed
waiting for that exact acknowledgement before the fix:
`/tmp/htc-accounted-queued-before.log`. No timeout, forced model wake, or speculative
success was added. The diagnostic state logs are observations only.

## Automated checks

The suite covers delayed positive and anti acceptance, stale round reports,
declared creation barriers, blocked obligations, strict GVT equality, rollback of
terminal completion, exact restored schedules, typed transport identities, schedule
ID collisions, horizon truncation without executing future self-events, safe
pause/resume, terminal failure, retention of apparently superseded stragglers,
receipt/flush/retirement barriers, and storage flush
failure. Completion tests distinguish a registered asynchronous reply wait from a
missing spontaneous completion; neither path invents a watchdog wake. The actual Kryo runtime binding and both protobuf interaction paths are
round-tripped with event-order and run/incarnation metadata.

The capability hook is checked before LP registration/initialization ACK. The
hybrid adapter rejects speculative shared routing and live transit birth; these
features are not silently treated as supported.

Focused checks: `/tmp/htc-accounted-identity-tests.log` (16 passed before the last
horizon/failure cases). An intermediate full suite passed **467 tests in 98 suites**;
assembly succeeded. Log: `/tmp/htc-accounted-release-467.log`.

That intermediate artifact is `/tmp/htc-accounted-release-20261023.jar`, assembly hash
`7fb52cdce68da5af01053c5a75c7aba1d0e23f12`, SHA-256
`5cacc968d61c5da37e46f8500ef4ce12ab0a964b9f27f4d92d19184747ce4932`.
That artifact precedes the queued-dispatch correction; final evidence is recorded below. `git diff --check`, new-file whitespace,
and local documentation links were checked.

## Bounded real simulator cases

Inputs, runner, and outcome verifier are preserved under
`/tmp/htc-person-replay-20261022/` and `/tmp/htc-time-warp-review-20261022/`.
The exact seed-42 generation command and deterministic input adaptations are in
[the original record](TIME_WARP_VALIDATION_2026_10_22.md#reproducible-bounded-model-scenario).
The same inputs are deliberately reused to reproduce the earlier multi-actor
failure. They contain two persons, two owned cars, eight MICRO links, no transit,
activities at ticks 10/200/800, static routing, and horizon 1000.

The verifier derives expected journeys from each person's actual plan and shortest
link distances. Each one-link trip is 200 m (daily total 400 m/person); each two-link
trip is 400 m (daily total 800 m/person). It checks per-person completion counters,
activity/destination sequence, one daily summary per person, distances and completion
reasons, matched vehicle/link MICRO entry/exit identities, and positive traversal
time. Every case expects four trips and two daily summaries. The two route variants
expect four and eight MICRO traversals respectively.

Optimistic acceptance additionally requires fresh increasing round IDs, monotone
frontiers, `[ACCOUNTED-COMPLETE] ... status=completed`, and the reporter shutdown
marker after accounted completion. Replay errors, runtime failures, null-state
interaction drops, and stall warnings all fail the check. JSON is the actual sink
used; the completion marker follows its acknowledged final flush.

Intermediate pre-queue-fix commands (preserved failure evidence; use fresh labels
because report files append). Final acceptance commands/results follow below:

```bash
sbt test assembly
python3 /tmp/htc-person-replay-20261022/run.py accounted-release-single \
  /tmp/htc-accounted-release-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-single.json 57
python3 /tmp/htc-person-replay-20261022/run.py accounted-release-multi \
  /tmp/htc-accounted-release-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-multi.json 58
python3 /tmp/htc-person-replay-20261022/run.py accounted-release-conservative-single \
  /tmp/htc-accounted-release-20261023.jar \
  /tmp/htc-person-replay-20261022/discrete-event-single.json 59
python3 /tmp/htc-person-replay-20261022/run.py accounted-release-conservative-multi \
  /tmp/htc-accounted-release-20261023.jar \
  /tmp/htc-person-replay-20261022/discrete-event-multi.json 60
python3 /tmp/htc-person-replay-20261022/analyze.py \
  accounted-release-single accounted-release-multi \
  accounted-release-conservative-single accounted-release-conservative-multi
```

The harness bounds each process at 75 seconds. After engine reporter shutdown it
waits three seconds and tears down the remaining cluster/JVM; a negative teardown
return code is not a claimed natural JVM exit. These are single-node cluster runs
with real sharded model actors and two LTMs, not a two-node transport test. They
make no performance or speedup claim.

## Remaining acceptance gates

Live Kafka/ClickHouse services were not exercised. Async sink receipt/drain and
failure contracts have actor tests; JSON file output is exercised by the simulator.
A multi-node delivery campaign, durable recovery/idempotent effect retry, bounded
histories, live input/birth adapters, timestamped dynamic routing, and a second
unrelated model remain follow-up work. Conservative exact same-tick closure and
acknowledged durable termination remain on their separate migration path.

The tested terminal outcomes establish this bounded static-routing case. They do
not prove equality of every metric/timestamp between the two policies or correctness
of all transit, signal, lane-change, and model configurations.

## Separate-run comparison limits

The intermediate `bb5284c0a28161ca767a628c1a01ee92b49d92a6` conservative/optimistic pair had 98 JSONL rows in each
one-link run and 152 in each two-link run. Daily summaries matched exactly. In
the two-link pair, trip-completion, journey-completion, and MICRO exit payloads
matched exactly. In the one-link pair, the first-trip arrival order of the two
vehicles was swapped: person 14 completed at tick 32 conservatively and tick 34
optimistically; person 16 showed the reverse. The repeated optimistic one-link
run retained its optimistic arrival order. This is consistent with the unresolved
conservative independent same-tick closure requirement, but that cause has not been
proved by an injected-channel reference test here.

`sumo_summary_step.duration` measures real CPU time and differs across runs; it is
not a simulated-state parity metric. Later optimistic repetitions also require
comparison of all physical metrics against a sequential reference. Do not infer
complete deterministic or physical parity from trip counts or matching row counts.

## Two-link MICRO tie-order investigation (2026-10-23)

To distinguish reporter ordering from changed vehicle state, the same conservative
jar (`/tmp/htc-accounted-queue-20261023.jar`) and seed-42 scenario were run twice
with DEBUG actor logging enabled. Both runs completed with 152 records and the
expected outcomes. Link actor logs show different actual receiver order for the
two same-tick arrivals at link 11:

| Run | Link 11 `EnterLink` receive order | MICRO exit order |
|---|---|---|
| `conservative-debug-logs` | car 15, then car 13 | car 15 at tick 56 (21 ticks), car 13 at tick 58 (23 ticks) |
| `conservative-debug-logs-b` | car 13, then car 15 | car 13 at tick 56 (21 ticks), car 15 at tick 58 (23 ticks) |

Both cars enter link 11 at simulation tick 35 in each run. The log lines are emitted
inside the Link actor's `handleEnterLink` before its synchronous MICRO registration,
so they establish the actual order seen by the model, rather than report-sink order.
The lead vehicle reverses with that receive order. `LinkVehicleFlowHandler` inserts
vehicles at position zero by appending equal-position arrivals; the MICRO strategy
then uses lane queue order to choose the leader. This demonstrates a model-level
same-tick tie whose outcome depends on cross-actor message arrival. It is not merely
a report serialization difference, and the evidence does not identify CPU-resource
contention as the cause.

The conservative/optimistic first-link lead swap is consistent with this same
arrival-order sensitivity. The earlier conservative-channel-closure hypothesis is
not established as the cause of this scenario's differing vehicle ticks. The source
level conservative channel-closure gap remains independent and still needs an
injected delayed-channel regression.

## Stable MICRO lane tie-break validation (2026-10-23)

`VehicleInLane` now orders lane members by descending position and then ascending
vehicle ID. Link entry, every active MICRO sub-tick, lane changes, and the legacy
micro manager share the same ordering. Focused coverage verifies reversed arrival
insertion, leader selection, post-step ordering, and Link handler integration.

The full suite passes **472 tests in 99 suites**, and assembly succeeds. Final
artifact SHA-256: `aeefef777337f1c40a8122fdc49902c07e688bdf72b64ad5b8f226ad1a8ca1c3`.
Six seeded MICRO simulator runs pass strict trip, schedule, MICRO traversal,
exception, replay, null-state-discard, stall, and engine-stop checks. The one-link
cases and first two-link repetitions ran before a final consistency change in the
legacy micro manager; four two-link repetitions were rerun against the exact final
artifact:

| Run | Mode / scenario | Trips / schedules | MICRO entries / exits | Rollbacks | Strict result |
|---|---|---:|---:|---:|---|
| `lane-fix-final-cons-multi-a` | Conservative, two links | 4 / 2 | 8 / 8 | 0 | Pass |
| `lane-fix-final-cons-multi-b` | Conservative, two links repeat | 4 / 2 | 8 / 8 | 0 | Pass |
| `lane-fix-final-opt-multi-a` | Optimistic, two links | 4 / 2 | 8 / 8 | 83 | Pass |
| `lane-fix-final-opt-multi-b` | Optimistic, two links repeat | 4 / 2 | 8 / 8 | 202 | Pass |

The earlier one-link runs also passed on the same active Link strategy code: both
modes produced four trips, two schedules, four MICRO entries/exits, and zero
null-state discards.

The same-tick equal-position leader now follows vehicle ID even when messages
arrive in the opposite order. This removes that cause of the link-level lead swap.
In `lane-fix-final-cons-debug`, the Link actor received car 15 before car 13 at
link 11, but car 13 exited first (tick 56 versus tick 58), as the stable ID order
requires. This is a simulator-level check that the leader no longer follows
message arrival order.

The tie fix does not establish full conservative event-tick determinism: in the
two conservative repetitions, car 13 entered link 11 at tick 33 in one run and
tick 35 in the other. The optimistic two-link pair repeated the same reported
event ticks. The conservative handoff timing difference needs a separate causal
trace. The source-level conservative channel-closure gap also remains independent.

## Historical preliminary MESO policy check — before departure correction

This failure evidence is superseded for the bounded Car cases by the
[MESO correction and validation](MESO_POLICY_VALIDATION_2026_10_23.md).

The same generated scenario was rerun after changing all link states to MESO in
temporary inputs under both policies (before the legacy-manager-only final
consistency change). All four runs stopped and emitted four trip
completions, two daily summaries, zero exception lines, zero null-state discards,
and zero stalls. This is not a MESO parity pass: in the one-link cases, reported
trip distance is 0 m; in the two-link cases, each reported trip distance is 200 m
although its route has two 200 m links. The optimistic two-link first trip also
completed at tick 50 versus tick 32 conservatively. MESO distance accounting and
the multi-link policy timing therefore need their own fixes and strict acceptance
scenario before MESO can be called validated for both policies. Temporary run
artifacts are under `/tmp/htc-person-replay-20261022/meso/` and the
`lane-fix-meso-*` directories.

## Final queued-dispatch correction

The final full build passed **469 tests in 98 suites** and assembly succeeded:
`/tmp/htc-accounted-final-469.log`. Both queued-dispatch variants pass, including a
future positive input ahead of the stale dispatch. The runtime indexes queued
dispatches independently; it does not scan unrelated model inputs to find them.
Only obsolete transport entries are removed; the known-earlier-self ordering gate
continues to apply to model execution.

Artifact: `/tmp/htc-accounted-queue-20261023.jar`; assembly hash
`6a5febc8b37fad6d7340ab60b0c69c91f8d616a5`; SHA-256
`96a3d8e914709a84316e914a94594552da67cd1d74b96028396317270e8b7601`.

Commands for the final normal-cadence optimistic cases:

```bash
python3 /tmp/htc-person-replay-20261022/run.py accounted-queue-single \
  /tmp/htc-accounted-queue-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-single.json 65
python3 /tmp/htc-person-replay-20261022/run.py accounted-queue-multi \
  /tmp/htc-accounted-queue-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-multi.json 66
```

The faster-cadence case sets `HTC_TIME_WARP_TERMINATION_HEARTBEAT_MS=50`. The
multiple-sink case also sets `JAVA_TOOL_OPTIONS` to
`-Dhtc.report-manager.json.number-of-instances=2 -Dhtc.report-manager.json.number-of-instances-per-node=2`.
These settings change collection cadence and sink routing; they do not relax the
cut, ordering, or expected-outcome checks. Detailed results follow below.

| Final case | Trips / schedules | MICRO entries / exits | Rollbacks | JSONL rows | Acceptance |
|---|---|---|---|---|---|
| Optimistic one link | 4 / 2 | 4 / 4 | 36 | 98 | Pass |
| Optimistic two links | 4 / 2 | 8 / 8 | 46 | 152 | Pass |
| Optimistic two links, repeat | 4 / 2 | 8 / 8 | 102 | 152 | Pass |
| Optimistic two links, 50 ms / two sinks | 4 / 2 | 8 / 8 | 27 | 152 | Pass |
| Conservative one link | 4 / 2 | 4 / 4 | 0 | 98 | Pass |
| Conservative two links | 4 / 2 | 8 / 8 | 0 | 152 | Pass |

Every final case has zero exception lines, unused-read errors, runtime failures,
null-state interaction discards, and stall warnings. The four optimistic cases have
monotone fresh-round frontiers and acknowledged accounted completion before reporter
shutdown. The two-sink case produced two distinct nonempty files of 76 records each;
the expected 152 committed records are present across them.

Additional final commands:

```bash
HTC_TIME_WARP_TERMINATION_HEARTBEAT_MS=50 \
JAVA_TOOL_OPTIONS='-Dhtc.report-manager.json.number-of-instances=2 -Dhtc.report-manager.json.number-of-instances-per-node=2' \
python3 /tmp/htc-person-replay-20261022/run.py accounted-queue-fast-dual \
  /tmp/htc-accounted-queue-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-multi.json 67
python3 /tmp/htc-person-replay-20261022/run.py accounted-queue-multi-repeat \
  /tmp/htc-accounted-queue-20261023.jar \
  /tmp/htc-person-replay-20261022/time-warp-multi.json 68
python3 /tmp/htc-person-replay-20261022/run.py accounted-queue-conservative-single \
  /tmp/htc-accounted-queue-20261023.jar \
  /tmp/htc-person-replay-20261022/discrete-event-single.json 69
python3 /tmp/htc-person-replay-20261022/run.py accounted-queue-conservative-multi \
  /tmp/htc-accounted-queue-20261023.jar \
  /tmp/htc-person-replay-20261022/discrete-event-multi.json 70
python3 /tmp/htc-person-replay-20261022/analyze.py \
  accounted-queue-single accounted-queue-multi accounted-queue-multi-repeat \
  accounted-queue-fast-dual accounted-queue-conservative-single \
  accounted-queue-conservative-multi
```

Final payload comparison is preserved in
`/tmp/htc-person-replay-20261022/accounted-queue-comparison.json`. It excludes the
wall-clock `real_time` envelope and CPU `sumo_summary_step.duration`, while retaining
simulation ticks and physical data. In the final one-link pair, all compared event
types except summary steps match. In the final two-link pair, the conservative
vehicle order is swapped relative to the optimistic order. All three final
optimistic two-link runs retain the same per-person completion ticks:
person 14 at 56/246 and person 16 at 54/244; conservative completion is 54/244 and
56/246 respectively. Daily summaries match. Full cross-policy metric parity is
therefore **still open**, despite independently correct trip counts, destinations,
distances, activity sequences, and committed completion in these bounded cases.

## Retirement boundary verification follow-up

Checking the round-scoped retirement change exposed two additional boundary bugs:

- A terminal decision at tick 10 followed by an interaction at tick 20 was marked
  eligible at GVT 20. The new regression failed before the correction with
  `expected RuntimeCommitted(1,false), found RuntimeCommitted(1,true)`. Ordinary
  retirement now requires both the terminal event and the latest processed event
  to be strictly below GVT, with no pending work. Later speculative state and
  reports survive until that frontier passes them.
- The older Time Manager adapter used `finish.end <= GVT` in its immediate and
  deferred destruction paths. Both boundary tests failed before the correction.
  Both paths now require `finish.end < GVT`, including when GVT is already cached
  before the finish arrives.

The LP also checks the coordinator and committed round before accepting ordinary
retirement. Final-cut retirement is explicitly marked for settled completion or
horizon truncation. A null-state accounted delivery fails before acknowledgement.
The rollback test still proves terminal intent can be cancelled at GVT and the
actor can subsequently commit corrected output and retire.

`sbt test assembly` passed **476 tests across 99 suites**, with no failures.
Build log: `/tmp/htc-retirement-check-20261023-final-build.log`. Failing regression
evidence is preserved in `/tmp/htc-retirement-suffix-regression-before.log` and
`/tmp/htc-retirement-gvt-boundary-before.log`.

The rebuilt artifact is `/tmp/htc-retirement-checked-20261023.jar`, assembly hash
`c2bfa0f71efd4eadabb865c2812aff316c200428`, SHA-256
`27ffe6efde91cd8ef14466791e4597797dbc59f21cf51f44fbf114dafbb82d0a`.
The same generated seed-42 inputs described above were reused: two persons,
two owned cars, no transit, horizon 1000, and one fixed policy per run.
Expected distances remain 200 m per one-link trip and 400 m per two-link trip.

| Run suffix | Policy / route | Trips / expected | Daily summaries / expected | MICRO entries / exits | Rollbacks | Strict result |
|---|---|---|---|---|---|---|
| conservative-single | Conservative / one link | 4 / 4 | 2 / 2 | 4 / 4 | 0 | Pass |
| optimistic-single | Optimistic / one link | 4 / 4 | 2 / 2 | 4 / 4 | 51 | Pass |
| conservative-multi | Conservative / two links | 4 / 4 | 2 / 2 | 8 / 8 | 0 | Pass |
| optimistic-multi | Optimistic / two links | 4 / 4 | 2 / 2 | 8 / 8 | 227 | Pass |

Each run directory is
`/tmp/htc-person-replay-20261022/accounted-retirement-checked-<suffix>/` and
contains `run.conf`, `run.log`, JSONL reports, `analysis.json`, and per-person
outcome verification. All four cases have zero errors, replay mismatches,
null-state interaction discards, and stalls. Both optimistic runs show monotone
accounted frontiers and acknowledged completion before reporter shutdown.
The launcher observed engine completion before its bounded JVM teardown; these
results do not assert that the cluster JVM exits by itself.

Reproduction uses the preserved runner and verifier:

```bash
python3 /tmp/htc-person-replay-20261022/run.py accounted-retirement-checked-conservative-single /tmp/htc-retirement-checked-20261023.jar /tmp/htc-person-replay-20261022/discrete-event-single.json 111
python3 /tmp/htc-person-replay-20261022/run.py accounted-retirement-checked-optimistic-single /tmp/htc-retirement-checked-20261023.jar /tmp/htc-person-replay-20261022/time-warp-single.json 112
python3 /tmp/htc-person-replay-20261022/run.py accounted-retirement-checked-conservative-multi /tmp/htc-retirement-checked-20261023.jar /tmp/htc-person-replay-20261022/discrete-event-multi.json 113
python3 /tmp/htc-person-replay-20261022/run.py accounted-retirement-checked-optimistic-multi /tmp/htc-retirement-checked-20261023.jar /tmp/htc-person-replay-20261022/time-warp-multi.json 114
python3 /tmp/htc-person-replay-20261022/analyze.py accounted-retirement-checked-conservative-single accounted-retirement-checked-optimistic-single accounted-retirement-checked-conservative-multi accounted-retirement-checked-optimistic-multi
```

Use fresh labels when reproducing because report files append. Input hashes,
commands, artifact identity, and results are recorded in
`/tmp/htc-retirement-check-20261023-manifest.json`. These bounded MICRO checks do
not close the conservative channel-ordering gap or establish MESO/tick parity.
