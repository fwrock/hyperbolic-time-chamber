# Node identity and per-node message accounting

Purpose: study distributed runs. For every actor-to-actor message we want to know which node sent it and which node
received it, count same-node vs cross-node traffic, and keep that information in Prometheus and in the reports, so
different distributed executions (allocation approaches, cluster sizes) can be compared and explained.

## Naming a node (nothing to configure per node)

`core.cluster.NodeIdentity` is a JVM-wide static: every actor on the node reads it.

- **Rank**: position of the node in the cluster membership, members sorted by host then port. Every node sees the same
  member set, so every node computes the same table. The table is computed while the cluster forms and **frozen** when
  `pekko.cluster.min-nr-of-members` members are Up (the simulation does not start earlier). `min-nr-of-members` must equal the
  real cluster size; a WARN is logged if the cluster grows after the freeze. `HTC_NODE_RANK` forces a node's rank (for
  example a StatefulSet ordinal) when names must be stable from run to run.
- **Label**: chosen only by strategy, `htc.node.label-strategy` / `HTC_NODE_LABEL`:
  `rank` (`node-0`, `node-1`, …), `animals` (Onça-pintada, Capivara, Tamanduá-bandeira, …),
  `cities` (Pereiro, São Paulo, Recife, …). Lists have 32 entries; beyond that a suffix keeps names unique (`Pereiro-2`).
  All nodes must use the same strategy (the label is derived locally from the rank).

Startup log: `Node identity frozen: rank=0 name=node-0 strategy=rank members=1 table=0=node-0@<address>, …`.

## What travels with a message

`ActorInteraction.senderNode` (proto field 23, `uint32`) = sender rank + 1; 0 means unknown, and proto3 writes zero as no bytes.
Only the number is sent; the receiver derives the label from its own table. Cost on a stamped message: 3 bytes (2-byte tag,
1-byte varint). `ActorInteractionEvent.senderNode` defaults to `NodeIdentity.stamp` at construction, i.e. it is set on the
sender. Both serializers carry it (standalone and the fused `EntityEnvelopeEvent` frame); Kryo-carried runtime deliveries
include it as a normal case-class field. `htc.node.message-origin = false` (`HTC_NODE_MESSAGE_ORIGIN=false`) disables
stamping (the receiver then counts the traffic as `unknown`).

## Metrics (Prometheus, port of `HTC_METRICS_PORT`)

- `htc_messages_by_node_total{from_node, to_node, locality, event_type}`: interactions received by this node, by the node that
  sent them and the **message type** (the event type, e.g. `MicroUpdate`, `EnterLink`, `RequestLinkAccess`).
  `locality` is `local` (same node), `remote`, or `unknown` (unstamped sender). Anti-messages (Time Warp control traffic) are not counted.
  Cardinality: observed N × N × types series; the `event_type` label is capped at 64 distinct values (the rest is `other`).
- `htc_messages_by_class_total{from_type, to_type, locality}`: the same traffic by actor class (Car → Link, Link → Car, …): which kinds
  of actors generate the cross-node traffic.
- `htc_node_info{node, rank, address, strategy} 1`: this node's label, rank, cluster address and strategy.

## Reports

Every `ReportEvent` now carries `node` (the node of the reporting actor), `fromNode` (the node that sent the message being
handled when the report was produced), `messageType` (that message's event type) and `fromType` (the sender's actor class); all
empty for reports made inside spontaneous events. Written as four columns at the END of each record, so readers of the old columns
keep working:

| Writer | Columns |
|---|---|
| CSV | `…,data,node,from_node,message_type,from_type` |
| JSON | keys `node`, `from_node`, `message_type`, `from_type` (empty string when none) |
| Parquet | nullable string columns `node`, `from_node`, `message_type`, `from_type` |
| ClickHouse | `node`, `from_node`, `message_type`, `from_type` `LowCardinality(String)`, added with `ADD COLUMN IF NOT EXISTS` to existing tables |
| Kafka | **not included**: its Avro schema is governed by the schema registry and needs a new schema version |

Comparing runs: group the report rows by `(from_node, node)`; `from_node == node` is same-node traffic. For example in
ClickHouse: `SELECT from_node, node, message_type, count() FROM vehicle_link_events WHERE simulation_id = '…' GROUP BY from_node, node, message_type`.

## Managers (singletons and per-node)

`BaseManager` instruments every manager (SimulationManager, global/local time managers, ReportManager, LoadBalanceManager, …), so a
new manager is covered without touching it. `htc.node.manager-metrics` / `HTC_MANAGER_METRICS` (default true) turns it off.

- `htc_manager_instances{manager, kind}`: live instances on this node. `kind="singleton"` is 1 on the node hosting that cluster singleton
  (detected from the actor path `<name>/singleton`), `node` for per-node managers. A singleton that already finished (e.g. the load
  manager after loading) reads 0.
- `htc_manager_messages_total{manager, message}` and `htc_manager_message_seconds{manager, message}` (histogram): messages handled and
  time spent per message class (a sharding envelope is unwrapped; at most 200 distinct classes, then `other`).
- Load balancing (`LoadBalanceManager`): `htc_loadbalance_rebalances_total{reason,result}`, `htc_loadbalance_migrations_planned_total{reason,from_node,to_node}`,
  `htc_loadbalance_migrations_total{from_node,to_node,outcome}`, `htc_loadbalance_migration_seconds`. Node labels come from the rank table by cluster address.
- Not done: mailbox depth per manager (needs Pekko internals), and domain metrics for the other managers beyond the generic ones.

## Verified / not verified

- Verified: engine compiles; unit tests (`NodeIdentitySpec`: label lists, uniqueness, identical rank table from any member
  order, wire round trip through both serializers, receive accounting) pass; an end-to-end run of `native-two-corridors` on one
  node produced `htc_messages_by_node_total{from_node="node-0",to_node="node-0",locality="local"} 862`, `htc_node_info`, the
  startup log line, and Parquet reports with `node`/`from_node`/`message_type`/`from_type` (726 rows from messages, 338 spontaneous). The same
  run published `htc_manager_instances{manager="ChannelClosedGlobalTimeManager",kind="singleton"} 1`, per-manager message counts and handling time,
  `event_type` per message and the Car/Link/Node class pairs. Load-balance metrics exist but no migration happens on one node.
- NOT verified: a real multi-JVM cluster (cross-node stamps, remote counting, rank table agreement across processes).
  In a single JVM every message is local and is not serialized, so the wire path is only exercised by the unit test.
  The remote path depends on every node running this build.
