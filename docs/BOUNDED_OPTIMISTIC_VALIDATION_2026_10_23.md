# Bounded optimistic execution validation — 2026-10-23

The subsequent [progressive-input validation](PROGRESSIVE_OPTIMISTIC_VALIDATION_2026_10_24.md)
records the new source-window protocol and streaming parser on a later artifact.
The measurements below retain their original artifact and scope.

This validates [startup execution budgets and safe history collection](BOUNDED_OPTIMISTIC_EXECUTION.md)
on the distributed optimistic runtime. Each process selects one synchronization
policy before startup. Bounded/unbounded benchmark settings are separate optimistic
runs, not policy switching or mixed-policy execution.

## Tested artifact and boundary regressions

`sbt 'testOnly *DistributedRuntimeSpec' test assembly` passed **508 tests across
109 suites**, then built `/tmp/htc-bounded-20261023.jar`. Assembly hash:
`5cff2213160a30a525d97bfdd982c49c5058432f`; SHA-256:
`937b871c82f71c38a842db3489333f9a3492f4840c5a1c9f5651b0571314f083`.
Log: `/tmp/htc-bounded-resume-fixed-build.log`.

Focused regressions cover:

- Inclusive window/horizon boundaries, overflow-safe addition, zero-width windows
  and invalid execution budgets.
- Positive inputs held by tick and event budgets, cancellation accepted exactly at
  GVT, identities retained at GVT and removed only after it passes them.
- An orphaned dispatch after a reply handler forgets completion, and a reply wait
  with no queued future work: both fail explicitly instead of reporting success.
- A thousand-tick reply-producing counter behind a one-tick window. Its requester
  retains an actual unfinished dispatch slot while waiting; GVT advances to the
  future work and the final count is exactly 1000.
- The bounded counter retains at most eight log records, six read records and six
  processed identities in its assertions; the same model with collection disabled
  retains at least 1000 log/read records. This is an object-retention check, not a
  JVM heap measurement. The earlier final counter-only run is recorded in
  `/tmp/htc-bounded-counter-final.log`.
- Ten thousand committed events/read journals collected in batches leave one state
  floor and an empty log/journal. A later event at GVT can still roll back to that
  floor. A low-tick checkpoint after an earlier uncommitted sequence cannot be
  selected as the floor.
- Obsolete queued dispatch entries reclaimed behind earlier live work, preserving
  the actual queue order and dispatch index.
- Deliberately staggered LTM resumption: next-round input reaches a paused owner
  before its local resume command and is accepted into the frozen LP queue.
- Kryo round-trips of startup limits and queued-dispatch/retention snapshot fields.

The counter is a small model independent of mobility, with a deterministic step
observation through `EventReadJournal`. It exercises the runtime contract without
adding any model fields to the core checkpoint type.

## Defects exposed by tighter validation

The first narrow-window MICRO run failed while replaying populated Link lanes:
JSON restored integer lane keys as strings. `LinkLaneCheckpointSpec` reproduces
the cast before the fix (`/tmp/htc-bounded-lane-before.log`). Link state now declares
Integer lane-key types; typed vehicles, lane arithmetic and queue independence
survive the actual checkpoint codec. The global numeric mapper policy is unchanged.
The preceding artifact is preserved as
`/tmp/htc-bounded-before-lane-fix-20261023.jar`.

The first bounded 32-person cohort also exposed an existing resume-order race:
the receiving LTM was still paused when new traffic from a resumed peer arrived.
Allowing queue acceptance in that between-round phase fixes the race without
executing its LP early or accepting traffic during collection/commit/retirement.
The new protocol test holds the receiver's resume explicitly. Failed earlier
attempts are retained under `before-resume-fix-*` in the campaign directory and
are excluded from accepted results. The preceding artifact is
`/tmp/htc-bounded-before-resume-fix-20261023.jar`.

## Mobility matrix

Preserved inputs are the same seed-42 two-person/two-owned-Car scenario used in
[the distributed validation](DISTRIBUTED_OPTIMISTIC_VALIDATION_2026_10_23.md): static
routing, two LTMs, no transit and one actual JSON sink with batch size 1. One-link
plans require four 200 m trips; two-link plans require four 400 m trips. Each run
requires two daily summaries. MICRO runs use two links in each direction.

The campaign root is `/tmp/htc-bounded-validation-20261023/`; `run_matrix.py`,
`matrix.json` and report-comparison JSON files preserve commands and observations.
Run directories under `/tmp/htc-person-replay-20261022/` are:

- `bounded-final-cons-meso-single` / `accounted-bounded-final-meso-single`
- `bounded-final-cons-meso-multi` / `accounted-bounded-final-meso-multi`
- `bounded-final-cons-micro-multi` / `accounted-bounded-final-micro-multi`
- `accounted-bounded-final-one-tick-meso` / `accounted-bounded-final-one-tick-micro`

The first six cases use the startup defaults: 50 ticks ahead, 256 events per LP
above GVT, 64 inputs per turn, collection enabled. The last two use a one-tick
window, eight speculative events and two inputs per turn. Their physical reports
are also compared with the corresponding conservative cases.

| Case | Trips / expected | Summaries / expected | Entries / exits | Trip distance (m) | Rollbacks | Strict acceptance |
|---|---:|---:|---:|---:|---:|---|
| Conservative MESO one link | 4 / 4 | 2 / 2 | 4 / 4 | 200 | 0 | Pass |
| Optimistic MESO one link | 4 / 4 | 2 / 2 | 4 / 4 | 200 | 28 | Pass |
| Conservative MESO two links | 4 / 4 | 2 / 2 | 8 / 8 | 400 | 0 | Pass |
| Optimistic MESO two links | 4 / 4 | 2 / 2 | 8 / 8 | 400 | 31 | Pass |
| Conservative MICRO two links | 4 / 4 | 2 / 2 | 8 / 8 | 400 | 0 | Pass |
| Optimistic MICRO two links | 4 / 4 | 2 / 2 | 8 / 8 | 400 | 95 | Pass |
| One-tick optimistic MESO two links | 4 / 4 | 2 / 2 | 8 / 8 | 400 | 33 | Pass |
| One-tick optimistic MICRO two links | 4 / 4 | 2 / 2 | 8 / 8 | 400 | 14 | Pass |

Every accepted case completes four trips, two summaries and all expected matched
entries/exits (4 for MESO one link, 8 for MESO/MICRO two links). Person and SUMO trip
distances are 200/400 m. MESO completion ticks remain 30/220 for one link and
50/240 for two links. Errors, replay mismatches, null-state discards and stalls are
zero. Optimistic GVT is monotone, and successful engine completion precedes
reporter shutdown after acknowledged drain.

All five physical report comparisons pass: default one/two-link MESO and two-link
MICRO policy pairs, plus both one-tick cases against conservative output. Multisets
preserve duplicate counts and all physical fields, excluding run ID, wall-clock
`real_time` and diagnostic SUMO CPU `duration`. Expected row counts are 52, 60 and
152 respectively. The launcher subsequently stops the remaining cluster JVM;
engine completion and natural JVM exit remain distinct observations.

## Repeated resource-control comparison

`run_comparison.py` under the campaign root uses the **same executable** for three
alternating serial repetitions of each setting. Both settings keep the corrected
GVT/wait accounting and cooperative 64-input turns. The unbounded reference uses
`Long.MaxValue` lookahead, `Int.MaxValue` speculative events and collection disabled;
the bounded run uses defaults. Thus the comparison isolates these execution/storage
controls from binary/version differences.

Inputs are the preserved 32-person/32-owned-Car, two-link MESO cohort in
`/tmp/htc-distributed-benchmark-20261023/simulation.json`: 64 expected 400 m trips,
32 summaries and 128 matched traversals. Four JVM processors, 2 GiB heap, two LTMs
and one JSON sink are held fixed. Timing spans first LTM execution start through
acknowledged engine completion, excluding cluster startup and launcher teardown.

Each accepted run passes plan, distance, traversal, speed-law and runtime checks;
each paired report comparison must match all 960 committed physical rows. The
campaign writes `benchmark-results.json`, `benchmark-summary.json` and
`benchmark-{1,2,3}-comparison.json`. Retention counts are fresh pre-commit snapshots
summed across live LPs; retirement may remove finished LPs from later samples.

| Measurement | Unbounded reference | Bounded defaults |
|---|---:|---:|
| Accepted repetitions | 3 / 3 | 3 / 3 |
| Trips / summaries / matched traversals per run | 64 / 32 / 128 | 64 / 32 / 128 |
| Execution seconds, repetitions 1 / 2 / 3 | 7.887 / 8.511 / 6.836 | 7.840 / 7.335 / 8.426 |
| Median execution seconds | 7.887 | 7.840 |
| Median rollback count | 3826 | 3893 |
| Median peak retained events | 1384 | 567 |
| Median peak retained read records | 128 | 64 |
| Median peak processed identities | 936 | 311 |
| Median peak cancellation identities | 6374 | 1644 |

All three paired comparisons match all 960 committed physical rows. Median peak
retained events decrease by about 59%, read records by 50%, processed identities
by 67% and cancellation identities by 74%. These are retained-record measurements,
not equivalent percentages of heap savings. The bounded runs' final pre-commit
snapshots contain 64 log records, zero read records and zero cancellation records;
remaining events at the current frontier are collected only by the final commit.

Timing ranges overlap substantially and median time is essentially unchanged.
Rollback counts do not consistently improve; this campaign establishes lower
history retention and preserved results, not a general performance speedup. The
baseline and bounded labels are
`accounted-bounded-final-bench-{unbounded,bounded}-{1,2,3}` under the launcher root.
All eight mobility cases plus these six cohort runs use the tested artifact above.

## Limits of the evidence

These are bounded correctness and retention checks, not a one-million-actor or
city-scale capacity result. They do not measure a hard heap-byte bound. State size,
pending input/transport queues, per-event fan-out and bursts at GVT remain outside
the soft execution budget. Progressive optimistic loading, live model birth,
timestamped shared routing, migration/recovery, realistic large-network workloads
and real multi-node transport validation remain separate work.
