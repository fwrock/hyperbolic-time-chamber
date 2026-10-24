# Extensible conservative and Time Warp execution in HTC

Review date: 2026-10-22. Scope: the current core time managers, rollback machinery,
simulation actor, hybrid actors, and output pipeline. This is the current architecture
recommendation; the older Time Warp documents retain their historical investigations.

## Recommendation and scope

Each simulation run selects **one synchronization policy before execution**:
conservative or optimistic (Time Warp). Every actor and time manager in that run
uses the selected policy, which remains fixed until the run ends.

HTC should share model-independent execution infrastructure between these two
startup configurations. A model defines its state,
event transitions, event-time semantics, and deterministic inputs. The runtime owns
ordering, scheduling, transport identity, rollback, cancellation, commitment, and
lifecycle. Adding an energy, land-use, epidemic, or another mobility model should
not require modifying `SimulationBaseActor` or adding fields to a core checkpoint.

The 2026-10-23 follow-up implements a **paused-round reference runtime** for
optimistic commitment and termination. Every positive, anti-message, dispatch, and
schedule control crosses an acceptance ledger. A fresh frozen cut determines GVT;
logical completion remains reversible, and physical retirement and acknowledged
report drain precede successful completion. See
[the implemented protocol and its limits](TIME_WARP_ACCOUNTED_RUNTIME.md).

This is a bounded correctness baseline, not a certification of arbitrary models or
distributed recovery. Production optimistic runs now enroll deterministic source actors
in acknowledged input windows and reclaim committed history. Accounted transit birth now has a cancellable lifecycle adapter; shared dynamic
routing feedback remains rejected pending a timestamped adapter. See
[model-created actor birth](RUNTIME_ACTOR_BIRTH.md). Scalable asynchronous GVT remains later work. The production conservative factory now uses distributed
LTM channel accounting and a GTM frontier barrier; see
[the conservative protocol](CONSERVATIVE_CHANNEL_RUNTIME.md). Its simulator
validation is [recorded separately](CONSERVATIVE_CHANNEL_VALIDATION_2026_10_23.md)
from the earlier legacy-barrier results: MESO physical payloads match in the bounded
cases. A subsequent change moves Link summary counters into checkpointed
`LinkState` and distributes optimistic transport; see
[the current optimistic protocol](DISTRIBUTED_OPTIMISTIC_RUNTIME.md).

Both production policies now keep channel accounting in their LTMs. The existing
GTM collects conservative frontiers per causal batch or optimistic summaries per
paused GVT round. Production optimistic startup no longer creates an
`AccountedRuntimeCoordinator` child. That centralized class remains a reference for
tests/comparisons. Distributed ledgers remove per-message GTM routing but do not
remove global agreement or establish city-scale performance.

Each mode must satisfy independently expected outcomes before separate-run comparison.
The roadmap below remains the architecture target. Per-actor policy selection and
switching during execution are outside the design.

## Remaining core policy work — status after 2026-10-24 follow-ups

The [bounded-execution follow-up](BOUNDED_OPTIMISTIC_EXECUTION.md) enables startup
execution budgets and committed-history reclamation. It also separates queued work
from waiting obligations in distributed cuts so small windows cannot pin GVT at an
old request tick. This changes resource controls, not whole-run policy selection.

This roadmap concerns execution policies and their runtime contracts. The bounded
Car MESO/MICRO results do not close the gaps below. Several LTMs already execute
concurrently in the single-node checks; multi-node validation adds remoting,
serialization, process isolation, and cluster membership behavior.

| Order | Gap | Kind / affected policy | Required completion evidence |
|---|---|---|---|
| 1 | Account conservative interactions and close the event frontier before execution advances. Independent same-tick events also need the established stable order. | Implemented in the production conservative path; bounded validation required below. | Injected two-LTM tests hold a delivery after its sender finishes and reverse noncommuting inputs against a sequential reference. Simulator acceptance is tracked in the conservative validation record. |
| 2 | Restore progressive input loading during execution with explicit producer lower bounds and closure. | Implemented for deterministic JSON/SQLite source windows; loading and execution alternate at paused enrollment barriers. Arbitrary live producers and background prefetch remain outside this implementation. | Delayed-window, enrollment-at-GVT, producer-loss and source-failure regressions; separate MESO/MICRO progressive-versus-preloaded outcome checks. See PROGRESSIVE_OPTIMISTIC_VALIDATION_2026_10_24.md. |
| 3 | Require report receipt and sink drain before successful conservative completion. | Implemented in the production conservative path. | Protocol tests delay report receipt and final flush independently; completion waits for both. Real Kafka/ClickHouse and crash-retry durability remain unvalidated. |
| 4 | Reclaim committed history/read journals/identities and bound speculative execution. | Implemented in the production optimistic path: tick window, event/turn budgets and strict-boundary fossil collection. Hard heap limits and city-scale capacity remain open. | Long counter regression retains bounded records; cancellation exactly at GVT survives. See BOUNDED_OPTIMISTIC_VALIDATION_2026_10_23.md for narrow-window mobility and retained-record measurements. |
| 5 | Implement accounted model-caused actor birth and its descendant effects; make it reversible in optimistic runs. | Implemented for one deterministic unique logical birth slot per child ID, with ordinary positive/anti cancellation and dormant retirement. | Birth/freeze ordering, cancellation before/after activation, descendant rollback, replay, selected-policy inheritance and retirement-fence regressions; see RUNTIME_ACTOR_BIRTH_VALIDATION_2026_10_24.md for transit scenario evidence. |
| 6 | Verify the runtime across multiple JVMs/nodes under delayed and reordered transport. | Bounded two-JVM loopback validation; multi-host/partition coverage remains open for both policies. | See MULTI_JVM_POLICY_VALIDATION_2026_10_24.md for cold-codec regressions, real Artery transport, physical references and registered-worker loss. This is not coordinated recovery or scale certification. |
| 7 | Provide coordinated recovery, supported migration, and durable effect retry/deduplication. | Unsupported fault-tolerance features; especially optimistic histories and commitment. | Recover a consistent distributed state with channels, histories, schedules, membership, and committed effects preserved; an independent actor restart is insufficient. |
| 8 | Bound and measure distributed optimistic execution, then replace paused global cuts if necessary. | Performance/architecture work; optimistic. Per-message ledgers are now LTM-owned. | Preserve safety tests; measure throughput, pause costs and retained memory before selecting a nonblocking GVT algorithm. Distributed routing alone is not a scale claim. |

Items 1 and 3 share the implemented conservative closure protocol. Item 2 uses a
model-independent producer protocol, separate from speculative model-caused birth.
See [the progressive optimistic protocol](PROGRESSIVE_OPTIMISTIC_RUNTIME.md).
The conservative path retains its own windowed input protocol.

### Progressive input contract

A deterministic input source already knows its scenario actors and timestamps.
Registering those actors gradually does not inherently require rolling back their
creation. The producer must instead participate in safe-time accounting:

1. Register the producer before execution, with a lower bound on the earliest
   actor/event it may still introduce. An unknown bound constrains commitment; it
   must not be treated as an empty source.
2. Include every open producer's lower bound in the accounted cut. GVT must not
   exceed that bound, even when existing LPs speculate farther ahead.
3. Account loading/initialization/registration as a transaction. Advance the bound
   only after all earlier input is accepted by the runtime, including LP ownership
   and initial schedules. A requested window horizon is not an acknowledgement.
4. Enroll new LPs without crossing a committing/retiring membership cut. Drain or
   defer registration consistently with the freeze protocol and invalidate an
   affected collection round before publishing its frontier.
5. Close a producer explicitly after exhaustion and accepted registration of its
   final window. Empty LP queues cannot close an open source or terminate the run.

An actor at the published GVT may still be introduced; one below it must fail the
run. Sources cannot lower a bound that already authorized commitment. Production
optimistic execution now implements this contract: the accepted inclusive window
end caps execution, its successor constrains GVT, and new enrollment follows a
committed, retired and paused round. The next snapshot includes the new cohort;
no committing cut changes membership. It does not preload all source actors or
read the next window concurrently with model execution.

In contrast, an actor created by a speculative model event is causally owned by
that event. Its birth, initialization effects, descendant messages, cancellation,
and retirement need the reversible lifecycle adapter in item 5. These are distinct
requirements, even if they eventually share registration and identity primitives.

## Basis and review method

The foundational Time Warp model uses timestamped events, saved state, rollback,
and anti-messages to undo speculative computation. Irrevocable output must respect
the global progress bound. See Jefferson's primary paper,
[Virtual Time (1985)](https://worrydream.com/refs/Jefferson_1985_-_Virtual_Time.pdf).

A distributed snapshot must account for channel state as well as local state.
Mattern describes snapshot-based GVT methods that support non-FIFO delivery:
[Efficient Algorithms for Distributed Snapshots and Global Virtual Time Approximation (1993)](https://vs.inf.ethz.ch/publ/papers/snapshot_gvt.pdf).

Conservative execution avoids unsafe event processing; optimistic execution repairs
it. Both need an explicit event-time ordering contract. Fujimoto reviews these
approaches in [Parallel and Distributed Simulation (2015)](https://informs-sim.org/wsc15papers/004.pdf).

The implementation choices below are recommendations for HTC, inferred from the
checkout and these principles, rather than a claim that any cited paper specifies
HTC's actor protocols. Findings marked open are code-path findings unless a test or
scenario result is stated. Dated claims in older documents were not treated as proof.

## Findings in the current core and model

Paths in this table are relative to `src/main/scala`.

| Priority | Finding and concrete failure path | Status in this change |
|---|---|---|
| P0 | `core/actor/manager/time/gvt/MarginBasedGVTEstimation.scala`: local reports can all be at 1000 while a tick-10 positive or anti-message is delayed in a shard/router/mailbox. Subtracting 100 still allows commitment of tick 10. Simulation-time margin does not bound wall-clock delivery delay. | Replaced in production optimistic execution by a ledger-drained, frozen snapshot round. The legacy estimator remains for compatibility/tests and does not authorize commitment. |
| P0 | `OptimisticGlobalTimeManager.recomputeGvtAndCheckTermination`: heartbeats repeatedly evaluate the same `latestReports`; unchanged idle observations do not prove a fresh consistent cut or empty channels. A delayed request, reply, anti-message, or creation can arrive after termination starts. | Replaced by fresh participant snapshots, delivery acceptance, producer closure, and explicit completion/failure results. Repeated idle reports no longer authorize success. |
| P0 | `SimulationBaseActor.selfDestruct`: direct model calls flush every report and stop immediately. Stopping an actor does not prove its speculative history is committed. LTM deferred destruction is bypassed by these calls. | Fixed in the accounted path: checkpoint terminal intent, retain the LP for rollback, and authorize physical retirement only after a safe cut. Raw destruction fails the run. |
| P0 | `SimulationBaseActor.reportToSpecificReporter` sent directly, bypassing buffering, rollback discard, and replay suppression. `reportMirrored` therefore leaked speculative Kafka output even when its default output was buffered. `Link` uses targeted ClickHouse output. | Fixed: all reporting destinations now share `reportToTarget`. |
| P0 | `handleInteractWith` forgot an anti-message whose positive was absent. If cancellation arrives before the positive, or after the positive was undone but before it is reintroduced, the cancelled event can execute later. | Fixed: retain cancellation identities outside model state; filter positives and reprocessing. GVT-based reclamation remains open. |
| P1 | `OptimisticLocalTimeManager.dispatchNextAvailable` computed running IDs once. If an actor was queued at ticks 1 and 2 before startup, the same pass sent both, despite its first dispatch already becoming in flight. | Fixed: update the per-pass running-ID set after each dispatch. |
| P1 | `SimulationBaseActor.applyMigrationSnapshot` cleared relationships only if the saved map was nonempty. Restoring an empty map left relationships introduced by undone events. | Fixed: replace the map even when the checkpoint is empty. |
| P1 | `RollbackHistoryHandler.pruneBelow` could keep a checkpoint **at** GVT after processing an event at that tick, removing the earlier checkpoint needed to undo that event. | Fixed: retain a floor strictly before GVT. Collection is still not enabled by this patch. |
| P1 | `SimulationBaseActor` imported the mobility `DynamicWeightCache`. Its per-link map overwrote repeated reads within an event; replay could not reproduce two different observations of the same key. | Fixed foundation: generic ordered `EventReadJournal`; the mobility cache opts in at its read boundary. |
| P1 | `GPSUtil.calcRouteCompact` can miss its process-wide cache live and hit it during replay, skipping the weight reads made by the original search. Recording only cache-internal reads is therefore not a stable replay boundary. | Fixed: journal immutable route results above positive/negative cache lookup and reconstruct fresh mutable queues for each consumer. Nested reads belong to their outer observation. |
| P1 | The route cache key distinguishes walking from vehicle routes but does not distinguish dynamic/static weights or a committed cost version. A route computed with dynamic costs can remain cached after those costs change. | Open; cache immutable topology separately from time/version-dependent model decisions. |
| P1 | `Link.microTickScheduled`, `signalAtExit`, and `emptyGraceTick` were excluded from snapshots as caches. They gate MICRO scheduling, signal behavior, and grace termination; no deterministic rebuild ran on restore. | Fixed: capture and replace these through namespaced model snapshot hooks. |
| P1 | `handleSpontaneous` assigned `currentTick = event.tick` without checking whether interactions already advanced the actor beyond it. A delayed spontaneous event could execute against future state without rollback. | Fixed for earlier-tick dispatch: restore the prefix, cancel undone outputs, process the spontaneous event, then reprocess surviving interactions. Finish the incoming dispatch normally, without an extra clear/reschedule exchange. The shared input queue and full-order rollback now cover same-tick conflicts. |
| P1 | Straggler detection compares only `event.tick < currentTick`. Same-tick noncommuting interactions follow mailbox arrival order; Lamport clock updates do not sort the mailbox or trigger rollback for an earlier same-tick order key. | Shared causal microsteps and stable keys now drive the ordered actor queue and optimistic rollback. The production conservative path now closes channels before granting a causal batch; see CONSERVATIVE_CHANNEL_RUNTIME.md. |
| P1 | `OptimisticLocalTimeManager.localVirtualTime` ignores pending scheduled ticks while `runningEvents` is nonempty; a queued earlier tick can be omitted from the minimum. Passive/reactive LP activity and interaction mailboxes are not represented by spontaneous dispatch bookkeeping. | Queued schedules and actual in-flight dispatch ticks now participate in the minimum, including after rewind. The accounted snapshot also includes LP queues, passive LPs, and drained transport acceptance. |
| P1 | `OptimisticGlobalTimeManager` assigns the newest estimate directly to `currentGvt`. Late reports can lower it; already committed effects cannot be reversed. Merely clamping with `max` does not make an unsafe estimator safe. | Fixed in the accounted coordinator: validate the frozen cut before publishing; a regression is fatal. |
| P1 | `LinkMetricsReporter.publishDynamicCost` writes the shared routing cache during speculative execution and coast-forward. Recording reads makes a surviving event repeat its observations, but does not retract a cache write or correct another LP that consumed it. | Adapter remains open. The hybrid capability hook rejects shared feedback in accounted runs; static routing is supported. |
| P1 | `BusStation`/`SubwayStation` defer child creation until GVT but can then initialize a child using an earlier planned start tick. Their polling and local pending/ACK variables are outside a general lifecycle log. Delaying creation alone does not prove absence of new work below GVT. | Implemented in the production accounted policies: ordered activation/cancellation, owner-accounted initialization and checkpointed birth replies replace GVT-deferred creation and polling. See RUNTIME_ACTOR_BIRTH.md. |
| P1 | Completion guards in vehicle journey reporters and `Person.scheduleCompleteReported` were actor-local and cleared after rollback rather than restored with the exact state prefix. `Person.scheduleAlreadyTruncated` is also outside the snapshot. | Car's journey fields and completion guards now live in checkpointed `CarState.journey`; its reporter accesses that state through a callback. Person completion/truncation guards now live in `PersonState` and restore with the exact prefix. Other vehicle reporters still require the same audit. |
| P1 | `CarMicroHandler` initializes an entry's velocity from `CarJourneyReporter.sumoArrivalSpeed` when no MICRO state is active. That value survived a rewind, so replay could use a future speed (the focused regression observed 5 m/s instead of the original 0 m/s). | Fixed by moving the complete journey record to `CarJourneyState` within `CarState`. The handler remains an adapter over model state; no core mobility fields or ad hoc replay overrides were added. |
| P1 | Initial rollback checkpoints were captured before `onFinishInitialize` seeded Person's cursor. Restoring that floor produced an empty plan, so replay skipped the original decision and left recorded reads unused. | Fixed: finish state initialization before registering/capturing the floor. Regression tests reproduce the original exception, restore the exact plan and pending reply identity, and exercise cancellation/replacement through the runtime. |
| P1 | `PrivateVehicle` checkpoints restored the trip owner but omitted `tripRequestSeq` and `driverAttributes`. Restoring an earlier trip after a newer activation could echo the newer token in its completion, which Person rejected. | Fixed through namespaced model snapshot metadata, shared by Car/Bicycle/Motorcycle. A JSON checkpoint regression checks the actual completion token and restored driver after a second trip overwrites both. |
| P1 | Anti-message handling used `rollbackTo(logged.tick)`, undoing earlier events at the same tick, including the causal send that preceded the cancelled input. Report discard also used tick alone. Positive straggler rollback could likewise retract the request answered by an incoming same-tick reply. | Fixed: cancel from the exact processed-event sequence; positive interactions retain the existing prefix at their tick and undo strictly later processing. Buffered reports carry their producing event's sequence. The 2026-10-23 shared queue and full-order rollback add deterministic optimistic same-tick correction. The production conservative path now uses channel-closed causal batches. |
| P1 | Tick rollback could remove a future-tick entry while retaining a later-processed, earlier-tick spontaneous entry. Restored state then omitted an event still present in the retained log. | Fixed: undo a complete processing suffix from the earliest affected event. |
| P1 | `replayLoggedEvent` previously logged an exception and continued after partially reconstructing the LP. Live dispatch also catches exceptions and can log partial mutations as completed events. | Accounted live/replay failures freeze and fail the run. Automatic independent recovery is unsupported. |
| P1 | The 2026-10-22 fixed-seed optimistic one-/two-link runs fail expected completion: blocked work, repeated cancellation, inconsistent vehicle reports, and null-state late-message discards remain. Fixing the Person journal exception did not resolve the complete multi-actor protocol. | Historical failure retained in the dated records. The accounted follow-up fixes the reproduced retirement/report loss and fabricated rollback wake; current supported-scope evidence is recorded separately. |
| P1 | `scheduleEvent`/`finishEvent` repair old timestamps by bumping them to an actor watermark plus one; rollback repair uses a privileged generation value and clear/reschedule messages. This changes event time rather than proving causal ordering. | Fixed for the versioned runtime path: identified self-events, monotone schedule revisions, atomic replacement, and explicit stale-dispatch acknowledgement preserve requested timestamps. An unstamped compatibility adapter remains. |
| P1 | Conservative idle detection ignored interaction-driven waits/activation. The bounded generated case reported two person trip starts, then `No more scheduled events` and stop, with zero completed trips. | Fixed for tracked reply obligations: both policies record and consume waits, and conservative local reports carry `hasPendingWork`. The original case now completes four trips and stops without errors/stalls. See the termination follow-up in the validation record. Full channel accounting remains part of the target architecture. |
| P1 | `MessageId` contains sender numeric ID, tick, and send counter, but no run, model/type namespace, or incarnation. Counters are actor-local and not preserved across restart. Reuse can alias old cancellations. | Accounted identities now include model/run/incarnation; independent restart fails explicitly. Private scheduler handles also isolate equal numeric model IDs. Migration remains disabled. |
| P1 | Shutdown acknowledgements confirm that actors sent reports; they do not confirm that every reporter persisted them. Report messages and stop messages reach sinks through different senders, so their cross-sender order is not guaranteed. | Implemented for accounted optimistic completion: sole committed producer, actual sink acceptance, and final storage flush ACK. Real Kafka/ClickHouse service validation remains open; conservative shutdown retains its legacy path. |
| P2 | `pruneBelow` has no production caller; replay logs, checkpoints, cancelled identities, processed identities, and idle actors' output can grow without bound. Historically passive actors received no periodic spontaneous GVT notification. | Strict commit broadcasts now reach passive LPs. History/index/tombstone reclamation remains open. |
| P2 | The core `MigrationSnapshot` embeds many vehicle/link fields. A process-wide random stream or real-time TTL input also couples otherwise independent models and LPs. | Extension hooks added; migrate legacy fields incrementally and make random streams event/LP owned. |

P0 means the result/commit boundary can be wrong, rather than merely slow. P1 means
ordering, state, or lifecycle can violate the intended event semantics. P2 records
scale and maintainability work; it does not reduce the severity of the P0 blockers.

The previously reported Movable route-state gap remains a hypothesis requiring a
reproduction. Route fields are already in `MovableState`; that alone neither proves
correct replay nor identifies a separate route bug. Fixing incomplete model snapshots,
ordering, feedback, and replay inputs must precede attributing divergence to routing.

## Event-ordering foundation follow-up — 2026-10-23

The shared adapter now carries `EventOrder(tick, microstep, key)` on interactions
and self-events. Zero-delay outputs advance the causal microstep; SHA-256 parent
keys plus output ordinals keep logical identity independent of transport resend
counters and shard placement. Both serializers preserve the metadata. The actor
uses one ordered input queue for received events and requeued interactions, and
waits for an already-known earlier self-event instead of executing its future
interaction first. Optimistic stragglers compare the complete order, including
same-tick conflicts. Cancellation still targets the exact transport send.

Schedules now carry a monotonically increasing transport revision and the logical
self-event key. `ReplaceScheduleEvent` replaces or cancels the schedule atomically;
rollback no longer fabricates a `Long.MaxValue` completion. A stale dispatch is
acknowledged by its exact generation without invoking the model or altering the
replacement schedule. Conservative batch entries retain their captured revision,
so an obsolete wake still waiting behind a batch is skipped before dispatch.
Revisions and transport generations survive rollback;
logical event position and pending self-event are checkpointed and reconstructed.
Versioned schedules retain the requested physical tick. The unstamped compatibility
path still accepts legacy messages and retains its watermark adapter.

Existing model handlers continue to use `sendMessageTo`, `scheduleEvent`, and
`onFinishSpontaneous`. They do not allocate transport revisions or logical keys.
`onFinishSpontaneous(Some(t))` replaces the LP's pending wake; `None` cancels it.
The transport may redispatch a retained logical self-event with a new revision,
while coast-forward reconstructs its original logical identity. A model adapter
must snapshot its own behavior-affecting state and journal external observations;
these core changes do not automatically make an arbitrary model optimistic-safe.

This is the event-contract foundation, not completion of every Stage 1 gate.
Conservative dispatch still uses the existing physical-tick barrier: the engine
has no proof that an independent smaller same-tick interaction cannot still
arrive. The accounted optimistic follow-up now supplies run/incarnation identities,
channel acceptance, safe-time rounds, reversible terminal intent, and sink drain.
See [the implemented scope](TIME_WARP_ACCOUNTED_RUNTIME.md) and its remaining limits.

## Target architecture

```mermaid
flowchart TB
  M["Model plugin: state, transitions, codecs, time semantics"] --> LP
  subgraph Core["Model-independent simulation runtime"]
    LP["LogicalProcessRuntime: one owner per LP"]
    Q["Input queue: interactions and self-events"] --> LP
    LP --> J["History, snapshots, read journal, output log"]
    LP --> P["Run-wide policy fixed at startup: conservative OR optimistic"]
    LP --> T["Accounted transport: positives and anti-messages"]
    T --> Q
    T --> G["GVT / quiescence rounds"]
    G --> K["CommitCoordinator"]
    K --> J
  end
  K --> S["Report / external-effect sinks with flush ACKs"]
  K --> L["Physical lifecycle and fossil collection"]
```

Retain Pekko as the mailbox, sharding, and placement substrate. Pekko FIFO guarantees
for a sender/recipient pair are useful transport properties, but are not a global
event scheduler. A logical process is the unit of rollback; a worker/LTM may host
many independent LPs. A worker cannot claim that their interaction queues are idle
merely because its spontaneous-event batch finished.

Recommended responsibilities and interfaces (design sketch, **not current APIs**):

`SynchronizationPolicy` is an implementation boundary in the engine. Startup
chooses its implementation from the scenario configuration and propagates that
choice to every worker before execution begins. It is not an actor-level choice
or an API for changing a running simulation.

```scala
trait SimulationModel[S, E, Snapshot]:
  def capture(state: S): Snapshot
  def restore(snapshot: Snapshot): S
  def handle(state: S, event: E, ctx: EventContext): Transition[S]

trait EventContext:
  def now: VirtualTime
  def send(to: LogicalProcessId, at: VirtualTime, event: ModelEvent): Unit
  def scheduleSelf(at: VirtualTime, event: ModelEvent): Unit
  def observe[A](key: ReadKey[A])(read: => A): A
  def report(sink: SinkId, payload: ImmutableReport): Unit
  def create(spec: EntitySpec, at: VirtualTime): LogicalProcessId
  def retire(): Unit

trait SynchronizationPolicy:
  def mayExecute(lp: LogicalProcessView, event: ScheduledEvent): Boolean
  def onArrival(lp: LogicalProcessView, event: ScheduledEvent): RuntimeAction
```

The existing mutable state and lambda-based handlers can remain behind an adapter.
A wholesale conversion to immutable state is not required. Snapshot codecs must
deep-copy mutable queues/maps and cover every behavior-affecting field, including
reply obligations, completion guards, pending schedules, and deterministic RNG state.
Process-wide read-only graph handles can be rebuilt. A signal value that controls
the next transition is semantic state even if someone calls it a cache.

Keep model registration separate from synchronization configuration. A model plugin
registers event/state codecs, checkpoint version, and adapters; the scenario selects
one policy for the whole run before startup. Validate optimistic capability at load time. Unsupported external effects,
missing snapshot support, and incompatible timestamp semantics must produce a clear
configuration failure rather than silently selecting a partly supported path.

### One event-time contract

Use physical simulation time plus a delta/microstep for zero-delay causality. Define
a stable tie-break key for independent events at the same logical time. For example,
order by `(tick, microstep, logicalEventKey)` with logical keys derived from the
initial scenario and parent event/output ordinal. A send at the same physical tick
must advance the causal microstep. Keep the transport send identity separate: fresh
send counters after rollback must not change model tie-breaking or random choices.

Specify whether sub-tick MICRO updates are atomic within one LP event or externally
visible events with a common fixed-resolution timestamp. Do not introduce floating
point comparisons into cancellation or commitment. Validate timestamp overflow and
zero-delay cycles; runtime limits should report a model/protocol error with evidence.

All interactions and self-events enter the same ordered LP input queue. A smaller
order key arriving after execution triggers rollback in optimistic mode, including
same-tick cases. Conservative mode executes only when its channel/frontier proof
excludes any earlier arrival; it must also settle the chosen same-tick semantics.

### Rollback and replay transaction

1. Locate the earliest affected event by its full order key or exact cancellation
   identity. Restore a checkpoint before it.
2. Coast-forward through the surviving prefix. Rebuild state using the original read
   journal and original request correlation tokens. Suppress sends, scheduling,
   reports, creation, destruction, and external writes during this reconstruction.
3. Retract the recorded outputs of undone events with their original transport
   identities. Retain anti-before-positive tombstones; cancellation is transport
   history, not a model variable to roll back.
4. Requeue undone positive inputs except cancelled ones. Requeue self-events using
   runtime-owned schedule IDs and a schedule revision; invalidate already-issued
   stale dispatches. Execute the corrected suffix normally, producing fresh send
   identities and a fresh output log.
5. Abort the run explicitly if checkpoint restoration or deterministic reconstruction
   fails. Never continue with a partially replayed LP. A default actor restart is
   insufficient: history, pending work, and speculative messages are coordinated
   simulation state. Recovery needs its own protocol or must be rejected for that run.

Distinguish coast-forward of **surviving** events from live re-execution of **undone**
events. Suppressing sends in both would lose work; resending in both duplicates work.
Add replay checks for both read consumption and send/effect shape. Hashes can be a
debug validation aid; they are not a substitute for complete state and inputs.

The new `EventReadJournal` is the first adapter for this contract. It records an
ordered sequence, supports repeated keys, rejects reordered/missing/unused reads,
and restores nested scopes on exceptions. Its values must be immutable and its
scopes synchronous. It is currently an in-memory, convention-typed journal; typed
keys, codecs, and persistence are follow-up work. This journal reproduces an
observation during coast-forward; it does not turn shared speculative state into
causally correct model input.

Observe at a stable semantic boundary. In mobility, journaling `getWeight` alone
fails when a live route-cache miss becomes a replay cache hit. The route adapter
now journals `(cost, immutable path)` before the cache-dependent work can vary.
An outer observation temporarily suspends nested recording and stores its returned
value; replay skips its entire implementation. Mutable route queues are rebuilt
from that immutable record. Do not perform unlogged simulation side effects inside
an observation callback.

The new `captureModelSnapshot`/`restoreModelSnapshot` hooks use namespaced, versioned
string metadata, optionally containing model-owned JSON. `Link` demonstrates their
use. Prefer putting new semantic fields in model `state`; use the hooks to adapt
existing handlers and inherited actor fields. Later introduce typed snapshot codecs
without adding another bus/car-specific field to the core schema.

### GVT and termination: implement a provable baseline first

Choose the strict convention **commit time < GVT**. Events at GVT remain rollbackable.
Use the same comparison for output, lifecycle, tombstone collection, and history.
The accounted path now uses this strict boundary for buffered reports and terminal
retirement. Legacy isolated-LTM destruction code is retained for compatibility; it
does not authorize retirement in the production accounted path.

The minimum must include executing LP events, pending LP inputs/self-events, positive
and negative messages in transit, delayed creation, and lower bounds promised by
progressive/external input producers. Every LP, including reactive infrastructure,
has exactly one accounted runtime owner. Routing, buffering, and pending ACKs are
part of transport state, even if Pekko owns the physical buffer.

For HTC's first correct implementation, use an explicit **quiescent snapshot round**:

1. Fix the participant/ownership epoch. Pause new model-event execution, let current
   callbacks finish, and keep actor mailboxes processing control and delivery ACKs.
   Pausing is an actor state transition, never a blocking wait.
2. Register every send in an outgoing ledger before transmission. The receiver ACKs
   acceptance into its runtime input/cancellation structure, not merely arrival at
   a shard proxy. Include anti-messages and internal lifecycle messages.
3. Collect a fresh round-tagged report from every owner and producer. Wait until all
   callbacks are inactive and all outgoing deliveries from the frozen computation
   are accounted for. Repeat collection if processing a cancellation emits more
   anti-messages; do not reuse a previously idle report as a new round.
4. Compute the minimum of frozen pending work and producer lower bounds. Validate
   monotonicity before publishing. Unexpected work below a published watermark is a
   fatal invariant violation with its message history, not a reason to lower GVT.
5. Commit below that bound, acknowledge completion, and resume. Fossil collection
   is a separate bounded-memory milestone and is not enabled by this baseline.
   Declare normal termination only when the same accounted snapshot proves no live
   work, no in-transit messages, no blocked obligations, and no future producer input.

This costs periodic pauses and ledger/ACK overhead, but gives a tractable baseline
for the current actor topology. A future asynchronous Mattern-style epoch/color
protocol can replace it after the same invariants pass. That implementation needs
epoch-tagged send/receive accounting, crossing-message minima, serialized epoch
metadata, and a defined membership policy. A pluggable estimator over the latest
LVT numbers alone is insufficient to implement that protocol.

For a fixed simulation end time, settle all causal work before the horizon before
committing truncation. Distinguish completed runs, horizon-truncated runs, externally
cancelled runs, and failed runs. A blocked request is live work and cannot be
converted into successful termination by a plateau detector.

### Outputs, model feedback, and entity lifecycle

Use one runtime effect log for reports to every sink, external publication, spawn,
and retirement. Effects belong to a logical event and disappear when it is undone.
Conservative execution can commit immediately only for events already established
safe by its policy. Optimistic effects wait for the proven commit frontier.

For mobility feedback, send a timestamped `LinkCostChanged` event to the relevant
model state or query service; include that service in rollback/GVT participation.
Use committed publication for external Kafka/Redis consumers. Disabling routing
feedback is useful for isolating a test, but does not validate dynamic routing.

Represent dynamic entities as logical lifecycle state. A speculative child may
process cancellable events, but physical creation must not make unlogged initialization
effects irrevocable. Cancellation retires its speculative incarnation; old messages
cannot recreate it as a fresh entity. As a simpler first migration, precreate inert
LP shells and make activation an ordinary timestamped event. Announce all deferred
activation bounds to GVT. Keep a terminal LP available for rollback until retirement
is committed; only then passivate the Pekko entity.

The commit coordinator must send a barrier to every sink and wait for a flush ACK
covering all effects in that barrier before reporting durable completion. Either
serialize writes/barriers through a single ordered sink owner, or account for every
producer channel. If crash/retry delivery is supported, sinks also need idempotent
effect IDs; the current actor transport does not imply exactly-once durability.

### Model and protocol obligations

Model adapters must round-trip representative populated state through the actual
checkpoint codec, including numeric map keys/values and nested collections. Empty
collection snapshots cannot reveal erased Scala/Jackson element types. The Node
counter regression in the distributed follow-up demonstrates this: an apparent
`Map[Long, Int]` could restore boxed Long values and fail only on replay arithmetic.
Keep model-specific codec annotations in model state, without adding model fields
to core snapshots or changing the global numeric policy for unrelated models.

Keep the repository's conditional reply discipline. A metrics notification is
fire-and-forget. A request needed for consistent state gets a reply on every branch,
including no-op/error branches. The runtime tracks unresolved obligations and
correlation IDs, but does not invent an extra reply round-trip for every message.

Do not let a reply require new work from the actor already waiting for it. During
rollback the obligation registry is restored with model state; surviving requests
retain their correlation token and undone requests are cancelled. Late replies are
matched by request instance, not by the current status field alone.

Use LP/event-owned random streams or counter-based draws keyed by run seed, stable
LP ID, logical event key, and draw ordinal. A process-wide seeded RNG can still be
order-dependent. Avoid wall-clock deadlines for model decisions; bound route work
by expansions and make environmental input a logged event or journaled immutable
observation. Separate diagnostic CPU timing from any value that affects decisions.

## Implementation order and acceptance gates

Validate each mode independently before comparing them. All comparisons below use
separate simulation runs; a run never contains both synchronization policies.

| Stage | Concrete changes | Evidence required before moving on |
|---|---|---|
| 0 — foundations (this patch) | Common reporting gate; retained cancellation; duplicate-input index; per-pass dispatch exclusion; empty-map replacement; generic reads and snapshot hooks; Link control state; prune floor and numeric underflow fixes. | Focused regressions and full existing test suite. These tests do not certify distributed Time Warp. |
| 1 — conservative correctness | Define stable event ordering, one ordered input queue, identified self-events, and accounting for interaction-driven work and reply obligations. Fix premature idle termination in conservative runs. | A small conservative-only scenario completes every expected trip and transition and matches a sequential reference, including delayed activation and same-tick cases. |
| 2 — optimistic LP correctness | Add complete snapshots, deterministic replay, cancellation, typed LP/run/incarnation identity, and schedule revisions on the same event contract. Adapt existing `act*` handlers. | Optimistic-only controlled tests restore the exact expected state after injected rollback, delayed spontaneous events, and anti-before-positive delivery; Person replay divergence is resolved. |
| 3 — optimistic coordination and GVT | Own every LP and positive/anti/lifecycle send; implement quiescent snapshot rounds with producer bounds. Replace plateau success and heuristic commitment. | Optimistic-only distributed tests account for delayed/reordered delivery through real serializers/sharding; no commit below pending channel work; no termination with a blocked obligation; monotone round results. |
| 4 — effect/lifecycle transaction | Move reports, feedback, activation, retirement, and sink drain behind runtime effect records. Migrate completion guards and station pending state. | Validate each mode separately. Optimistic rollback across trip completion, activation, child messages, and retirement leaves no ghost actors or premature output. Both modes acknowledge durable sink flush before successful completion. |
| 5 — separate-run comparison and model extensibility | Run identical inputs once with conservative mode and once with optimistic mode, then adapt a second small model unrelated to mobility. | Each run independently satisfies expected outcomes; committed state/output agree between the two runs for both models. |
| 6 — bounded memory and scale | Enable strict-boundary fossil collection, reclaim input indexes/tombstones, commit to passive LPs, add bounded dispatch work and speculation/memory budgets. | Long bounded runs demonstrate stable retained memory after GVT; no LP starvation; counters distinguish executed, undone, replayed, and committed events. |
| 7 — asynchronous optimization | Replace paused GVT with a proven nonblocking epoch protocol if measurements justify it; tune checkpoint intervals and partitioning. | Preserve all correctness tests; publish rollback rate, memory, GVT latency, committed events/s, and speedup against a separate conservative run of the same workload. |

Per-event full snapshots are a useful initial/debug reference for small models.
Periodic snapshots plus coast-forward are a reasonable production choice after
state completeness and deterministic replay are proven. Reverse computation,
adaptive cancellation and online shard migration should
follow that baseline rather than expand the first correctness milestone.

## Validation record

See [TIME_WARP_VALIDATION_2026_10_22.md](TIME_WARP_VALIDATION_2026_10_22.md) for commands,
observed results, and the limits of the checks performed for this patch.

## Implementation status — accounted runtime, 2026-10-23

Stages 2–4 now have a concrete reference implementation for fixed/preloaded LPs,
strict report commitment, and reversible terminal intent. The implementation uses
`core/runtime/accounting`, composed with the existing actor/history adapter; the
`SimulationModel` and `SynchronizationPolicy` interfaces above remain target APIs.
No mobility imports were added to core. Model validation rejects unsupported effects
before registration, and model snapshot/read hooks remain the adaptation boundary.

Stage 3 now has a bounded [two-JVM campaign](MULTI_JVM_POLICY_VALIDATION_2026_10_24.md);
multi-host partition coverage remains open. Stage 4 now has cancellable
model birth under the restricted identity contract in RUNTIME_ACTOR_BIRTH.md;
timestamped feedback and real Kafka/ClickHouse integration tests remain open. Conservative closure and sink drain now have their own
[distributed LTM protocol](CONSERVATIVE_CHANNEL_RUNTIME.md); they do not use
optimistic rollback or commitment. Stage 5 still requires a second unrelated
model. Passing the small mobility cases is evidence for those cases only.

See [the accounted protocol](TIME_WARP_ACCOUNTED_RUNTIME.md) and
[its validation record](TIME_WARP_ACCOUNTED_VALIDATION_2026_10_23.md). The earlier
validation documents preserve before/after evidence from the preceding milestones.
