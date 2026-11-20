# Node-local report sinks

`htc.report-manager.placement` (env `HTC_REPORT_PLACEMENT`, startup-only) chooses where an actor's
reports are sent:

| Value | Behavior |
|---|---|
| `cluster-pool` | Reports go to the cluster router pool of the strategy (round-robin routees spread over the nodes). With N nodes about (N-1)/N of the report messages cross the network. |
| `node-local` (default) | Each actor sends to a routee of the same strategy that runs in its own JVM, chosen stably by entity id. |

It mirrors what `node-local-tick` does for the time manager (see
[NODE_LOCAL_TIME_MANAGER.md](NODE_LOCAL_TIME_MANAGER.md)): the unit of placement is the JVM. Single-node
mode ignores it (every sink is already local).

## What changes and what does not

Only the destination changes. The pools, `number-of-instances` / `number-of-instances-per-node`, the sink
actors, `DeliveryAccepted` acknowledgements, `FlushRuntimeReports` and the drain at shutdown are the
existing ones; the time managers already record the acknowledging sink (the real routee) for the drain, so a
report sent to a local routee is flushed like any other. Output files keep one writer per routee.

Every actor resolves the local routees once, lazily and without blocking: `NodeLocalReporters` asks the
router for its routees (`GetRoutees`), waits until the expected number exists (the configured total capped by
`number-of-instances-per-node` times the Up members), keeps those whose address is this JVM's, and picks one by
`hash(entityId)`. Until then, or when this JVM hosts no routee, the router itself is used, so a report is never
lost or delayed by the resolution. When the first resolution completes the JVM logs
`[REPORT-PLACEMENT] node-local: router=... local sinks=k of n`.

## Sizing

`number-of-instances-per-node` is the number of local sinks per JVM. The default of one per node means every
actor of a JVM writes through one sink; raise it (and `number-of-instances` to nodes x per-node) if a single
sink per JVM becomes the bottleneck. A strategy whose `number-of-instances` is smaller than the node count leaves
some JVMs without a local sink; those keep using the router.

## Limits

- A local sink that stops is not replaced; the run fails as it does with the pool.
- No per-actor ordering guarantee is added or removed beyond what one sink per actor gives; reports of one actor
  now always reach the same sink.
- The effect on cross-JVM traffic has **not** been measured: it was made the default on the reasoning above
  (a sender no longer reaches the remote router, and the local routee needs no serialization). Compare with
  `scripts/scenario/benchmark_node_local_scheduler.py --compare reporters` (paired two-JVM runs, `reporting` category of
  `RemoteTrafficInstrument`) on a scenario its mobility validator accepts. Select `cluster-pool` to restore the previous routing.
