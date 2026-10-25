# Accounted dynamic routing

`htc.routing.cache-strategy=accounted` makes hybrid road feedback and route decisions
ordinary simulation interactions under the policy selected before startup. It uses
the existing LTM delivery ledgers and ordered LP queues. It adds no time manager,
global routing coordinator, wall-clock expiry or independent asynchronous cache.
The `inmemory` and `kafka` strategies remain rejected by production accounted
policies; `disabled` retains static routing.

## Configuration and membership

```hocon
htc.routing.cache-strategy = "accounted"
htc.routing.accounted.worker-ids = [9000001, 9000011]
htc.routing.link-cost.publish-interval = 10
```

Declare every configured ID exactly once in JSON or SQLite actor sources whose
class is `hybrid.actor.RoutingService`, loading strategy is `EAGER`, lifecycle is
`STATIC`, and creation type is `LoadBalancedDistributed`. Each actor contains
`model.hybrid.entity.state.RoutingServiceState`, normally initialized as:

```json
{"startTick":0,"scheduleOnTimeManager":false,"costs":{}}
```

Startup checks the source membership before creating shards. Missing, extra or
duplicate workers and incompatible source policies fail startup. Worker order and
configuration must match on every JVM. A requester selects one worker by a stable
hash of its numeric entity ID; each Link publishes to every configured worker.
These are model LPs enrolled through the normal source protocol, not pool routees.

## Feedback, queries and replies

Link post-load registration publishes initial costs only after EAGER actors exist.
Later MESO occupancy changes and MICRO global ticks publish `LinkCostChangedData`
at the configured tick interval. Each update contains a numeric link ID, positive
finite weight and observed tick, which must agree with the event source and tick.
The worker checkpoints the weight and full `EventOrder` in `RoutingServiceState`.
Unobserved links fall back to immutable topology weights; the last accepted cost
remains valid until another ordered update replaces it. There is no wall-clock TTL.

Dynamic costs use consistent speed units. MICRO lane velocities are m/s while
MESO/configured link speeds are km/h. Cost publication normalizes them to m/s and
treats an empty MICRO link as free-flow traffic, retaining the stopped-traffic
penalty only while vehicles remain.

Persons defer pending mode choices and PT-timeout replans to their selected worker.
Vehicles without precomputed paths send route queries there. Each query receives a
correlated result, including no-route/no-viable-journey results. Workers calculate
against their own checkpointed cost view through a synchronous scoped lookup.
Shared positive and negative route caches are bypassed for dynamic route searches.
Fixed transit service routes continue to use static topology.

The requester records its pending decision in model state and the exact send
sequence in the existing checkpointed awaited-reply registry. It resolves its
current scheduling slot with `onFinishSpontaneous(None)`; the recorded reply
obligation prevents settled completion while the causal reply remains outstanding.
Only a reply matching the current worker and send sequence consumes that obligation
and advances the trip. PT boarding supersedes a pending timeout replan. No polling,
model-reply watchdog or extra Time Manager round-trip is added.

## Rollback and commitment

Updates, queries and replies cross the same positive/anti transport as other model
events. A late update can roll a worker back before a query. Cancellation restores
its earlier cost state, retracts affected replies and reprocesses surviving queries;
downstream requesters can then roll back their affected decisions. Coast-forward
replay retains the existing read journal and send identities. Reports still commit
strictly below GVT through the existing sink receipt/flush protocol.

`RoutingServiceRollbackSpec` verifies that cancelling an update restores the previous
causal version, emits the anti for the old reply and produces a replacement reply.
Checkpoint tests preserve worker costs and pending requester state; codec tests
decode routing payloads with independent cold writer/reader systems.

## Scope and costs

Each worker stores the full observed road-cost map, so map storage and Link fan-out
grow with worker count. Existing full JSON checkpoints can copy that map repeatedly;
this implementation makes no city-scale memory or throughput claim. Worker
membership is fixed for a run. Cost publication remains sampled (10 ticks by
default), and this adapter does not reroute a vehicle already following a path.
Independent recovery, shard migration, arbitrary external live feedback and dynamic
worker replacement remain unsupported. The bounded validation record is
[here](ACCOUNTED_ROUTING_VALIDATION_2026_10_24.md).
