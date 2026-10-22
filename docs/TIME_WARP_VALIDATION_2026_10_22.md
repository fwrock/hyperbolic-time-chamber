# Time Warp review validation — 2026-10-22

Related architecture and findings: [Extensible Time Warp Runtime](TIME_WARP_EXTENSIBLE_RUNTIME.md).

Every run uses one policy selected before execution and fixed for all actors.
The two mode configurations below are separate runs. The immediate goal is to
make each mode satisfy its expected outcomes independently, then compare results.

**Follow-up:** the conservative termination failure below has since been fixed for the
reproduced activation/reply path. See [Conservative termination follow-up](#conservative-termination-follow-up).
The earlier run records are retained as historical evidence; optimistic failures remain open.

## Initial review automated regression evidence

`sbt test assembly` completed successfully after the final routing observation change:
**404 tests, 85 suites, zero failures, zero pending tests**. Assembly produced
`target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar`, with sbt jar hash
`a79502c0533b23ca0a352a7c9c963d0dc72493ca`.

Raw log: `/tmp/htc-time-warp-final-tests.log`. The earlier focused run passed 59 tests;
its log is `/tmp/htc-time-warp-focused-tests.log`. A separate metadata JSON round-trip
check passed all six `LinkMigrationSnapshotSpec` tests before the final full suite.

The added/extended regression coverage verifies:

- Ordered observations, repeated keys, nested scopes, mismatch detection, and cleanup.
- Route-cache miss/hit independence during replay, suppression of nested weight reads,
  immutable captured paths, and a fresh queue for every replay.
- Buffering, cancellation, and replay suppression for targeted ClickHouse reports;
  delayed delivery to both default and Kafka mirror destinations; immediate targeted
  output in conservative mode.
- Anti-before-positive annihilation, cancellation surviving another rollback,
  duplicate positive delivery, and reprocessing other undone interactions.
- One in-flight dispatch per actor within an optimistic dispatch pass.
- Replacing an empty relationship map when restoring a checkpoint.
- Link signal/MICRO scheduling metadata across JSON serialization and restore.
- Retaining a checkpoint before the GVT boundary and saturating subtraction at
  `Long.MinValue` instead of wrapping to a large positive watermark.

The existing tests also include Movable/Car replay equivalence and request/reply
restoration. These establish useful local invariants. They do not establish a safe
distributed commit frontier, lifecycle protocol, or scenario-level parity.

## Reproducible bounded model scenario

Artifacts are under `/tmp/htc-time-warp-review-20261022/`:

| Artifact | Purpose |
|---|---|
| `scenario/` | Generated entity input and topology |
| `prepare.py` | Exact temporary input adaptations and per-mode runtime configuration |
| `expected.json` | Expectations derived from the actual person plans |
| `run.py` | Bounded Java launcher and termination observations |
| `analyze.py` | JSONL report and runtime-error summary |
| `discrete-event/`, `time-warp/` | Final configuration, logs, JSONL reports, and analysis |
| `invalid-initial-*` within each mode | Excluded initial harness attempt |
| `pre-route-journal-*` within each mode | Intermediate run that exposed cache-dependent replay |

Generation command:

```bash
python3 scripts/scenario/generate_hybrid_scenario.py \
  --output-dir /tmp/htc-time-warp-review-20261022/scenario \
  --rows 2 --cols 2 --num-persons 2 --num-cars 2 \
  --num-bicycles 0 --num-motorcycles 0 \
  --num-bus-lines 0 --num-subway-lines 0 \
  --micro-pct 100 --num-activities 3 --seed 42
```

`prepare.py` moves activity boundaries to ticks 10, 200, and 800, selects Car-only
decisions, and sets a 1,000-tick simulation horizon. It excludes empty entity sources
from the runtime configuration; the generated empty source arrays cause loader
errors and do not exercise transit. The original two-activity generation was
home-to-home and was replaced before the meaningful comparison.

The inspected input has eight MICRO links, two persons (14 and 16), two owned cars
(13 and 15), and no transit services. Each person's plan is node 1 → node 2 → node 1.
Expected behavior is **four completed car trips**, positive vehicle traversal on
MICRO links, and no transit boarding. Two registered cars alone are not evidence
that MICRO movement occurred; traversal reports and completion must be checked.

Both modes use identical model input, two local time-manager routees, JSON reporting
with batch size 1, and disabled dynamic routing feedback. Disabling feedback isolates
this test; it supplies no validation for dynamic-cost synchronization. Each mode
uses separate local cluster, management, and metrics ports.

Java is launched with `--add-opens=java.base/java.lang=ALL-UNNAMED` for the installed
JDK/Pekko virtual-thread dispatcher, an explicit `HTC_MOBILITY_CITY_MAP_FILE`, and
the matching `HTC_SIMULATION_CONFIG_FILE`. `run-result.json` records the full command
and selected environment variables. The first attempt omitted the module/map
settings and is excluded from model validation.

Run commands after building the jar:

```bash
python3 /tmp/htc-time-warp-review-20261022/prepare.py
python3 /tmp/htc-time-warp-review-20261022/run.py discrete-event
python3 /tmp/htc-time-warp-review-20261022/run.py time-warp
python3 /tmp/htc-time-warp-review-20261022/analyze.py
```

For the final run the launcher allows 45 seconds, then terminates its child JVM;
after observing ReportManager's stop message it allows three seconds for output
before teardown. A JVM teardown exit code is not an engine completion assertion.
Neither this observation nor batch size 1 is a durable sink-flush acknowledgement.

## Intermediate finding that changed the implementation

The first correctly configured run exposed an observation-boundary defect: a Person
live route search recorded weight reads, but its replay hit the process-wide route
cache and skipped them. The journal correctly detected unused observations. The
default supervisor then restarted Person with null state; later TripCompleted
messages failed repeatedly. That run is preserved as `pre-route-journal-*` and
**does not describe the final routing implementation**.

Its conservative run stopped after two `person_trip_started` reports and zero
completed trips. Its optimistic run exceeded its 90-second bound, generated 9,086
rollback warnings and 1,740 error lines, and wrote repeated vehicle completion
reports. These numbers motivated journaling the immutable route result above the
cache. They also demonstrate why restart after a replay exception needs an explicit
run-failure policy rather than allowing the simulation to continue.

## Initial review scenario result: failed acceptance

The review's rebuilt jar at that stage **did not pass** the generated scenario. The routing
adapter fixes the isolated cache-boundary issue, but does not repair every Person
state/replay branch. The exact remaining divergence needs a minimal Person
checkpoint/replay reproduction; this review does not attribute it solely to the
route cache.

| Check | Expected | Conservative final run | Optimistic final run |
|---|---|---|---|
| Completed person trips | 4 | 0 reported | 0 reported |
| Person trip starts | 4 over the full plan | 2 | 0 committed to JSON |
| Vehicle journey starts/completions | 4 / 4 | 0 / 0 reported | 4 / 5 reported; inconsistent |
| MICRO link enter/leave reports | Actual traversal with balanced terminal membership | 0 / 0 | 4 / 5; inconsistent |
| Runtime errors | 0 | 0 error lines | 45 error lines |
| Time Warp rollback warnings | No fixed expected count | 0 | 211 |
| Stall warnings | 0 | 0 | 1 |
| Engine stop observed | After all required work is settled | Yes, prematurely at tick 10 | No, bounded launcher stopped it |

Conservative logs say `No more scheduled events across local time managers` at
tick 10, immediately after trip activation. This is failure despite the stop signal
and absence of exceptions: expected trips did not complete. A grace-period query
does not account for all interaction-driven work.

Optimistic logs still show `Replay did not consume all recorded reads` in Person,
followed by supervisor restart and null-state errors on TripCompleted. The strict
journal exposes a surviving replay inconsistency rather than silently accepting it.
Restart does not preserve this LP's simulation state; a future runtime must fail
the run explicitly or perform a defined consistent recovery. The five vehicle
completion reports cannot be treated as five valid completed trips.

Final raw evidence:

- `discrete-event/run.log`, `run-result.json`, `analysis.json`, and `reports/`.
- `time-warp/run.log`, `run-result.json`, `analysis.json`, and `reports/`.

Paths are relative to `/tmp/htc-time-warp-review-20261022/`. Final JSONL totals were
4 rows for conservative and 37 for optimistic. These include only the outputs
actually observed on disk; missing reports are not assumed to prove that no
corresponding speculative transition ever ran. Report counts do establish failure
of the required committed-output/completion acceptance criterion.

## Limits and acceptance

The scenario is a correctness probe, not a performance measurement. Startup and
bounded teardown dominate wall time. A single local cluster, static routing,
Car-only trips, and no transit cannot validate multiple nodes, transit lifecycle,
dynamic feedback, failure recovery, shard migration, or another model plugin.

The release gate remains a sequential reference plus conservative/optimistic
equivalence under delayed/reordered delivery, same-tick conflicts, lifecycle
rollback, exact GVT accounting, and acknowledged sink commitment. The local fixes
and passing unit suite do not meet that gate by themselves.

## Conservative termination follow-up

The pre-review revision `200ba25` was rebuilt in an isolated worktree at
`/tmp/htc-termination-baseline-20261022/`. Against the identical seed-42 input it
reproduced two person trip starts, zero completions, and early termination. This
establishes that the failure predates the uncommitted review changes.

The core fix tracks registered reply obligations in both modes and adds
`LocalTimeReportEvent.hasPendingWork`. A conservative LTM can finish its current
tick batch while still reporting a pending interaction. The GTM waits for that work
to resolve before idle termination, while continuing to dispatch other scheduled
actors. No timeout or extra sender/receiver handshake was added.

Extending the tracking also exposed two model paths that had to agree with it:
MICRO entry replies did not consume their EnterLink registration, and final-link
cleanup registered a reply even though the vehicle completed without using it.
The MICRO reply now consumes the entry in Car/Bus/Bicycle/Motorcycle; final cleanup
does not introduce a wait. Existing conservative reply staleness checks remain in
place; accepted link, signal, and trip replies release the tracking entry in either
policy.

The two-link route exposed a further false wait: after a proactive MICRO exit, the
vehicle's cleanup notification does not receive another reply. The checkpointed
`MovableState.movableLinkExitReported` flag records that exit, skips the redundant
wait, and resets on entry to the next link. A JSON round-trip test covers the flag.

Regression coverage includes delayed activation with no scheduled work, advancing a
sibling while another actor awaits a reply, cancellation of an idle termination
probe, release of the final obligation, MICRO entry consumption, and final cleanup
without a reply obligation. `sbt test assembly` passes **414 tests in 87 suites**
and builds the jar. The log is
`/tmp/htc-conservative-termination-complete-validation.log`; the assembly hash is
`78d0529ef202b0413bff890f72dd7ba69188630c`.

| Scenario check | Pre-review baseline | Fixed, one-link trips | Fixed, two-link trips |
|---|---|---|---|
| Person trip starts | 2 | 4 | 4 |
| Completed person trips | 0 | 4, each 200 m | 4, each 400 m |
| Completion reasons | No completions | All `reached_destination` | All `reached_destination` |
| Completed daily schedules | 0 | 2, each two trips and 400 m | 2, each two trips and 800 m |
| MICRO entries/exits | 0 / 0 | 4 / 4, matching vehicle/link identities | 8 / 8, matching vehicle/link identities |
| Engine stop observed | Prematurely at tick 10 | After final activity at tick 800 | After final activity at tick 800 |
| Errors / stall warnings | 0 / 0 | 0 / 0 | 0 / 0 |

Artifacts are in `/tmp/htc-conservative-termination-20261022/`. `baseline/` contains
the clean revision's run; `fixed/` and `multi-link/` preserve intermediate results
that completed trips but retained stale waits. `final-single/` and `final-multi/`
contain the successful runs, JSONL output, and `verification.json`.
`prepare-multi.py` changes the plans from node 1 → node 2 → node 1 to
node 1 → node 4 → node 1, requiring two links per trip. `run.py` records the bounded
launch and `verify.py` derives expected routes from the actual input, then checks
trip counters, destination activities, distances, completion reasons, unique daily
summaries, positive MICRO travel time, balanced traversals, and the engine's stop
signal.

Reproduction after `sbt assembly`:

```bash
python3 /tmp/htc-conservative-termination-20261022/run.py final-single \
  "$PWD/target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar"
python3 /tmp/htc-conservative-termination-20261022/verify.py final-single
python3 /tmp/htc-conservative-termination-20261022/prepare-multi.py
python3 /tmp/htc-conservative-termination-20261022/run.py final-multi \
  "$PWD/target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar" \
  /tmp/htc-conservative-termination-20261022/multi-link/simulation.json 1
python3 /tmp/htc-conservative-termination-20261022/verify.py final-multi
```

The bounded harness still tears down the JVM after collecting the engine stop
signal; this is not a durable sink-flush or natural JVM-exit assertion. Optimistic
GVT, replay correctness, and full transport accounting remain separate open work.

## Optimistic replay follow-up

`PersonReplayInitializationSpec` drives a real `InitializeEvent` with the scenario's
default empty cursor and an immutable observed decision supplied by a test engine.
Before the fix, both initialization tests failed: rollback restored an empty cursor,
and replaying departure threw `Replay did not consume all recorded reads`. The core
had captured its floor before `onFinishInitialize` seeded the cursor. Running that
hook before registration fixes the mismatch without adding model code to core.
A third test cancels a real completed-trip input through the anti-message path,
verifies the original pending reply identity, rejects the cancelled positive, and
accepts its replacement without repeating the route observation or `StartTrip` send.

The real optimistic reruns then exposed two additional rollback-boundary defects.
Anti-message cancellation used a tick cut and could cancel a preceding same-tick
event, including the send that caused the cancelled input. Cancellation now starts
at the exact processed-event sequence, and buffered reports carry their producing
sequence so earlier same-tick output survives. History rollback removes a complete
processing suffix; a later-processed, earlier-tick event cannot remain in the log
while missing from restored state.

Earlier spontaneous dispatches also previously executed against future state. Two
new tests failed before the fix with reversed input order and an uncancelled future
send. Spontaneous stragglers now restore/cancel before execution and reprocess
surviving interactions afterward. Their normal completion resolves the incoming TM
dispatch exactly once, without a redundant rollback rescheduling exchange.

The full suite exposed a further same-tick boundary in an existing awaited-reply
regression: a delayed positive reply must preserve the request at its own tick.
Positive interaction rollback now removes strictly later processing; anti-messages
still cut at the exact cancelled event, and an earlier spontaneous dispatch restores
before its tick. These are bounded repairs to the existing arrival-order semantics,
not an implementation of the proposed stable global event-order key. Independent
same-tick inputs still require that later architecture work.

A private-vehicle checkpoint regression exposed another reply-identity gap: after
trip 2 overwrote the actor-local token and driver, restoring trip 1's JSON checkpoint
still emitted trip 2's completion token (22 instead of 11). `PrivateVehicle` now
captures/restores both fields in namespaced `modelSnapshot` entries; it does not add
vehicle fields to the core checkpoint schema. The regression verifies the emitted
`TripCompletedData.requestSeq` and restored driver attributes. Its failing baseline
is `/tmp/htc-person-replay-vehicle-before-fix.log`.

The regression logs are:

- `/tmp/htc-person-replay-before-fix.log`: both initial Person tests fail.
- `/tmp/htc-person-replay-focused.log`: initial fix passes its focused tests.
- `/tmp/htc-person-replay-cancellation-before-fix.log`: same-tick cancellation and
  non-prefix history tests fail.
- `/tmp/htc-person-replay-cancellation-focused.log`: 34 focused tests pass.
- `/tmp/htc-person-replay-spontaneous-before-fix.log`: both delayed-dispatch tests fail.
- `/tmp/htc-person-replay-spontaneous-focused.log`: 37 tests in six suites pass,
  including Car replay equivalence at checkpoint intervals 1, 2, and 50.
- `/tmp/htc-person-replay-final-tests.log`: the first full run with spontaneous
  rollback exposed the existing awaited-reply regression described above, plus a
  GTM test that sent its first message after a fixed sleep without checking recovery.
  The latter harness now waits for `recoveryFinished`; production timeouts were not added.
- `/tmp/htc-person-replay-positive-focused.log`: all 39 focused tests pass after
  the positive-reply boundary correction, including both previously failing suites.

Scenario artifacts are under `/tmp/htc-person-replay-20261022/`. The input remains the
same seed-42 two-person, two-car MICRO configuration used above. The generated
`time-warp-single.json` and `time-warp-multi.json` select optimistic execution before
startup; the corresponding `discrete-event-*.json` select conservative execution in
separate runs. `optimistic-*` preserves the initialization-only runs and
`cancellation-*` preserves the intermediate runs before spontaneous straggler handling.
`final-*` preserves the run before the positive-reply boundary correction;
`reply-*` preserves the runs before the private-vehicle checkpoint correction. That assembly produced
`5f13828292b780f8c4cdcede044b5df48f2ad71f` (sbt jar hash), retained as `reply.jar`.

The private-vehicle adapter build's `sbt test assembly` passes **428 tests in 88 suites**, with no failures or
pending tests, and produces sbt jar hash `40d2cb9a13b6684f605e1e56f12745be5be35d7a`.
Log: `/tmp/htc-person-replay-complete-validation.log`. `snapshot.jar` retains that jar;
`snapshot-*` contains its separate mode/route runs, raw reports, and verification.
The Car/Movable equivalence tests now inject their unrelated input after the retained
prefix's tick, matching the corrected positive-input boundary. Their exact snapshot
and request/reply comparisons remain unchanged.

The two-link follow-up exposed an additional deterministic-replay defect in Car:
`CarMicroHandler` uses the journey reporter's arrival speed when entering its next
MICRO link, but that value was not checkpointed. A real Car regression recorded an
entry at 0 m/s, processed a later 5 m/s update, then restored/replayed the entry.
Before the fix it incorrectly re-entered at 5 m/s. Baseline:
`/tmp/htc-person-replay-speed-before-fix.log`.

`CarJourneyState` now carries the journey inputs and completion guards within
`CarState`. `CarJourneyReporter` holds callbacks rather than its own mutable journey
record, so it sees the latest restored state. A JSON regression verifies both a
surviving completion guard and an undone completion that can report again, including
arrival speed and waiting time. Car replay equivalence now compares those fields as
well; only each fixture's distinct peer ID in lane labels is normalized.

The final journey-state build passes **430 tests in 88 suites**, with zero failures
and zero pending tests, and assembly succeeds. The full log is
`/tmp/htc-person-replay-journey-complete-validation.log`; sbt jar hash:
`ee9d654e12870ad64b6317657a9ee94c12f87607`. That jar is retained as `journey.jar`,
and `journey-*` contains its simulator runs.

`verify.py` now checks null-state interaction discards as well as exceptions and
stalls. `outcomes.json` records passing model-outcome checks independently of those
runtime checks; `analysis.json` and `verification.json` retain the combined result.
This prevents completed trip counts from masking discarded late anti-messages.

### Latest scenario acceptance: optimistic execution still fails

Each case expects four completed person trips and two completed daily schedules.
The final jar produced these results:

| Check | Conservative, one link | Conservative, two links | Optimistic, one link | Optimistic, two links |
|---|---|---|---|---|
| Completed person trips in JSONL | 4 | 4 | 0 | 0 |
| Completed daily schedules | 2 | 2 | 0 | 0 |
| MICRO entries / exits | 4 / 4 | 8 / 8 | 2 / 2 | 6 / 27 |
| Vehicle journey completions | 4 | 4 | 2 | 24; inconsistent |
| Runtime exception lines / unused-read mismatches | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Null-state interaction discards | 0 | 0 | 0 | 311 |
| Rollback warnings / stall warnings | 0 / 0 | 0 / 0 | 65 / 26 | 1,124 / 14 |
| Engine stop observed before teardown | Yes, at tick 800 | Yes, at tick 800 | No | No |
| Acceptance | Pass | Pass | Fail | Fail |

The conservative verifier checks each person's trip counters, destinations, distances
(200 m or 400 m per trip), completion reasons, unique daily summary, and matching
positive MICRO traversals. The optimistic counts are observed output, not a claim
that no corresponding speculative transition ever executed. Both optimistic runs
reached the 75-second launcher bound; its subsequent JVM teardown is excluded from
normal engine completion. Wall time includes startup and teardown and is not a
performance result.

An intermediate `snapshot-optimistic-single` run completed all four trips and two
daily schedules, but discarded three late anti-messages at a null-state Car before
termination. Its outcome checks pass; strict runtime acceptance fails. The final
build's failed runs above remain the current result. That intermediate success is
not evidence of reliable optimistic execution or parity.

The original Person exception and the pinned local replay defects are fixed, but
the runtime still fails the model-level acceptance gate. Existing gaps in stable
input/self-event ordering, speculative actor retirement, model completion state,
and accounted GVT remain active work. The observed cancellations and discarded
messages require a controlled multi-actor trace; these runs do not identify one
sole cause for every remaining stall. A correct commit/lifecycle boundary must
replace direct speculative flush/stop paths before these reports can be trusted.

Reproduce the final cases after `sbt assembly` using the preserved harness and input:

```bash
python3 /tmp/htc-person-replay-20261022/run.py rerun-optimistic-single \
  "$PWD/target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar" \
  /tmp/htc-person-replay-20261022/time-warp-single.json 20
python3 /tmp/htc-person-replay-20261022/run.py rerun-optimistic-multi \
  "$PWD/target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar" \
  /tmp/htc-person-replay-20261022/time-warp-multi.json 21
python3 /tmp/htc-person-replay-20261022/analyze.py \
  rerun-optimistic-single rerun-optimistic-multi
```

Choose fresh run labels because the JSON reporter appends to an existing directory.
Substitute the corresponding `discrete-event-*.json` for separate conservative runs.
