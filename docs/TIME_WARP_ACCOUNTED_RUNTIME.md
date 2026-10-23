# Accounted optimistic runtime — reference implementation

Implemented 2026-10-23. This implements part of the architecture in
[TIME_WARP_EXTENSIBLE_RUNTIME.md](TIME_WARP_EXTENSIBLE_RUNTIME.md). One policy is
selected before startup for the whole simulation and remains fixed throughout.
Pausing a run does not change its policy.

## Ownership and transport

`AccountedRuntimeCoordinator` owns a run UUID and a fixed set of Local Time
Managers. Every initialized LP, including passive infrastructure, registers its
full model class, numeric ID, concrete actor reference, incarnation UUID, and
initial wake. Initialization is acknowledged only after runtime registration.
The coordinator assigns exactly one LTM owner. Numeric IDs may repeat across
models: private scheduler handles isolate the legacy Long-keyed LTM indexes.
Logical ordering continues to use the original model identity, never those handles.

Positive interactions, anti-messages, spontaneous dispatches, and schedule controls
cross the coordinator. It records a delivery token before forwarding a
`RuntimeDelivery`. An LP acknowledges only after accepting the input into its
ordered input or cancellation queue; an LTM acknowledges after applying its control
update. A dispatch that becomes obsolete while already queued is found through a separate
dispatch index and acknowledged before the known-earlier-self gate, even behind
another input: otherwise it can retain the exact LTM slot needed by its
replacement. An unknown destination remains pending. Direct unaccounted model input
fails an accounted run. Raw actor destruction is also rejected.

A late reply is ordered before applying model staleness checks. Request identity
in the speculative suffix cannot justify dropping that input before rollback;
only transport cancellation/duplicate identity can bypass it.

Transport identities contain model, entity ID, run, incarnation, and a fresh send
counter. Anti-messages retain the exact original identity. Logical `EventOrder`
uses tick, causal microstep, and a stable parent/ordinal-derived key; it remains
independent of transport retries and placement.

## Fresh safe-time rounds

The heartbeat requests a round; elapsed time and repeated idle reports cannot
prove progress. The round has these explicit transitions:

```mermaid
sequenceDiagram
    participant C as Accounted coordinator
    participant P as Every LP and LTM
    participant S as Actual report sinks
    C->>P: FreezeRuntime(round)
    P-->>C: RuntimeFrozen(round)
    Note over C,P: Mailboxes accept deliveries; model execution is paused
    Note over C: Wait for every recorded delivery to be accepted
    C->>P: CollectRuntimeSnapshot(round)
    P-->>C: Pending minimum, reply obligations, callbacks
    C->>P: CommitRuntime(round, GVT)
    P->>C: Reports with tick strictly below GVT
    C->>S: Committed report delivery
    S-->>C: Accepted into actual sink buffer
    P-->>C: RuntimeCommitted(round)
    Note over C: Eligible terminal LPs may now retire
    alt More work
        C->>P: ResumeRuntime(round)
    else Settled completion or horizon
        C->>P: Retire LPs / stop managers
        C->>S: Flush barrier through ReportManager
        S-->>C: Storage completion or failure
        P-->>C: Retirement acknowledgements
        Note over C: Publish successful result only after both barriers
    end
```

All participants must acknowledge freezing and every pre-freeze routed send must
be accepted before snapshots are collected. This relies on Pekko's per-sender
message order: each participant's earlier routes precede its freeze ACK, and each
coordinator delivery precedes its later snapshot request. Mailbox processing stays
nonblocking. A new route or member during collection invalidates that round.
Reports are accepted only for the current round and fixed membership.

A spontaneous callback with a registered reply obligation may finish later from
its reply handler; its dispatch remains in flight. Returning without completion
or a registered wait fails explicitly instead of synthesizing a later wake.

The minimum includes LP queued positives/antis, pending self-events, unresolved
reply send times, initialization callbacks, scheduled LTM work, and actual
in-flight dispatch times. Declared creations keep a cut open until settled. The
production baseline fully loads progressive inputs **before** execution: a loader
window's requested upper limit is not a proof that future input is closed.
External/live producers need an explicit lower-bound and closure adapter.

The frontier is monotone, and **only event time strictly below GVT commits**. An
event at GVT can still be cancelled or reordered. Unexpected input below published
GVT, lost participants, failed reconstruction, and independent actor restart fail
the run; they never lower the frontier or become successful idle termination.
An LP input delivered to a registered process with null model state also fails the
run before the delivery is acknowledged. It indicates initialization loss or an
invalid late delivery to a retired sharded entity; silently discarding it would
lose an anti-message or model interaction while reporting apparent success.

With no live work, normal completion uses `Long.MaxValue` as the frontier sentinel.
With only work beyond the finite inclusive horizon, commitment reaches
`horizon + 1` and the result is explicitly `horizon-truncated`. LTMs retain future
self-events without dispatching them; future interactions remain bounded at the
coordinator. A reply obligation at or before the horizon still prevents completion.
Truncation does not invoke model `onDestruct` to synthesize extra model events.

## Reversible completion and output

`selfDestruct` and destructive spontaneous completion record a checkpointed
terminal intent and cancel future self-scheduling. They do not stop a speculative
LP or flush its future reports. Rollback restores terminal intent with state.
At a cut, a terminal LP is eligible for physical retirement only when both its terminal
event and its latest processed event are strictly below GVT and it has no pending work.
A later interaction can extend speculative history after terminal intent was recorded;
that history and its reports must survive until GVT passes them too. The LP accepts an ordinary
retirement command only from the coordinator and only for the same committed round
that granted eligibility. Final-cut retirement is separately marked by the
coordinator because horizon truncation may intentionally leave future work beyond
the requested horizon. Physical retirement performs no new model transitions.

Every reporting destination uses the same speculative buffer, replay suppression,
and rollback discard rules. The coordinator is the sole producer of committed
reports sent to the reporter pools. It waits for actual routee acceptance, records
those concrete sink refs, and sends a final drain to those sinks. This avoids a
barrier racing ahead through a different pool routee or producer channel.

JSON/CSV flush buffers and force file contents; Parquet closes its footer and forces
the file. Kafka waits for broker acknowledgements (`acks=all`, idempotent producer),
and ClickHouse waits for schema and batch-write completion. Async completions return
through `pipeTo(self)`; actors do not block on futures. Failure does not acknowledge
a successful drain. These are storage-acknowledgement contracts, not a guarantee of
exactly-once recovery after coordinator/process failure. Such recovery needs stable
effect IDs, durable runtime transactions, and sink deduplication.

## Hybrid traversal reply ownership

The policy selects event scheduling and commitment; the model's MESO/MICRO mode
selects the traversal protocol. Both policies use the same model protocol:

| Mode | Traversal completion | Sender's obligation |
|---|---|---|
| MESO | `LeaveLinkData` receives `ReceiveLeaveLinkInfo` | Wait for this reply before completing the trip or entering another link; it supplies the distance accounting. |
| MICRO | The Link proactively sends `MicroLeaveLinkData` | Account distance immediately; the later `LeaveLinkData` is cleanup and requires no second reply. |

`Movable.leavingLink` uses the checkpointed `movableLinkExitReported` marker to
distinguish these cases. `enterLink` resets the marker for each new link. A pending
MESO leave request registers its correlation key and finishes without a next wake;
the reply handler consumes that obligation, accounts the link, and completes the
journey or schedules the next link. An intermediate reply must never clear timing
metadata already belonging to a newer link. Car signal handling initiates departure;
the completion path decides terminal intent after traversal accounting.

Car SUMO `routeLength` and the journey-distance metric subtract the checkpointed
trip-start odometer. Vehicle `distance` and `total_distance` fields remain cumulative;
person trip distance and SUMO route length describe one trip. The bounded MESO
validation covers person-owned Cars, static routing, and unsignaled links. Other
vehicle adapters and controlled signals require their own scenario validation.

## Model adaptation and supported scope

Core remains independent of mobility. Existing model handlers use:

- Complete state or versioned `captureModelSnapshot` / `restoreModelSnapshot` hooks.
- `EventReadJournal` at immutable semantic observation boundaries.
- Runtime send, self-scheduling, report, and logical retirement APIs.
- `validateRuntimeCapabilities` to reject unsupported effects before registration.

A journal reproduces observed values during coast-forward; it cannot repair causal
feedback written speculatively into a shared cache. The hybrid adapter therefore
requires `htc.routing.cache-strategy = "disabled"` (also `none`/`off`) for static
routing in the accounted path. It rejects shared InMemory/Kafka feedback until
link-cost changes are timestamped model events or committed external effects.

Live `spawnDynamicActor` is rejected. `BusStation`/`SubwayStation` fail validation
because their existing birth paths lack cancellation and incarnation ownership.
Waiting for GVT to pass a dequeue tick and then creating a child at that old tick
is unsafe: the child introduces new work below the frontier. Precreate inert LPs
and activate them with logged events, or implement a cancellable birth adapter with
announced lower bounds, initialization effects, child-message cancellation, and
retirement rules. The creation ledger alone does not implement this adapter.

The validated model population is eagerly loaded plus fully preloaded progressive
LPs, static routing, cars/persons/links/nodes, and disabled migration. Other model
features require their own state/effect audit and expected-outcome tests.

## Costs and remaining architecture work

This is a centralized reference protocol. Every modeled send adds coordinator work
and an acceptance ACK; safe-time rounds pause all participants. Some current
membership lookups are linear. Histories, processed-input indexes, and cancellation
tombstones remain unbounded; strict-boundary fossil collection is not enabled.
There is no throughput, city-scale, or distributed-recovery claim. Optional
`HTC_TIMEWARP_TRACE_ENABLED=true` snapshots can expose pending events and obligations
when a frontier remains unchanged; diagnostics never resolve those obligations.

Remaining work includes multi-node transport tests; cancellable birth and input
producer adapters; timestamped feedback; durable effect retry; bounded histories
and speculation; a second unrelated model; and measured performance. A later
asynchronous epoch/color GVT implementation must preserve the same channel and
membership invariants. Conservative independent same-tick channel closure and
acknowledged durable completion remain separate migration work. No mixed-policy
run or runtime policy switching is required by this architecture.

## Conservative same-tick closure follow-up

The conservative `SelectiveBarrier` is not a channel-closed barrier. Each LTM
reports its next scheduled tick and whether its local dispatched work is complete.
The GTM then advances when every registered LTM has reported, but an interaction
sent directly through a sharding region is outside those reports. In particular,
the sender's `FinishEvent` reaches its LTM independently of the interaction's
delivery to the receiver. `ConservativeGlobalTimeManager.handleLocalTimeReport`
can therefore release the next tick while an earlier-tick interaction is still
in transit. `hasPendingWork` covers registered reply obligations; it does not
count all ordinary fire-and-forget interactions.

This is a source-proven closure gap. However, investigation of the observed
two-link ordering difference found a more direct model-level nondeterminism:
vehicles entering the same MICRO link at the same tick and position are inserted
in message arrival order, and the car-following calculation uses queue order to
choose the leader. Repeated conservative runs with the same jar and seed kept both
link-11 entries at tick 35 but reversed which vehicle exited first. Thus the
observed difference does not establish that an unclosed conservative channel
caused the outcome. The independent closure gap still requires a protocol fix;
do not mask it with a sleep, extra idle poll, or fixed number of barrier rounds.

The immediate reproducible cause was in
`LinkVehicleFlowHandler.handleEnterLinkMicro`: equal-position inserts appended in
arrival order. `DefaultMicroSimulationStrategy.processMicroLane` then treated the
previous queue element as leader, and its post-step sort used position alone.
`VehicleInLane` now defines a shared front-to-back order by descending position,
then ascending logical vehicle ID; entry, each sub-tick, lane changes, and the
legacy micro manager use it. Focused tests cover reversed arrival sequences and
leader selection. Repeated seeded MICRO runs pass strict trip/summary/discard
checks and produce a stable first-link leader, but conservative two-link runs
still vary the tick at which the first vehicle enters link 11 (tick 33 versus 35).
The lane tie fix therefore closes the reproduced equal-position cause without
establishing full conservative determinism or cross-policy event-tick parity.
See `docs/TIME_WARP_ACCOUNTED_VALIDATION_2026_10_23.md` for MICRO results and
`docs/MESO_POLICY_VALIDATION_2026_10_23.md` for the corrected Car MESO distance/timing
checks and remaining same-tick density differences.

The conservative policy needs its own safe-time gateway, selected for the whole
run before actors start. Its minimum protocol is:

1. Route every model interaction through the conservative gateway. Register its
   unique token and logical event order before forwarding it to the destination.
2. Have the destination acknowledge after accepting the interaction into its
   model input path, including any resulting schedule/control update. A delivery
   acknowledgement must not depend on a later reply through the original sender.
3. Include outstanding gateway deliveries and their event times in the barrier
   snapshot. Release a frontier only after all LTMs report and no earlier/equal
   channel delivery remains in flight.
4. Preserve the established same-tick rule: causal sends advance the microstep;
   independent events use the stable logical key. The receiver must queue and
   execute according to that order before the frontier passes it.
5. Treat unknown destinations, lost acknowledgements, and gateway failure as
   explicit run failures. Do not infer channel closure from elapsed time or idle
   LTM reports.

Implement this behind a conservative-run protocol boundary, reusing transport
identity and ordering primitives where appropriate but not enabling optimistic
rollback, GVT commitment, or actor-policy mixing. First add an injected-channel
protocol test that holds one delivery after its sender reports finished and
asserts that the frontier cannot pass it. Then validate with the fixed-seed
scenario and a sequential reference, comparing physical event ticks and state
outcomes as well as trip totals. The existing report-order difference remains
unresolved until those tests pass.

See [validation evidence](TIME_WARP_ACCOUNTED_VALIDATION_2026_10_23.md).
