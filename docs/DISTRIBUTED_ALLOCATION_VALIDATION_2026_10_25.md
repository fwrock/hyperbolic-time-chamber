# Allocation authority across JVMs — 2026-10-25

The controlled allocation/restore protocol now runs over independent Pekko
processes without sharing `ShardAllocatorRegistry` or allocator command maps.
The [design contract](DISTRIBUTED_ALLOCATION_DESIGN.md) defines ownership and ACKs.
Custom balancing remains disabled by default.

## Implementation and failure semantics

`AllocationRuntime` is an ActorSystem extension. On enabled nodes it starts an
authority singleton manager/proxy and a node client before region creation.
`ActorCreatorUtil` installs a per-type `RemoteShardAllocator` on every enabled
JVM. Node clients register real region references, addresses, and incarnations;
ACKs gate successful callbacks. Disabled nodes retain their existing path and
the extension stays passive.

The authority owns callback matrices and explicit placement intents. Both native
allocation and rebalance callbacks cross its protocol, regardless of which JVM
hosts the active Pekko coordinator. Callback rows use typed records rather than
ActorRef JSON keys. Sequence numbers reject stale index updates, and actor-type
names scope all placements. Repeated rebalance callbacks keep returning an
eligible intent until observation and correlated controller retirement; losing
one callback reply cannot consume the command.

LoadBalanceManager binds its controller incarnation to the authority epoch before
announcing readiness. Preflight is asynchronous. Installing all physical types
of a logical move is one authority operation, and its ACK precedes monitoring.
After placement and store-accepted restores, acknowledged retirement precedes
local admission completion and window closure. The control actor mutates its
state only in receive handlers; adapters hold transport handles and callback
sequence counters, not placement maps. A changed authority epoch, conflicting
registration/controller, or callback-owner replacement fails closed. This is a
fixed-run, stable-membership protocol, not recovery after those replacements.

## Reproduced gaps

The old singleton manager registered its allocator only in its own JVM, after
regions could already have captured default strategies. The new enabled factory
path no longer reads that registry as authority. The separate-process test
requires the registry to remain empty in both processes.

The first launch in `/tmp/htc-allocation-authority-20261025-1/` moved the physical
shard but did not recreate its idle entity: no buffered input existed to cause
activation. It timed out awaiting normal resume and is rejected evidence. The
controller now sends native `StartEntity` requests for the expected cohort after
fresh placement evidence. A start ACK does not replace SnapshotManager's restore
confirmation. MigrationWindowSubscriber also now creates its proxy directly,
without a blocking actor lookup. Normal cluster member transitions are consumed
instead of throwing MatchError and restarting the bound controller during teardown.

## Separate-process method

The launcher starts two owned JVMs with independent ActorSystems, static
registries, and cold codecs. The worker starts first, so its JVM owns the actual
shard coordinator and allocation authority. The harness pins a production
LoadBalanceManager actor in the second JVM to test location independence; it
does not test that controller's singleton election or full SimulationManager/API
startup. Every node uses the same compiled classpath and configuration.

The fixture uses a single `DestinationCounterActor`, state value 23, and hash
shard ID 353 for entity 801. Only the controller fixture installs its known entity
membership; the worker's spatial registry stays empty. Hash routing agrees across
JVMs without hiding the separate distributed spatial-directory gap.

The fixture waits for real region registration ACKs and callback cohorts before
loading its entity. PubSub readiness probes establish actual reachability to both
window subscribers. Window-open/close ACKs, source final save, physical handoff,
target startup query, snapshot application, store confirmation, placement
observation, and intent retirement then use production handlers. The target query
is automatic through the real JVM-local migration flag. Only the conservative
pause/safe-boundary exchange uses a TestProbe; this is not a running traffic
scenario or proof of the Time Manager's safe cut.

Acceptance requires separate PIDs, one shared authority epoch, the controller and
callback owner on different addresses, no local allocator registry in either JVM,
actual source absence and target ownership, restored value 23, both window flags
closed, and exactly one normal resume. Fresh output directories prevent reuse of
old results. The launcher preserves commands, classpath content hash, JSON
evidence, and execution-log prefixes before terminating only its own children.
Full logs retain subsequent teardown separately.

```bash
sbt 'Test / compile' 'export Test / fullClasspath'
python3 scripts/scenario/run_allocation_authority_validation.py \
  --output /tmp/htc-allocation-authority-new-run
```

The first accepted development launch is
`/tmp/htc-allocation-authority-20261025-2/`. It records controller PID 1072833 at
`pekko://AllocationAuthorityJvmProbe@127.0.0.1:39645` and worker PID 1071436 at
`pekko://AllocationAuthorityJvmProbe@127.0.0.1:45465`. The worker is both callback
owner and restored owner; the value is 23, both flags close, both allocator
registries are absent, and worker spatial membership is zero. That development
manifest predates the content-hash guard added to the launcher; final validation
is recorded separately below.

## Final acceptance

Final source checks pass on 2026-10-25: **63 focused tests in ten suites**, full
`sbt test` with **712 tests in 144 suites**, `git diff --check`, and Python launcher
compilation. The final process launch is
`/tmp/htc-allocation-authority-20261025-3/`.

| Evidence | Observed value |
|---|---|
| Controller process / address | PID 1319151 / `pekko://AllocationAuthorityJvmProbe@127.0.0.1:36297` |
| Worker process / callback owner | PID 1319050 / `pekko://AllocationAuthorityJvmProbe@127.0.0.1:36623` |
| Restored owner | Worker address above |
| Shared authority epoch | `2b91d9f9-119c-4341-bd3c-325be8bbb196` |
| Controller / worker client incarnation | `4a3bd33a-372d-42d3-93d1-c5d8c4b71469` / `c2d145d1-e074-4b66-beb6-6ae984ec3dc1` |
| Restored value | 23 |
| Local allocator registries | Absent in both JVMs |
| Worker spatial entries | 0; hash routing fixture only |
| Migration flags | Closed in both JVMs before normal resume |
| Engine errors / main exceptions | 0 / 0 in each execution-log prefix |

`manifest.json` retains exact launch commands and these hashes:

```text
classpath contents: 70ef1e8f6fc34ced3554acada6e0ab51eafdd3c78a64de1752e0a4211b4abc98
launcher source:    1096f63f1a7cbe2878c900bd8cf44f298d1fdbe2077f9be069c3af664ef827a1
```

The launcher hashes the classpath before and after execution and rejects a
change. `result.json`, `worker-ready.json`, and both `*-execution.log` files are
captured before teardown. Full logs preserve later shutdown separately. These
are acceptance facts for this fixed protocol fixture, not performance or traffic
validation measurements.

## Remaining boundaries

- This is a protocol fixture using a pinned controller actor and explicit pause
  probe. It does not establish full conservative traffic simulation with migration.
- Entity-to-spatial-shard assignments and complete live cohort discovery are
  still JVM-local. Non-hash spatial migration and progressive membership need
  their own acknowledged distribution protocol and separate acceptance.
- Authority/controller/callback-owner restart, cluster membership replacement,
  lost process recovery, split brain, durable intents, and repeated runs in the
  same ActorSystem are unsupported. Use one fixed run per cluster.
- Accounted conservative and optimistic migration are not enabled. Existing
  fixed-placement distributed execution keeps its prior runtime boundary.
- Metrics discovery, physical workload measurement, quadtree refinement, and
  classification gaps remain separate. This is correctness evidence, not a
  migration throughput, pause-latency, or novelty result.
