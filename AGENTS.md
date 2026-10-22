# Hyperbolic Time Chamber (HTC)

Multi-agent discrete-event simulator for urban digital twins. Part of the HTC Digital Twin Platform for mobility and sustainability research (PhD project).

## Stack

- **Scala 3.3.5** — functional style, case classes, enums, extension methods
- **Apache Pekko 1.5** — classic actors (not typed), cluster sharding, persistence (LevelDB/Redis snapshots), Kafka connectors, Pekko HTTP
- **Serialization** — Kryo (default), Protobuf (control plane messages), Jackson (JSON state), Avro (Redpanda/Kafka events), Parquet (output)
- **Broker** — Redpanda (Kafka-protocol compatible, no Zookeeper, built-in schema registry); `pekko-connectors-kafka` connects unchanged
- **Observability** — Prometheus (`io.prometheus`), ClickHouse, structured `report()` calls
- **Build** — sbt + sbt-assembly fatjar; `idePackagePrefix = "org.interscity.htc"`

## Package Layout

```
src/main/scala/
  core/
    actor/           — BaseActor, SimulationBaseActor, managers (Time, Report, Load, LoadBalance)
    entity/          — state/, event/, configuration/, control/
    util/            — StringPool, JsonUtil, IdUtil, ActorCreatorUtil
    metrics/         — Prometheus counter/gauge definitions
  model/
    mobility/        — data-only survivors of a removed legacy actor package: entity/ (state,
                       event data incl. SubRoutePair, VehicleLinkFlowData — still consumed by
                       JsonUtil / ClickHouseReportData), collections/, util/, types/. No actors.
    hybrid/          — micro+meso: Car, Bus, Link, PrivateVehicle (trait), Movable (abstract)
      micro/         — CarFollowingModel, LaneChangeModel, MicroSimulationStrategy
      support/car/   — CarJourneyReporter, CarLinkHandler, CarMicroHandler, CarSignalHandler
      util/          — GPSUtil, VehicleSimulationConfig, ModeChoiceStrategy
  avro/              — Avro-generated event schemas (routing, etc.)
```

## Actor Hierarchy

```
PersistentActor (Pekko)
  └── BaseActor[T <: BaseState]          — persistence, shard routing, StringPool interning
        └── SimulationBaseActor[T]       — Lamport clock, currentTick, report(), sendMessageTo()
              ├── Movable[T <: MovableState]  — route calculation, link traversal (meso)
              │     └── Car / Bus / Subway    — concrete vehicle actors
              └── managers (TimeManager, ReportManager, LoadBalanceManager, ...)
```

## Critical Patterns

### Handler Delegation
Complex actors delegate behaviour to stateless helper classes (no Akka):
```scala
// Pattern: inject lambdas to access actor internals
class CarMicroHandler(
  reportFn: (Map[String, Any], String) => Unit,
  entityIdFn: () => String,
  currentTickFn: () => Tick,
  ...
)
// Actor wires handlers lazily
private lazy val microHandler = new CarMicroHandler(
  reportFn = (data, label) => report(data = data, label = label),
  entityIdFn = () => getEntityId,
  ...
)
```

### Reporting Metrics
```scala
// All simulation outputs go through report() — never println or direct ClickHouse writes
report(data = Map("speed" -> v, "tick" -> currentTick), label = "car_speed")
reportToSpecificReporter(ReportTypeEnum.Parquet, data)
```

### String Interning
Override `internStateStrings` in every actor to deduplicate node IDs and repeated strings at init:
```scala
override protected def internStateStrings(s: MyState): MyState =
  s.copy(origin = StringPool.intern(s.origin), destination = StringPool.intern(s.destination))
```

### Message Sending
```scala
sendMessageTo(entityId = targetId, shardId = shardId, data = someData, eventType = "EVENT_TYPE")
// actorType defaults to LoadBalancedDistributed; pass explicitly for PoolDistributed actors
```

### Time / Ticks
- `currentTick: Tick` (Long) is the simulation clock
- `onFinishSpontaneous(Some(nextTick))` schedules next self-wake; `None` means no reschedule
- `scheduleEvent(tick)` registers with the LocalTimeManager
- Micro mode: `LinkMicroTimeManager` drives sub-tick updates via `MicroUpdateData`

### Actor State
- State (`T <: BaseState`) is reconstructed from JSON on load via Kryo/Jackson
- `var` fields inside state classes are fine for mutable simulation state
- `var` fields on the actor itself must be reset in `resetTripState()` / `onDestruct()`
- Never hold `ActorRef` in state — use entity IDs + shard IDs

**What must live in `state`, not as an actor-local `var`**: only `state` is carried by
`BaseActor.buildMigrationSnapshot` across a shard migration/rebalance. Any actor-local `var` that
represents something needed to reconstruct correct behavior after a restart — most importantly a
**pending reply obligation to another actor** (e.g. `PrivateVehicle.ownerPersonRef`,
`Person.currentPTVehicleRef`) — must go in `state`, or it silently vanishes on migration and the
actor loses the ability to ever answer that sender. See `docs/KNOWN_GAPS.md` ("Shard Migration
Silently Drops Actor-Local Reply State") for the concrete failure chain this caused.

Actor-local `var`s are fine to keep **outside** `state` when they're cheaply recoverable and hold
no reply obligation — e.g. handles/proxies to process-wide singletons (`CityMapUtil`,
`StringPool`), retry/attempt counters that are safe to reset to zero on restart, or caches
rebuildable from `state` on demand. The test is not "is it mutable" but "does losing this value on
restart break correctness for someone else, or just cost a recompute."

**Shard rebalancing/migration is currently kept disabled** — the project is prioritizing
simulation execution correctness and performance first, and migration/snapshot restore is not
part of the active test loop. Gaps A/B/C in `docs/KNOWN_GAPS.md` are real but dormant under this
setting; don't treat their absence from observed bugs as evidence they're fixed.

## Synchronization Discipline

HTC avoids deadlocks by protocol invariant, not by watchdog/timeout recovery. Every
`actSpontaneous` must resolve on every branch (`onFinishSpontaneous(Some(tick))` or `None`), and
every interaction event's reply obligation is **conditional**, not universal — it depends on
whether the sender actually needs something back to stay consistent.

**Fire-and-forget** — sender doesn't need anything back, doesn't wait, resolves its own
spontaneous event regardless of what the receiver does with the message:
```scala
// Car reports its speed to the metrics pipeline — no reply expected, no wait.
override def actSpontaneous(event: BaseEvent): Unit = {
  report(data = Map("speed" -> currentSpeed, "tick" -> currentTick), label = "car_speed")
  sendMessageTo(entityId = linkId, shardId = linkShardId, data = LinkInfoData(...), eventType = "CAR_ENTER_LINK")
  onFinishSpontaneous(Some(nextTick))  // resolves immediately; doesn't wait on the link's reaction
}
```

**Consistency-critical** — sender needs the receiver's answer to proceed correctly, so it waits
for the reply before resolving; the receiver must reply on every branch, including no-ops:
```scala
// Car reaching a node needs the signal state before it can decide whether to cross.
override def actSpontaneous(event: BaseEvent): Unit = {
  sendMessageTo(entityId = nodeId, shardId = nodeShardId, data = SignalStateRequest(targetLinkId), eventType = "REQUEST_SIGNAL_STATE")
  // does NOT call onFinishSpontaneous here — waits for NODE's reply as an interaction event
}

// Node: must answer every request, even when there's no signal control for that movement
override def actInteractWith(event: BaseEvent): Unit = event.data match {
  case req: SignalStateRequest =>
    val state = signalStates.get(req.targetLinkId).getOrElse(SignalState.Green) // default green — still a reply
    sendMessageTo(entityId = event.sourceId, shardId = event.sourceShardId, data = SignalStateResponse(state), eventType = "SIGNAL_STATE_RESPONSE")
  case _ => // ...
}

// Car resolves its own spontaneous event only once the reply arrives
override def actInteractWith(event: BaseEvent): Unit = event.data match {
  case resp: SignalStateResponse =>
    if (resp.state == SignalState.Green) crossIntersection() else waitAndRescheduleSelf()
    onFinishSpontaneous(Some(nextTick))
  case _ => // ...
}
```

Rules of thumb:
- If nothing downstream depends on B's answer to decide what A does next, it's fire-and-forget —
  don't add a reply/wait that isn't needed.
- If A's own correctness (ordering, state coherence) depends on B's answer, it's
  consistency-critical — B must reply on **every** branch (a "nothing to report" case is still a
  reply), and only this case justifies A not resolving its spontaneous event until the reply
  arrives.
- Never let B's reply-producing logic require a further round-trip back through A (or anything
  downstream of A) before B can answer — that creates a logical wait cycle (livelock) even though
  no actor thread is ever blocked.
- Ordering between these exchanges is guaranteed by the Lamport clock (`lamportTick` on
  `BaseEvent`), not by a manual sync with the Time Manager.

Common bugs to check for when reviewing a diff against this discipline:
- A fire-and-forget path where `onFinishSpontaneous` got moved into a reply handler for no
  reason — unnecessary coupling/wait that doesn't need to exist.
- A consistency-critical request handler with a branch that returns without calling
  `sendMessageTo(...Response...)` — hangs the sender (and transitively the Time Manager).
- A reply handler that forgets `onFinishSpontaneous` after consuming the response — the sender
  received its answer but never resolves its own spontaneous event.
- A watchdog/timeout added to "fix" a hang instead of finding and fixing the missing reply path.

## Simulation Models

### Hybrid (micro+meso) — `model.hybrid`
- Meso: vehicles traverse links at average speed (LinkInfoData enter/leave)
- Micro: IDM car-following + MOBIL lane change; Link actor drives via `MicroUpdateData` every sub-tick
- Transition: `state.activateMicroMode()` / `state.deactivateMicroMode()`
- `VehicleSimulationConfig` provides simulation-wide tuning from env vars / application.conf

## Key Utilities

| Utility | Purpose |
|---|---|
| `GPSUtil.calcRouteCompact(origin, dest, maxExpansions)` | A* route in compact (linkId, nodeId) pairs |
| `CityMapUtil` | Singleton city graph; thread-safe reads |
| `StringPool.intern(s)` | JVM string deduplication for repeated node/link IDs |
| `VehicleSimulationConfig` | Lazy env-var/config reader for simulation tunables |
| `ActorCreatorUtil.createShardRegion(...)` | Registers a new actor type with cluster sharding |
| `IdUtil` | Deterministic entity ID generation |

## Coding Rules

1. **No blocking in actors** — never `Await.result`, `Thread.sleep`, or synchronous DB calls inside `receive`
2. **No `Future` chained into actor state** — use `pipeTo(self)` if needed, then match the reply message
3. **Handlers are stateless** — helper classes (`CarMicroHandler` etc.) receive only lambdas, hold no Pekko refs
4. **No comments explaining what** — only add comments for non-obvious WHY (constraint, workaround, invariant)
5. **Delegation over inheritance** — prefer composing handlers over deepening the actor hierarchy
6. **StringPool on every new actor** — override `internStateStrings`; every node/link ID string must be interned
7. **report() for all output** — never write to storage directly from an actor; use `report()` or `reportToSpecificReporter()`
8. **Reset mutable state** — override `resetTripState()` and `onDestruct()` when adding per-trip `var` fields to Car/Bicycle/Motorcycle
9. **Enum instead of magic strings** — event types in `EventTypeEnum`, actor types in `ActorTypeEnum`, status in dedicated enums
10. **Prometheus labels** — keep label cardinality low; never use entity IDs as label values

## Testing

`src/test/scala/model/hybrid/entity/state/plan/PlanCursorSpec.scala` covers the plan cursor
(`PlanElement`/`PlanCursor`/`RemainingQueue`) — pure logic, no Pekko.
Use `pekko-actor-testkit-typed` for actor tests. Test helpers: `TestKit`, `TestProbe`.
Pure logic (handlers, models) should be tested without Pekko using plain ScalaTest.
(`KafkaAbstractionSpec.scala` was removed — it tested a `KafkaSerializerFactory`/
`KafkaAbstractionFactory` API that was never implemented, only speculative traits in
`KafkaAbstraction.scala`. Revisit if that abstraction layer is ever built for real.)

## Build & Run

```bash
sbt compile
sbt test
sbt assembly   # produces target/scala-3.3.5/hyperbolic-time-chamber-1.24.3.jar
docker-compose up   # docker-compose.yml for local cluster
```

## Environment Variables (key ones)

| Var | Default | Purpose |
|---|---|---|
| `HTC_CAR_STALE_EVENT_LOG_EVERY` | 100 | Sample rate for stale-event warnings |
| `htc.car.stale-event-log-every` | (config fallback) | Same via application.conf |
| Simulation end tick | via config | `VehicleSimulationConfig.simulationEndTick` |

## Architecture Diagram Reference

See the attached diagram (HTC Digital Twin Platform):
- **HTC** = Hyperbolic Time Chamber (this repo) — the simulation engine
- **SimEDaPE** = downstream ML-based estimator consuming HTC output metrics
- **HTC-DL Bus** = definition language / config bus
- **Decision Layer** = ML Generator + ML Optimizer + Human Supervisor
