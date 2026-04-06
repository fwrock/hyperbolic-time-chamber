# Progressive Actor Loading

## Overview

The progressive loading system allows actor creation to be **deferred during simulation** instead of loading all actors at startup. This addresses two key problems with the original eager loading:

1. **Shard coordinator overwhelm**: Creating millions of actors at startup floods the shard coordinator
2. **Startup latency**: The simulation can't begin until all actors are created

## Architecture

### Two-Phase Loading

Actors are classified into two loading strategies via the `loadingStrategy` field in `simulation.json`:

| Strategy | Actors | When Created |
|----------|--------|-------------|
| `EAGER` | Infrastructure (nodes, links, signals, bus stops) | Before simulation starts (same as before) |
| `PROGRESSIVE` | Dynamic entities (cars, persons, buses) | During simulation, based on `startTick` |

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     PHASE 1: EAGER LOADING (before simulation)             │
│                                                                             │
│  SimulationManager                                                          │
│       │                                                                     │
│       ▼                                                                     │
│  LoadDataManager ─── filters by loadingStrategy ──┐                        │
│       │                                            │                        │
│       ▼ (EAGER only)                               │                        │
│  JsonLoadData ──► CreatorLoadData ──► Shard        │                        │
│       │                                            │                        │
│       ▼ (when done)                                │                        │
│  FinishLoadDataEvent(progressiveSources=[...]) ───►│                        │
│       │                                            │                        │
│       ▼                                            │                        │
│  SimulationManager.startSimulation()               │                        │
│       ├── creates ProgressiveLoadDataManager ◄─────┘                        │
│       ├── registers it with GlobalTimeManager                               │
│       └── sends StartSimulationTimeEvent                                    │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                     PHASE 2: PROGRESSIVE LOADING (during simulation)       │
│                                                                             │
│  ┌────────────────────┐                    ┌──────────────────────────┐     │
│  │ GlobalTimeManager  │  TickWindowRequest  │ ProgressiveLoad         │     │
│  │                    │ ─────────────────► │ DataManager (singleton)  │     │
│  │ Before broadcasting│                    │                          │     │
│  │ next tick, checks  │  TickWindowReady   │ For each PROGRESSIVE     │     │
│  │ if actors are ready│ ◄───────────────── │ source:                  │     │
│  │                    │                    │ ┌──────────────────────┐ │     │
│  │ If not ready:      │                    │ │ ProgressiveJson      │ │     │
│  │ WAITS (rare)       │                    │ │ LoadData             │ │     │
│  │                    │                    │ │ (tick-indexed)       │ │     │
│  │ Proactively loads  │                    │ └──────────────────────┘ │     │
│  │ ahead by           │                    │                          │     │
│  │ lookAheadTicks     │                    └──────────────────────────┘     │
│  └────────────────────┘                                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Components

#### 1. `LoadingStrategyEnum`
```scala
enum LoadingStrategyEnum:
  case EAGER, PROGRESSIVE
```

#### 2. `TickIndexUtil`
Scans JSON files to build a `Map[Tick, List[Int]]` index. This allows random-access to actors by their `startTick` without maintaining a sorted file.

#### 3. `ProgressiveJsonLoadData`
A `LoadDataStrategy` that:
- First builds a tick index of the entire JSON file (fast scan)
- Stores all actors in an indexed array for random access
- Responds to `LoadActorsForTickRange` requests by selecting the right actors

#### 4. `ProgressiveLoadDataManager`
A cluster singleton that:
- Manages multiple `ProgressiveJsonLoadData` actors (one per source file)
- Responds to `TickWindowRequest` from GlobalTimeManager
- Tracks which tick ranges have been loaded to avoid duplicates
- Reports `TickWindowReady` when all loaders complete a range

#### 5. `GlobalTimeManager` (modified)
- Receives `RegisterProgressiveLoadManagerEvent` during startup
- Before broadcasting each tick, checks if actors are ready up to `currentTick + lookAheadTicks`
- Proactively requests loading when the buffer is less than half the look-ahead
- Only **waits** if the simulation catches up (rare with good look-ahead)

## Configuration

### simulation.json

```json
{
  "duration": 86400,
  "startTick": 0,
  "actorsDataSources": [
    {
      "id": "htcrid:node;1",
      "classType": "hybrid.actor.Node",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "EAGER",
      "dataSource": {"sourceType": "json", "info": {"path": ".../nodes_1.json"}}
    },
    {
      "id": "htcrid:car;1",
      "classType": "hybrid.actor.Car",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "PROGRESSIVE",
      "dataSource": {"sourceType": "json", "info": {"path": ".../cars_1.json"}}
    },
    {
      "id": "htcrid:person;1",
      "classType": "hybrid.actor.Person",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "PROGRESSIVE",
      "dataSource": {"sourceType": "json", "info": {"path": ".../persons_1.json"}}
    }
  ]
}
```

### Which actors should be EAGER vs PROGRESSIVE?

| Actor Type | Recommended Strategy | Reason |
|-----------|---------------------|--------|
| `Node` | `EAGER` | Infrastructure, needed from tick 0 |
| `Link` / `RailLink` | `EAGER` | Infrastructure, needed from tick 0 |
| `TrafficSignal` | `EAGER` | Infrastructure, needed from tick 0 |
| `BusStop` | `EAGER` | Infrastructure, needed from tick 0 |
| `BusStation` | `EAGER` | Creates buses internally |
| `SubwayStation` | `EAGER` | Creates subways internally |
| `Car` | `PROGRESSIVE` | Has varying `startTick`, largest volume |
| `Person` | `PROGRESSIVE` | Has varying `startTick`, large volume |
| `Bus` | Depends | If created by BusStation: `EAGER`; if standalone: `PROGRESSIVE` |
| `Bicycle` | `PROGRESSIVE` | Has varying `startTick` |
| `Motorcycle` | `PROGRESSIVE` | Has varying `startTick` |

### Backward Compatibility

If `loadingStrategy` is not specified, it defaults to `EAGER`, preserving the original behavior. Existing `simulation.json` files work without modification.

## Event Flow

### Normal Flow (actors pre-loaded ahead of time)

```
GTM: calculateAndBroadcastNextGlobalTick()
  ├── nextTick = 500
  ├── progressiveLoadedUpToTick = 1500 (ok, 500 < 1500)
  ├── remainingBuffer = 1000, lookAhead = 1000
  │   └── buffer ≥ lookAhead/2 → no request needed
  ├── broadcast UpdateGlobalTimeEvent(500) to local TMs
  └── [simulation continues normally]
```

### Proactive Loading (buffer running low)

```
GTM: calculateAndBroadcastNextGlobalTick()
  ├── nextTick = 1200
  ├── progressiveLoadedUpToTick = 1500
  ├── remainingBuffer = 300, lookAhead = 1000
  │   └── buffer < lookAhead/2 → REQUEST MORE
  ├── sends TickWindowRequest(1200, 2200) to PLM
  ├── broadcast UpdateGlobalTimeEvent(1200) to local TMs
  │   [simulation continues in parallel]
  └── PLM responds TickWindowReady(2200, 450)
      └── progressiveLoadedUpToTick = 2200
```

### Blocking Wait (rare, only if simulation outpaces loading)

```
GTM: calculateAndBroadcastNextGlobalTick()
  ├── nextTick = 1600
  ├── progressiveLoadedUpToTick = 1500 (BLOCKED: 1600 > 1500)
  ├── waitingForProgressiveLoad = true
  ├── sends TickWindowRequest(1600, 2600) to PLM
  ├── [WAITS - does NOT broadcast]
  └── PLM responds TickWindowReady(2600, 800)
      ├── waitingForProgressiveLoad = false
      ├── progressiveLoadedUpToTick = 2600
      └── broadcast UpdateGlobalTimeEvent(1600) to local TMs
```

## Look-Ahead Configuration

The `lookAheadTicks` is automatically calculated based on simulation duration:

| Duration | Look-Ahead | Rationale |
|----------|-----------|-----------|
| > 10,000 | 1,000 | Large simulations need bigger buffer |
| > 1,000 | 500 | Medium simulations |
| ≤ 1,000 | 100 | Short simulations, less buffering needed |

## Performance Characteristics

- **Index Building**: O(n) scan of JSON file — one-time cost at startup
- **Memory**: All actors from PROGRESSIVE files are held in memory as `ActorSimulation` objects (not as live actors). This is much lighter than creating full Pekko actors.
- **Tick Range Queries**: O(k) where k = number of unique ticks in range, filtered from a HashMap
- **Shard Coordinator**: Receives actor creation requests spread across ticks rather than all at once
- **Simulation Blocking**: Rare with proper look-ahead; the proactive request mechanism keeps the buffer above 50% of look-ahead

## Files Created/Modified

### New Files
- `src/main/scala/core/enumeration/LoadingStrategyEnum.scala`
- `src/main/scala/core/util/TickIndexUtil.scala`
- `src/main/scala/core/actor/manager/load/strategy/ProgressiveJsonLoadData.scala`
- `src/main/scala/core/actor/manager/ProgressiveLoadDataManager.scala`
- `src/main/scala/core/entity/event/control/load/TickWindowRequest.scala`
- `src/main/scala/core/entity/event/control/load/TickWindowReady.scala`
- `src/main/scala/core/entity/event/control/load/StartProgressiveLoadingEvent.scala`
- `src/main/scala/core/entity/event/control/load/RegisterProgressiveLoadManagerEvent.scala`
- `src/main/scala/core/entity/event/control/load/PreLoadTickRange.scala`
- `src/main/scala/core/entity/event/control/load/TickRangeLoadedEvent.scala`
- `src/main/scala/core/entity/event/control/load/TickIndexBuiltEvent.scala`
- `src/main/scala/core/entity/event/control/load/LoadActorsForTickRange.scala`
- `src/main/scala/core/entity/event/control/load/BuildTickIndex.scala`
- `src/main/scala/core/entity/event/control/load/ProgressiveLoadingCompleteEvent.scala`

### Modified Files
- `src/main/scala/core/entity/configuration/ActorDataSource.scala` — added `loadingStrategy` field
- `src/main/scala/core/entity/event/control/load/FinishLoadDataEvent.scala` — added progressive source fields
- `src/main/scala/core/actor/manager/LoadDataManager.scala` — filters EAGER vs PROGRESSIVE
- `src/main/scala/core/actor/manager/GlobalTimeManager.scala` — tick window coordination
- `src/main/scala/core/actor/manager/SimulationManager.scala` — creates ProgressiveLoadDataManager
- `src/main/scala/core/util/ManagerConstantsUtil.scala` — added manager name constants
