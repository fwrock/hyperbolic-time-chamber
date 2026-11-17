# Node-local tick scheduling

`auto` is the default scheduling strategy. It selects `node-local-tick` for direct
conservative execution with live load balancing disabled, and `cluster-pool` for
Time Warp, `channel-closed` and live load balancing. Explicit `cluster-pool` retains
the previous scheduler; explicit `node-local-tick` rejects incompatible policies. All JVMs must run the same executable and configuration.

## Configuration

```hocon
htc.time-manager {
  scheduling-strategy = "node-local-tick"
  conservative-transport = "direct"
  node-local {
    nodes = 2
    workers-per-node = 3
  }
}
htc.load-balance-manager.enabled = false
```

Equivalent environment variables:

| Setting | Environment variable | Default |
|---|---|---|
| Scheduling strategy | `HTC_TIME_MANAGER_SCHEDULING_STRATEGY` | `auto` |
| Simulation JVM count | `HTC_NODE_LOCAL_SCHEDULER_NODES` | `0` (discover startup cohort) |
| Local worker count | `HTC_NODE_LOCAL_SCHEDULER_WORKERS` | `4` |

These settings are fixed before startup. Zero `node-local.nodes` freezes the current
Up cluster cohort after startup quorum; a positive value must match the fixed
simulation JVM cohort. Configure the startup quorum to include all simulation JVMs. Existing `total-instances` and `max-instances-per-node`
continue to configure the original cluster pool; the new strategy uses its own
node and partition counts. Under `auto`, `workers-per-node` configures the selected node-local scheduler.

## Ownership and storage

The GTM creates one node authority per simulation JVM, with at most one authority
on each node. Registration selects the authority on the actor's actual JVM. The
`RuntimeJoined` handshake supplies that local reference to the actor, so scheduling
does not require a separate process-global registry or a remote pool hop after
registration. The node authority is the local pool front and schedule dispatcher.

Each distinct effective `(typed actor, incarnation, tick)` wake-up gets the next
worker in that tick's round-robin sequence. A duplicate pending wake-up reuses its
assignment. Each worker owns a private `TreeMap` of tick buckets, bounded dispatch
slots and a direct-work ledger. One actor's events at different ticks can belong
to different workers.

The dispatcher stores assignment and acceptance state before forwarding a wake-up.
The worker acknowledges storage before claiming or executing it. Callback completion
is withheld from its accounting worker while schedule-storage receipts are pending.
Worker claims arrive before their grant/settlement receipts, and queued current-tick
directory entries also fence node completion. These rules prevent a transient
empty view during the handoff.

The node authority retains LP registration, dispatch generations, queued startup
input, callback effect validation, report receipts and physical retirement. Root
and descendant accounting belongs to the assigned partition worker. Direct model
messages carry that accounting worker through remote cascades. The receiver's node
applies effects before forwarding accounting completion. Fresh node summaries and
accepted input coverage determine the next GTM grant.

## Wake-up semantics

Independent future ticks are additive: scheduling tick 11 and finishing tick 10
with next tick 12 preserves both future wake-ups. The original strategy keeps its
existing revision/replacement behavior.

A new request at or below an actor's last dispatched tick is normalized to the
following tick. Deduplication uses the effective tick, so the actor still has at
most one spontaneous execution per tick. Exact direct-callback retries are filtered
before their effects are applied and do not create another schedule.

Verified green admission and confirmed zero-delay road/junction continuations use
the existing tracked model callback under this option, rather than request a second
spontaneous execution at the same tick. Red-phase re-verification continues through
the ordinary schedule path. This avoids adding intersection dwell solely because
the scheduler coalesces wake-ups. These adapters are conditional on the runtime
enrollment flag; other strategies retain their existing paths.

## Supported scope and limitations

The first implementation reuses conservative progressive input, startup delivery,
reporting, inclusive horizon and retirement machinery. It requires direct
conservative transport, positive fixed node/worker counts and disabled live load
balancing. Time Warp and channel-closed reject this selection. Model-created actor
birth and live migration are rejected. Runs requiring model-created Bus/Subway
birth must explicitly select `scheduling-strategy="cluster-pool"`; `auto` does not
infer model birth requirements from scenario data. Neither worker replacement nor independent
restart is a recovery mechanism. Unexpected partition loss fails the run.

There is no public cancellation/replacement contract for this additive strategy.
Do not infer support for arbitrary external scheduling or replay from the IDs.
The single dispatcher serializes admission and callback effects and can bottleneck.
Owner publications currently replicate the LP-to-node directory across node
authorities. Per-tick ledger history and queued futures consume memory. Event-count
balance is not a guarantee of equal CPU work or faster execution. No city-scale
throughput, hard heap bound or general speedup has been established. The
[paired single-host pilot](NODE_LOCAL_TIME_MANAGER_BENCHMARK_2026_11_17.md) records
lower simulation time and cross-JVM traffic for one bounded workload.

See the [design discussion and literature references](NODE_LOCAL_TIME_MANAGER_DESIGN.md).
The [bounded validation record](NODE_LOCAL_TIME_MANAGER_VALIDATION_2026_11_17.md)
contains the two-JVM MESO/MICRO results, protocol review and reproduction commands.
The [cross-JVM measurement guide](REMOTE_TRAFFIC_MEASUREMENT.md) explains optional
transport counters and the paired execution-time/message-volume benchmark.
