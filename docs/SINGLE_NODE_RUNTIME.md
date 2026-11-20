# Single-node runtime

`htc.runtime.mode` (env `HTC_RUNTIME_MODE`, read once at startup and pinned per
`ActorSystem` by `RuntimeMode.forSystem`) selects between two runtimes:

- `cluster` (default): Pekko Cluster Sharding, cluster singletons and cluster router pools.
- `single-node`: one JVM with none of that machinery. The simulation, its actors and its
  synchronization protocol are the same; only the transport underneath changes.

This is bounded engineering acceptance on small generated scenarios. It does not establish
city-scale performance or a measured memory difference.

## What replaces what

| Cluster mechanism | Single-node substitute | Code |
|---|---|---|
| Shard region per model type | `LocalRegion`: one plain parent actor per type that spawns entities on first contact and speaks the same protocol (`StartEntity` + ack, `EntityEnvelopeEvent` unwrapping, `Passivate`) | `core/util/LocalRegions.scala` |
| Delivery to an entity | Entity index (`entityId -> ActorRef`) and a plain `tell`, no envelope and no region hop, whenever that cannot reorder the sender's messages | `core/util/LocalEntityRouter.scala`, `BaseActor.sendToEntity` |
| `ClusterSingletonManager` / proxy | Ordinary top-level actor. The proxy is the singleton's own `ActorRef` when it already exists, or a buffering forwarder when it does not yet | `core/util/LocalSingletons.scala` |
| `ClusterRouterPool` | Plain `RoundRobinPool` | `ActorCreatorUtil.createRouterPool` |
| Mode switches | Concentrated in `ActorCreatorUtil` (`createShardRegion`, `createSingletonManager`, `createRouterPool`) and `DistributedUtil` (`getShardRef`, `createSingletonProxyUnder`) | `core/util/` |

The node still joins itself as a one-member cluster (`cluster.join(cluster.selfAddress)`), so
code that reads `Cluster(system)` keeps working. Artery still binds its TCP port, so
`CLUSTER_PORT` is still required. Local `tell`s do not go through Artery.

`htc.load-balance-manager.enabled=true` is rejected at startup: there is no second node to
balance to. `AllocationRuntime` only creates its cluster singleton behind that flag, so it is
unreachable in this mode.

## Entity delivery

`BaseActor.sendToEntity` asks the sender's `LocalEntityRouter` for a decision on every send:

1. Target in the entity index and the pair not pinned: `target.tell(payload, sender)`. This is
   the steady-state path; it reads a `ConcurrentHashMap` and never touches the region mailbox.
2. Otherwise the payload goes through the region as an `EntityEnvelopeEvent`.

A sender that ever routed to an entity through the region keeps using the region for that
entity (a pinned pair). Otherwise an earlier message of its own could still be queued in the
region mailbox and a direct one would overtake it. The pin is per sender and per
`(region, entity)` pair.

The region publishes an entity into the index only after it has forwarded the message that
spawned it, and removes it on `Terminated`.

Messages that go to a region without `sendToEntity` are the startup and birth handshakes
(`StartEntity`, `InitializeEvent`, `PostLoadRegistrationEvent`), the digital-twin updates and
`Link.onDestruct`. Each was checked: none of those senders sends to the same entity through
the direct path before the region has delivered, so no ordering hazard exists today. Two cases
rely on convention rather than enforcement: `spawnDynamicActor` callers (`BusStation`,
`SubwayStation`) must not message the child before its initialization ack, and `Link` stops
right after its destruct fan-out.

## Serialization

Local messages are not serialized in either mode (`pekko.actor.serialize-messages` is not
enabled), so Protobuf, Kryo, Jackson or CBOR bindings only matter across a remoting boundary.
Consequences in single-node:

- The gain over a one-member cluster comes from dropping the region hop, the envelope and the
  sharding bookkeeping, not from skipping serialization.
- Messages travel by reference, and a missing serializer binding cannot surface in this mode.

## Metric

`htc_single_node_deliveries_total{region, path}` counts deliveries made through
`sendToEntity` in single-node mode. `region` is the model type (low cardinality) and `path` is:

- `direct`: delivered with a plain `tell`.
- `region_new`: first contact before the entity was published; the pair is now pinned.
- `region_pinned`: a pinned pair, sent through the region.

A growing `region_*` share means the region mailbox is on the hot path.

## Running it

Use `scripts/scenario/run_cluster_validation.py` with one JVM and put the mode in an extra
config:

```bash
echo 'htc.runtime.mode="single-node"' > single.conf
python3 scripts/scenario/run_cluster_validation.py --jar <jar> --simulation <scenario>/simulation.json \
  --output <fresh-dir> --base-port 22000 --nodes 1 --timeout 180 --measure --extra-config single.conf
```

The script writes `htc.routing.cache-strategy="disabled"` and `enabled-strategies=["json"]`.
Both matter for local runs and are independent of the runtime mode:

- Dynamic-routing policies reject the default `inmemory` strategy; use `disabled` or
  `accounted` (the latter needs `RoutingService` sources listed in
  `htc.routing.accounted.worker-ids`).
- The default `enabled-strategies` includes `kafka`. Without Redpanda the report flush waits
  for the topic and the process does not exit after the simulation has completed.

## Results

Same generated scenario in both modes, one JVM, `--measure`, seed 7 (4x4 grid, 12 persons,
3 cars, 3 bicycles, 3 motorcycles, 1 bus line, 1 subway line, 30% MICRO, 4 activities):

| | cluster | single-node |
|---|---|---|
| Validator exit | 0 | 0 |
| Time to completion | 26.8 s | 20.4 s |
| Report rows | 17,717 | 17,717 |
| Event types | 26 | 26, identical counts per type |

One run per mode on a 16-second simulation: an indication, not a measured speedup. Parity was
compared by row count per event type, not by row content. Heap was not compared.

Delivery path, single-node, discrete-event conservative runtime:

| Scenario | Deliveries | `direct` | `region_new` | `region_pinned` |
|---|---|---|---|---|
| 4x4, 12 persons, seed 7 | ~43,000 | all | 0 | 0 |
| 5x5, 60 persons, 15 cars, 3 bus and 2 subway lines, seed 11 | ~200,000 | all | 0 | 0 |

`Bus` and `Subway` are born dynamically in both, so dynamic births do not reach the region
either: every birth path waits for the start and initialization acks before anyone addresses
the entity.

## Limits and open items

- Only the `discrete-event` conservative runtime was exercised. Births of the `accounted`
  runtime (`RuntimeBirthInitializer`, `DirectBirth`) and the Time Warp and optimistic policies
  were not run in this mode, so the pinned path has unit coverage
  (`LocalEntityRouterSpec`, `LocalRegionsSpec`) but no end-to-end evidence.
- No load balancing, no singleton failover: a stopped singleton leaves a dead registered ref.
- Memory was not measured. The index adds one map node per live entity; the per-sender pinned
  sets are the part that can grow, and were empty in the runs above.
- The buffering singleton proxy only matters if some caller asks for a proxy before the
  singleton exists. Whether that ever happens in startup was not checked.
