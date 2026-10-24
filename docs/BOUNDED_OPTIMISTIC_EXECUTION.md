# Bounded optimistic execution and committed-history collection

The policy remains fixed for the entire simulation. These controls apply to the
production distributed optimistic runtime; they do not impose an actor-count limit
or switch any actor to another synchronization policy.

## Startup controls

The GTM reads these settings once at startup and sends the same immutable limits
to every LTM and LP. Later registry changes apply to a subsequent run.

| Configuration key under `htc.time-warp` | Environment variable | Default |
|---|---|---:|
| `max-ahead-ticks` | `HTC_TIME_WARP_MAX_AHEAD_TICKS` | 50 |
| `max-uncommitted-events` | `HTC_TIME_WARP_MAX_UNCOMMITTED_EVENTS` | 256 |
| `max-events-per-turn` | `HTC_TIME_WARP_MAX_EVENTS_PER_TURN` | 64 |
| `fossil-collection-enabled` | `HTC_TIME_WARP_FOSSIL_COLLECTION_ENABLED` | true |

The execution ceiling is inclusive: `min(run horizon, GVT + max-ahead-ticks)`, with
saturating addition, additionally capped by accepted progressive input coverage
when its source is open. Before the first cut, the run's start tick supplies the base.
A zero-tick window is valid; negative windows or nonpositive event budgets fail
startup. Queued events beyond the ceiling remain pending and constrain the next
fresh GVT cut. LTMs do not dispatch self-events beyond it, and LPs do not execute
queued positive interactions beyond it.

An LP also pauses execution **above** GVT when its uncommitted-event budget is
exhausted. Work at GVT remains executable even if the budget is exceeded: otherwise
a same-tick causal chain could prevent the very frontier advance needed to release
the budget. Cancellations bypass this execution quota and the speculation ceiling;
they must remain processable to undo work and release retained state.

After the per-turn budget, an LP yields by queuing a local continuation. It keeps
accepting transport, freeze, snapshot and commit messages. No actor thread blocks,
no model event is dropped, and no timer is used to invent completion.

These settings bound speculative execution under an advancing frontier. They are
**not a hard heap-byte cap**: model state, one event's fan-out/observations, input
queues, in-transit messages and bursts exactly at GVT can exceed those budgets.
The implementation does not establish support for one million entities.

## Waiting is not unprocessed computation

The earlier snapshot minimum included the send time of every outstanding business
reply and every retained LTM dispatch slot. A requester at tick 0 could wait for a
trip finishing at tick 1000; its old timestamp would keep GVT at 0 forever under a
small speculation window.

In a frozen, channel-drained distributed cut, accepted self-events are represented
by the actual LP queue. A callback that returned with a checkpointed reply
obligation has completed its current computation; retaining its scheduler slot
prevents redispatch but does not represent an unprocessed tick-0 event. Its future
reply-producing work is accounted in the destination's queue/schedule and channels.

Distributed snapshots therefore use queued inputs/cancellations, pending self
events and creation callbacks as timestamp minima. They retain reply counts as
separate obligations. An LTM with a running dispatch requires its LP to report
either queued spontaneous input or a registered reply obligation. An orphaned
slot fails the run. A global cut with unresolved replies and no queued future work
also fails; it cannot become settled completion. Terminal eligibility explicitly
requires an empty reply registry. A finite horizon may truncate an accounted
continuation beyond the horizon; that is not settled completion.

This relies on the existing freeze and delivery-acceptance proof in
[the distributed protocol](DISTRIBUTED_OPTIMISTIC_RUNTIME.md). It is not permission
to commit from unaccounted mailbox estimates or to omit a pending business reply
from failure/retirement checks. Legacy centralized reference snapshots retain their
previous minimum calculation for compatibility tests.

Resumption is asynchronous across LTMs. A resumed sender may reach an owner still
paused between rounds before that owner's GTM resume command arrives. The owner
accepts and acknowledges that delivery into its still-frozen LP queue; model
execution waits for its own resume. Collection, commitment and retirement phases
still reject unexpected channel traffic. A regression deliberately holds the
receiver's resume while transferring next-round input.

## Safe fossil collection

After an authorized frozen commit, reports strictly below GVT are published, then
the LP collects committed history. An event **at** GVT remains cancellable.

- If an uncommitted suffix remains, retain a checkpoint before its first sequence
  and strictly before GVT, plus the intervening events/read observations needed for
  deterministic coast-forward. A later low-tick checkpoint cannot hide an earlier
  uncommitted sequence.
- If the whole processed prefix is committed, checkpoint the current quiescent
  state and discard the entire event/read log, even for a passive LP that has not
  reached its periodic checkpoint interval. Keep one usable state floor and the
  last scheduling outcome.
- Remove processed-input identities and cancellation tombstones only for ticks
  strictly below GVT. Input below a published GVT already fails the runtime; an
  identity at GVT is retained for duplicate suppression/cancellation.
- Reclaim obsolete dispatch entries still behind live queued input. Do not clear
  pending model events, reply obligations, logical ordering or fresh transport
  counters.

With collection disabled, commitment still rebases the speculative-event budget;
history is retained for controlled comparisons. Collection changes storage, not
the model's committed decisions. It does not provide independent actor recovery
or migration, which remain unsupported.

`[ACCOUNTED-RETENTION]` reports aggregate retained checkpoints, events, read records,
processed IDs, cancellation records, buffered reports and uncommitted event counts
from each fresh pre-commit snapshot. These are retained-record counts, not a heap
measurement. `[OPTIMISTIC-LIMITS]` records the run's chosen controls.

See [the validation record](BOUNDED_OPTIMISTIC_VALIDATION_2026_10_23.md) for boundary
regressions, long-run retained-state checks and physical report comparisons.
