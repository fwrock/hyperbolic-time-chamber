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
    mobility/        — meso-only: Car, Bus, Subway, Link, Node, Person (actors + states + events)
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

## Simulation Models

### Hybrid (micro+meso) — `model.hybrid`
- Meso: vehicles traverse links at average speed (LinkInfoData enter/leave)
- Micro: IDM car-following + MOBIL lane change; Link actor drives via `MicroUpdateData` every sub-tick
- Transition: `state.activateMicroMode()` / `state.deactivateMicroMode()`
- `VehicleSimulationConfig` provides simulation-wide tuning from env vars / application.conf

### Mobility (meso-only) — `model.mobility`
- Simpler; no micro mode; used for Bus, Subway, person trip planning
- `Person` actor orchestrates multi-modal trips via `PersonTripManager`

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

Single test file: `src/test/scala/system/broker/kafka/abstraction/KafkaAbstractionSpec.scala`
Use `pekko-actor-testkit-typed` for actor tests. Test helpers: `TestKit`, `TestProbe`.
Pure logic (handlers, models) should be tested without Pekko using plain ScalaTest.

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
