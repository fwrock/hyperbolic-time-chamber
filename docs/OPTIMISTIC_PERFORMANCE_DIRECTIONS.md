# Optimistic performance: speculation, partitioning and asynchronous GVT

Status: 2026-11-04. This note records the current implementation, the performance
questions raised by Mobiliti/Devastator, and proposed follow-up work. It is not an
approved asynchronous GVT protocol or a claim of measured city-scale speedup.

## Current HTC behavior

Both `discrete-event` and `time-warp` execute timestamped events. The former is
conservative; the latter permits speculative execution and rollback. Optimistic
execution does not require visiting every empty tick. MICRO physics can explicitly
schedule work every runtime tick; those physics events are actual model work.

Production Time Warp uses `AccountedOptimisticGlobalTimeManager` (GTM) and
`AccountedOptimisticLocalTimeManager` (LTM). Their
[accounting contract](DISTRIBUTED_OPTIMISTIC_RUNTIME.md) establishes GVT through
freeze, channel drain, fresh snapshots, commitment and retirement before resuming.
Nonblocking actor messages and asynchronous resumption do not make this a
background GVT algorithm: model execution pauses for the cut.

| Mechanism | Current behavior | Performance implication |
|---|---|---|
| Speculation | At most 50 ticks ahead of GVT by default | Can limit available parallel work; increasing it can increase rollback and retained history |
| Per-LP execution budget | 256 uncommitted events above GVT; 64 inputs per actor turn | A larger time window alone does not remove these limits |
| Round trigger | All owners idle for the current round, plus a 500 ms fallback heartbeat | Idle-triggered rounds remove waits for the heartbeat when runnable work is exhausted |
| Owner assignment | `floorMod(LogicalProcessId.hashCode, workerCount)` | Distributes identities without explicitly preserving road-network locality |
| Progressive input | Acknowledged windows, capped at a configured span of 1,000 ticks by default | Enrollment and execution alternate; accepted input coverage also caps execution |

The inclusive execution ceiling is:

```text
min(simulation horizon, GVT + maxAheadTicks, accepted input coverage)
```

Before the first GVT cut, the configured simulation start supplies the base.
An empty gap from tick 0 to an event at tick 20,000 does not require 400 advances
of 50 ticks. Once input coverage is established, a safe cut can jump directly to
the minimum pending timestamp. Pending messages, cancellations, creation work and
unloaded input must also be accounted for; the smallest scheduled self-event alone
cannot establish safety.

Ticks are runtime units. Their physical duration depends on `Simulation.tickDuration`;
50 ticks must not automatically be described as 50 seconds.

See [execution limits](BOUNDED_OPTIMISTIC_EXECUTION.md) and
[progressive input](PROGRESSIVE_OPTIMISTIC_RUNTIME.md) for the exact contracts and
configuration variables. Sources default to eager loading; progressive loading is
a source configuration choice independent of the synchronization policy.

## Implemented improvement: request a round when work is exhausted

An LP sends an idle hint after draining runnable input, tagged with the execution
round and its latest delivery token. Its owner invalidates idle state on a newer
delivery and ignores obsolete hints. An execution continuation is still active
work. Owners aggregate LP status with their schedules and outstanding transport;
only activity changes reach the GTM.

When all owners report idle for the current round, the GTM starts the existing
safe cut immediately. Duplicate hints are coalesced. Only one idle-triggered round
is allowed for an unchanged pair of GVT and accepted input coverage, preventing
repeated empty cuts. The heartbeat remains a fallback.

This changes when a cut begins. It preserves the frozen-cut proof, report receipts,
retirement rules and startup speculation limits. It does not implement background
GVT, background progressive enrollment or network-aware ownership.

The 2026-11-03 combined regression run passed 88 tests in 14 suites. The sparse
1,000-tick-gap case completed in two rounds with a 60-second heartbeat, demonstrating
progress independently of that heartbeat. This is functional regression evidence,
not a city-scale throughput benchmark. Relevant executable coverage includes:

- [Idle notice correlation and round coalescing](../src/test/scala/core/runtime/distributed/OptimisticIdleRoundSpec.scala).
- [Sparse events, event budgets and retained history](../src/test/scala/core/runtime/distributed/BoundedOptimisticRuntimeSpec.scala).
- [Progressive input boundaries](../src/test/scala/core/runtime/distributed/ProgressiveOptimisticRuntimeSpec.scala).
- [Transport accounting](../src/test/scala/core/runtime/distributed/DistributedRuntimeSpec.scala)
  and [dynamic actor families](../src/test/scala/core/runtime/distributed/RuntimeBirthFamilySpec.scala).

## What Mobiliti/Devastator contributes to the comparison

The [Mobiliti design paper](https://crd.lbl.gov/assets/Uploads/ITSC18-0935-MS-Final.pdf)
describes optimistic Time Warp execution, a tunable distance ahead of GVT, road
partitioning to reduce communication across workers, and GVT computation concurrent
with event processing. It does not establish an optimal 50-tick window for HTC.
The [Devastator repository](https://github.com/cychan-lbnl/devastator) describes its
C++ runtime, GASNet-EX communication, lightweight thread queues and asynchronous GVT.

These mechanisms motivate HTC experiments; they do not establish transferable
speedups. Runtime costs, traffic-model detail, event counts and hardware differ.
The cited sources do not establish HTC-style progressive actor loading in Mobiliti.

## Proposed first step: measure speculation and communication costs

Keep the current safe protocol as the baseline. Record the code revision, scenario
hashes, seed, selected policy, tick duration, worker/JVM placement, heap settings,
loading strategy and report sinks for every run. Separate startup/indexing/loading,
model execution, report draining and total end-to-end wall time. Keep cold and warm
cache results identifiable.

Start with `max-ahead-ticks` values of 50, 200 and 1,000 as experimental candidates.
Hold event budgets, heartbeat cadence, input coverage and other settings fixed.
If the per-LP budget or input boundary becomes the binding limit, report that and
vary it in a separate experiment. Use repeated seeded sparse, dense, MESO and MICRO
workloads; report variability rather than selecting the fastest run.

Measure committed throughput, wall time, CPU utilization, peak heap, garbage
collection, rollback/replay work, retained history, round frequency and coordination
time. Existing `[ACCOUNTED-ROUND]`, `[ACCOUNTED-RETENTION]` and `[LTM-ACCOUNTING]`
records provide part of this evidence. Add instrumentation for missing measurements;
do not infer idle time or rollback rate solely from GVT progress. Compare committed
model results against the same scenario under the established reference execution.

There is no established best window yet. Prefer settings that improve repeatable
end-to-end performance while preserving committed results and acceptable memory
use. A wider speculation window can hide coordination latency, but also performs
more work that may later be rolled back.

## Proposed network-aware ownership

Evaluate a fixed assignment computed before startup that places strongly interacting
actors on the same owner, while balancing event load and memory. Road connectivity
can guide mobility assignments; shared services, people and moving vehicles also
need an explicit assignment rule. Retain a deterministic fallback for other models
and define ownership for progressive cohorts and model-created children.

Runtime owner assignment and physical shard placement are separate mechanisms.
Changing shard allocation alone does not change the current hash-based LTM owner.
All owners and the GTM must agree on the same assignment for routing, registration
and birth authorization. An optimization must measure actual cross-JVM traffic as
well as cross-owner transfers: distinct owners can run on the same JVM.

Benchmark locality against load imbalance, hotspots, rollback work and end-to-end
time at fixed worker counts. Keep this first experiment static. Live repartitioning
would additionally require an optimistic migration protocol, which is unsupported.

## Proposed asynchronous GVT: protocol work required

The target is to compute a safe frontier while model events continue executing.
Removing freeze messages or reducing independently sampled queue minima does not
provide the current correctness guarantee. A replacement must establish a consistent
distributed cut that accounts for messages crossing the observation boundary.

Before implementation, specify an epoch/marker or message-coloring algorithm and
prove the following obligations for HTC's actual transport:

1. **Complete accounting:** include positive interactions, anti-messages, local
   dispatches, queued/executing work, schedule controls and asynchronous creation.
   Cover both LP-to-owner and owner-to-owner channels, including delayed acceptance
   acknowledgements and events that generate further messages after sampling.
2. **Safe commitment:** publish a nondecreasing frontier only after that round's
   accounting is complete. Serialize each LP's frontier update and history pruning
   with its event processing. Preserve rollback at GVT and commit strictly below it.
3. **Safe lifecycle:** prevent actor retirement while a live snapshot, queued input
   or transport obligation can still reference it. Define the membership treatment
   of cancellable births and progressive cohorts explicitly.
4. **Input bounds:** retain the earliest unloaded-input bound until acknowledged
   producer closure. Background GVT does not itself authorize concurrent enrollment.
5. **Completion and reporting:** preserve actual sink receipts, final flush
   acknowledgements and inclusive-horizon execution. A background frontier update
   is not a proof of settled completion. A separately coordinated final cut or
   enrollment boundary may remain necessary.
6. **Failure behavior:** reject stale epochs and duplicate/foreign summaries without
   weakening participant-loss failures. Independent restart and ownership migration
   require their own supported recovery contract.

Retain the current protocol as a reference while validating the replacement.
Required adversarial cases include delayed positives and cancellations crossing
snapshot markers, send-before/receive-after snapshots, same-tick causal chains,
creation during a round, input-window boundaries, stale acknowledgements, report
draining, horizon truncation and worker loss. Exercise ordinary asynchronous actor
mailboxes and independent cold serialization codecs, then real cross-JVM transport.
Measure performance only after committed results agree with the reference.

## Follow-up order

1. Establish the benchmark baseline and identify the binding speculation, input,
   communication and coordination costs.
2. Evaluate speculation settings and static ownership locality as separate changes.
3. Select and document an asynchronous GVT algorithm and its HTC-specific proof.
4. Implement and validate background GVT against the frozen reference, including
   lifecycle, reporting and cross-JVM cases, then repeat the performance campaign.

These are proposed follow-up tasks. This note changes no runtime defaults or policy
selection. The directions apply to Time Warp; conservative execution requires its
own performance assessment.
