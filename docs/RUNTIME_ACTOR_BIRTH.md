# Accounted model-created actor birth

Implemented 2026-10-24. This document describes the runtime contract.
See [the validation record](RUNTIME_ACTOR_BIRTH_VALIDATION_2026_10_24.md) for evidence and limits.
One synchronization policy remains selected before execution for the entire run.

## Identity and rollback

`spawnDynamicActor` in the production policies emits an ordinary ordered interaction
carrying initial state and initialization resources. Its target is a typed logical
process ID. The full birth order (tick, causal microstep and stable effect key) identifies the birth slot;
fresh transport IDs distinguish re-executions of that slot. A child ID cannot be
reused by a different logical birth, or collide with a source-loaded actor.

The owning LTM reserves the child with the existing GTM and initializes it through
a short-lived initializer actor. This actor only performs StartEntity / Initialize /
ACK; it does not coordinate simulation time or route model traffic. Ownership must
be installed before the initialization ACK completes. A newly initialized child is
inert and has no spontaneous schedule until its ordered birth positive executes.

Activation replaces the initial model state, interns strings, installs relationships,
performs any runtime post-registration hook and, for a scheduled child, schedules
the first wake no earlier than creation. Passive children remain unscheduled; their
nominal model start tick may be older. Inputs ordered before the birth are rejected. Its acknowledgement to the parent is a normal ordered interaction.
The parent records that reply obligation in its checkpointed registry, rather than
waiting on an actor-local polling flag. Model initializers must keep irreversible
side effects out of initialization; modeled activation effects use runtime sends
and reports.

The birth positive belongs to the creating event's normal sent-message journal.
Rollback therefore retracts it with the same anti-message machinery as any other
output. The child's checkpoint includes its active flag. Cancelling a processed
birth restores its dormant floor and retracts its subsequent messages, reports and
any descendant births. A cancellation arriving first annihilates the positive.
A replacement positive for the same birth slot waits for cancellation of the old
one; it cannot execute twice while the old version is active. Coast-forward replay
uses the same runtime activation dispatch and suppresses duplicate sends.

A dormant cancelled child remains available for positives/antis until GVT strictly
passes its birth tick and the latest tick of any accepted input, and no queued work
or reply obligations remain. This receipt fence is monotone and does not rewind
with model state: a child rolled back to dormancy may still owe a later anti-message
receipt. The fence constrains retirement, not the GVT minimum. Its identity
reservation is not reusable. Ordinary physical retirement still needs the owner's
round-scoped authorization; speculative cancellation never directly stops the LP.

## Transport and frozen membership

Unknown-target traffic can precede initialization. Owners retain those inputs.
Early ordinary/anti receipts transfer responsibility to the owning queue, while
the birth positive's source ledger remains outstanding until the initialized child
accepts it. The owner also tracks the initializer and all resulting LP deliveries.
A drained cut containing unknown inputs without an accounted birth fails explicitly.

Conservative creation occurs during a causal batch. The creating worker cannot
close its outgoing channel before the child accepts the birth. The next fresh
frontier includes the child's activation, ordered after the creating event. An
initialization ACK is not permission to execute the child in an earlier batch.

Optimistic creation can finish while a round is freezing. An owner that already
acknowledged freezing re-enters its draining phase when the birth arrives. It adds
the newborn to its freeze obligations, installs the horizon and fixed execution
limits, and queues inputs into the frozen child. The source cannot acknowledge its
freeze before the birth is accepted. The new child's freeze acknowledgement precedes
that acceptance on the child-to-owner channel. Therefore the final source freeze
causally follows newborn enrollment and freezing, even if an earlier owner freeze
ACK reached the GTM. Fresh collection includes the expanded membership. Enrollment
is rejected during collection, commitment and retirement.

Participant/initializer loss and uncoordinated restart fail the run. This does not
provide coordinated recovery, ownership migration or durable external-effect retry.

## Hybrid adapters

Accounted BusStation/SubwayStation execution requests births at the event that
actually dequeues the vehicle. It does not wait for GVT then create a vehicle at an
earlier timestamp. Departure schedules come from checkpointed model state, and
initialization acknowledgements do not drive polling ticks. Legacy constructors
retain their old behavior separately.

Bus route calculations stay in the actor event's deterministic read journal;
worker-thread route futures followed by `Await.result` are removed. Route computation
still consumes the actor's CPU turn and has its existing search expansion bound.

Initialization resources carry only the policy selected for the parent run. Child
actor types must be registered on participating shard nodes before execution.

Transit validation also exposed model defects independent of policy: a null
creation type on a Person unload reply, metres-per-second subway speeds treated
as kilometres-per-hour, an uncorrelated conservative subway link-exit guard, and
a subway stop barrier that never counted or awaited unloading passengers. The
adapters now correlate both load and unload obligations, and allow either reply
order before scheduling departure. Subway line iteration and route destinations
use deterministic route order. Bus journey counters/completion guards now live in
`BusState`, and restored Bus state retains its proactive link-exit guard. Bus MICRO
exit now goes through signal/capacity handling and normal link cleanup, which
consumes the completed path before another Ready wake. Duplicate/stale exits do
not add distance. The cyclic route's non-consuming peek uses its current cursor,
so signal/capacity requests name the actual next link rather than the route head.

## Supported scope and remaining limits

The contract supports deterministic unique child IDs with one immutable logical
birth slot, cancellable model messages/reports and descendant births. It does not
support recycling an ID for a different creating event, irreversible constructor
side effects, shared speculative routing-cache writes, coordinated recovery or
migration. An initializer is allocated per in-flight handshake; its overhead and
retained birth metadata have not been measured at city scale.

The simulator checks use static routing, file reports and multiple LTMs in one JVM.
They do not certify remoting or durable Kafka/ClickHouse retry. PT person reports
still omit traveled distance, and Subway lacks a journey-completion report; the
validation checks actual boarding/alighting and the train's cyclic rail timetable.
Cyclic buses run to the simulation horizon, where an unfinished final traversal
is explicitly counted as censored rather than a completed link or destination trip.

Horizon truncation can leave a MESO bus without a final journey/SUMO report when
its next scheduled exit is beyond the cap. The runtime does not execute that exit
or invent a destination completion. Uniform model finalization at the horizon
remains a reporting gap, recorded explicitly by the transit validator.
