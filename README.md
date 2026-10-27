# Hyperbolic Time Chamber *(HTC)*

![HTC Banner](https://github.com/user-attachments/assets/dddd6245-f4bd-43fc-8888-6ef73d01a221)

**A distributed, event-driven multi-agent framework for urban mobility simulation and research**

The next release is **3.2.0**, following 3.1.0. Release branch: `release/3.2.0`.
The sbt version, assembled JAR and deployment images use the same release number.

Built with Scala 3 and Apache Pekko, HTC implements a **hybrid micro-meso simulation model** where each link in the road network can independently operate in mesoscopic (aggregate flow) or microscopic (individual car-following) mode. Distributed execution and selective microscopic detail support urban mobility research; city-scale throughput and general traffic accuracy remain validation goals.

---

## Table of Contents

1. [What is HTC?](#what-is-htc)
2. [Architecture Overview](#architecture-overview)
3. [Simulation Models](#simulation-models)
4. [Agent Catalogue](#agent-catalogue)
5. [Quick Start](#quick-start)
6. [Scenario Structure](#scenario-structure)
7. [Configuration](#configuration)
8. [REST API](#rest-api)
9. [Reporting & Output](#reporting--output)
10. [Infrastructure & Deployment](#infrastructure--deployment)
11. [Project Layout](#project-layout)
12. [Documentation](#documentation)
13. [Academic Usage](#academic-usage)
14. [License](#license)

---

## What is HTC?

The Hyperbolic Time Chamber is a general-purpose **activity-based urban mobility simulator**.
Its key design goals are:

| Goal | Mechanism |
|---|---|
| **Scale** | Apache Pekko cluster — horizontal scaling across nodes |
| **Fidelity** | Per-link MESO/MICRO mode flag — microscopic detail only where needed |
| **Realism** | Activity-based Person agents with mode choice (car, bicycle, motorcycle, walk, bus, subway) |
| **Openness** | JSON scenario files, REST API, pluggable car-following / lane-change models |
| **Observability** | Prometheus metrics, ClickHouse / CSV / JSON / Parquet outputs |

*Inspired by the Hyperbolic Time Chamber from Dragon Ball, where 1 day inside equals 1 year of training — our simulator compresses real-world time into fast, accelerated mobility analysis.*

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────┐
│                  Apache Pekko Cluster                 │
│                                                      │
│  SimulationManager ──► GlobalTimeManager             │
│         │                    │                       │
│         ▼                    ▼                       │
│  LoadDataManager      LocalTimeManager(s)            │
│  (progressive JSON    (per-actor scheduling)         │
│   batch loading)                                     │
│         │                                            │
│         ▼                                            │
│  Actor Pool ──────────────────────────────────────   │
│  ├── Node / Link / RailLink / TrafficSignal          │
│  ├── BusStop / BusStation / Bus                      │
│  ├── SubwayStation / Subway                          │
│  ├── Car / Bicycle / Motorcycle                      │
│  └── Person (activity-based agent)                   │
│                                                      │
│  ReportManager ──► ClickHouse / CSV / JSON / Parquet │
│  MetricsServer ──► Prometheus                        │
│  REST API (Pekko HTTP, port 8080)                    │
└──────────────────────────────────────────────────────┘
```

### Core managers

| Manager | Responsibility |
|---|---|
| `SimulationManager` | Orchestrates startup, actor lifecycle, global state |
| `GlobalTimeManager` | Advances discrete ticks; dispatches spontaneous events |
| `LoadDataManager` / `ProgressiveLoadDataManager` | Progressive batch loading of JSON scenario files |
| `ReportManager` | Collects and exports simulation metrics |
| `StatisticManager` | Real-time statistical aggregation |
| `SnapshotManager` | State snapshots for checkpointing |
| `DigitalTwinManager` | Digital-twin synchronisation hooks |
| `MachineLearningManager` | ML inference hooks |
| `RandomSeedManager` | Reproducible random number streams |

### Conservative and Time Warp execution

Each run selects one synchronization policy through `Simulation.timeManagerType`:
`discrete-event` is the default conservative policy; `time-warp` selects optimistic
execution. Production Time Warp uses `AccountedOptimisticGlobalTimeManager` and
`AccountedOptimisticLocalTimeManager`. Local managers own model delivery and
anti-message accounting; the global manager aggregates frozen-round summaries
to establish global virtual time (GVT). Rollback restores checkpointed state and
replays ordered inputs. Reports commit strictly below GVT, and successful completion
waits for report receipts and storage flush acknowledgements.

The supported runtime milestone includes bounded speculative execution,
progressive JSON/SQLite input windows, cancellable station-created Bus/Subway
actors and an accounted dynamic-routing adapter. Two-JVM validation covers actual
cross-owner transport, conservative/optimistic report parity, delayed transport
and explicit run failure on worker loss. Those experiments use separate JVMs on
one host; recovery, ownership migration, network partitions and city-scale
performance remain outside the validated scope. See the
[runtime contract](docs/DISTRIBUTED_OPTIMISTIC_RUNTIME.md) and
[multi-JVM validation](docs/MULTI_JVM_POLICY_VALIDATION_2026_10_24.md).

---

## Simulation Models

### Mesoscopic (MESO)

Each link maintains a **count** of registered vehicles and computes aggregate speed via a
BPR-like density model (`SpeedUtil.linkDensitySpeed`).  The link is **passive** — vehicles
schedule their own departure tick.  No per-vehicle position or velocity is tracked.

### Microscopic (MICRO)

Active MICRO Links schedule their own spontaneous events through the simulation
Time Manager. `LinkMicroSimulationHandler` delegates each batch to
`DefaultMicroSimulationStrategy`, with default 10 × 0.1-second physics steps per
one-second runtime tick. Each lane uses the simplified Krauss car-following model
and updates vehicle positions and velocities. The handler sends the final
`MicroUpdateData` for each vehicle in that batch, or `MicroLeaveLinkData` on exit.
The active default uses `NoLaneChangeStrategy`; integrated MOBIL lane changing is
not established by the presence of the model interfaces.

Opt-in 0.1-second runtime ticks, boundary remainder transfer, finite junction
connectors and downstream leader observations provide the validated single-lane
Car configuration. All four predeclared v2 cases and five fresh demand seeds pass
the provisional SUMO criteria; independent empirical following/discharge validation
remains open. Default mixed-model scenarios retain one-second clocks. See
[MICRO implementation and validation status](docs/MICRO_VALIDATION_STATUS.md) for
configuration, evidence, limitations and remaining work.

### Hybrid Strategy

Every road `Link` carries a `simulationMode: SimulationModeEnum` flag (`MESO` | `MICRO`).
Road vehicles adopt the mode of the Link they enter. Existing one-second scenarios
can mix MESO roads and MICRO corridors alongside transit and Person actors.
The fractional-clock configuration has the narrower Car/Link/Node/TrafficSignal
scope described in the [validation status](docs/MICRO_VALIDATION_STATUS.md).

#### Krauss safe-velocity formula

$$v_{safe} = -\tau b + \sqrt{(\tau b)^2 + v_{leader}^2 + 2 b \cdot gap}$$

where $\tau$ = reaction time (1.0 s), $b$ = max deceleration (4.5 m/s²).
Here `gap` is bumper spacing after subtracting the minimum standstill gap. This
continuous stopping-distance approximation does not reproduce every discrete
detail of SUMO's Krauss implementation.

### Public transport and mixed trips

Bus and Subway scenarios support scheduled service creation, boarding/alighting
and cyclic routes under both conservative and Time Warp execution. Startup installs
transit resources on every JVM before model actors initialize. Passenger ride
distance uses checkpointed boarding and alighting odometer differences; Subway
counts physical arrival before unloading and prevents duplicate distance credit.

Person preserves completed-leg distance across walking, transit and private-vehicle
transitions and PT replanning. Reaching the next activity completes one trip for
the entire sequence of legs. Walking distance comes from the actual route and is
credited only on arrival. An abort retains completed legs without claiming a
completed trip; unfinished walks contribute no distance at the horizon. Active
vehicle journeys receive explicit horizon-truncated reports instead of invented
destination arrivals or unfinished-link distance.

The bounded mixed-trip campaign validates a
**walk → Bus → walk → Subway → walk** journey as one 1,700 m trip, alongside
walking-only and consecutive-leg cases. All ten simulator cases pass and seven
full-report comparisons match, including MICRO Bus execution, delayed two-JVM
transport and optimistic rollback. These Person/transit scenarios require
**one-second runtime ticks**; fractional clocks currently support only the
documented Car/Link/Node/TrafficSignal configuration. See
[mixed-trip and walking validation](docs/WALKING_DISTANCE.md) and
[transit distance and horizon reporting](docs/HORIZON_AND_TRANSIT_REPORTING.md).

---

## Agent Catalogue

### Road Infrastructure

| Actor | Package | Description |
|---|---|---|
| `Node` | `hybrid.actor` | Intersection / endpoint; stores traffic-signal states and PT stop references |
| `Link` | `hybrid.actor` | Directed road segment; MESO or MICRO mode |
| `RailLink` | `hybrid.actor` | Directed rail segment (subway-only) |
| `TrafficSignal` | `hybrid.actor` | Signal controller; wakes once per cycle, notifies Nodes |

### Public Transit

| Actor | Package | Description |
|---|---|---|
| `BusStop` | `hybrid.actor` | Physical stop; queues waiting passengers |
| `BusStation` | `hybrid.actor` | Route manager; spawns `Bus` actors at scheduled ticks |
| `Bus` | `hybrid.actor` | Autonomous vehicle; follows fixed route; handles boarding/alighting |
| `SubwayStation` | `hybrid.actor` | Spawns `Subway` trains; manages platform interactions |
| `Subway` | `hybrid.actor` | Metro/train following `RailLink` paths |

### Private Vehicles

| Actor | Package | Vehicle length | Max accel | Desired speed |
|---|---|---|---|---|
| `Car` | `hybrid.actor` | 4.5 m | 2.6 m/s² | 50 km/h |
| `Bicycle` | `hybrid.actor` | 2.0 m | 1.0 m/s² | 20 km/h |
| `Motorcycle` | `hybrid.actor` | 2.5 m | 3.5 m/s² | 60 km/h; lane filtering |

### Person Agent

`Person` is the **activity-based decision-maker**. It persists for the entire simulation, walking
a flat **plan** — a list of `Activity`/`AtomicLeg` (`WalkLeg`, `PrivateVehicleLeg`, `TransitLeg`)
/`PendingDecision` elements — one element at a time via a `PlanCursor`, and owns references to
private vehicles.

Key features:

- **Plan** — flat ordered list of plan elements (`originalPlan` for provenance, `cursor` for actual
  execution position), replacing the older `dailySchedule`/`currentActivityIndex` pair
- **Mode choice** — `Walk | Car | Bicycle | Motorcycle | Bus | Subway`; a leg is either already
  concrete in the plan (fixed) or a `PendingDecision` resolved at runtime
- **Dynamic mode choice** — pluggable `ModeDecisionEngine`s (`"raptor"`, `"nearest-stop-utility"`,
  `"travel-time"`) registered in `ModeDecisionEngineRegistry`; only `"travel-time"` evaluates
  private vehicles as a candidate
- **Trip lifecycle** — executes walking, vehicle and transit legs; reaching the next activity completes the trip
- **Statistics** — preserves measured distance across legs and counts completed trips once per activity arrival

See **[Person Agent docs](docs/PERSON_AGENT.md)** for the full model.

---

## Quick Start

**Prerequisites:** Java 21+, Docker

```bash
# Clone and build
git clone https://github.com/fwrock/hyperbolic-time-chamber
cd hyperbolic-time-chamber

# Build fat-jar and Docker image, then run
./build-and-run.sh

# Tests only
sbt test

# Compile without running tests
sbt compile

# Code formatting
sbt scalafmt

# Lint check
sbt scalafix --check
```

**Docker (pre-built image):**

```bash
docker pull uxhabam/hyperbolic-time-chamber:3.2.0
docker run -e HTC_SIMULATION_CONFIG_FILE=/app/simulations/my_scenario/simulation.json \
           -v /path/to/scenarios:/app/simulations \
           uxhabam/hyperbolic-time-chamber:3.2.0
```

### Release artifacts

Build the versioned JAR and Docker image locally:

```bash
sbt test assembly
docker build --build-arg HTC_VERSION=3.2.0 \
  -t uxhabam/hyperbolic-time-chamber:3.2.0 .
```

The assembly is `target/scala-3.3.5/hyperbolic-time-chamber-3.2.0.jar`.
`build-and-run.sh` reads the version from sbt and passes it to the Docker build.
Compose and Kubernetes manifests reference `uxhabam/hyperbolic-time-chamber:3.2.0`;
the VM Compose file also accepts an explicit `HTC_IMAGE` override. Registry pull
examples require that image to be published first. Dated validation records retain
their original executable names and hashes for reproducibility.

### Stack versions

| Component | Version |
|---|---|
| Scala | 3.3.5 |
| Java | 21+ |
| SBT | 1.10.7 |
| Apache Pekko | 1.7.1 |
| Pekko HTTP | 1.4.2 |
| Pekko Management | 1.2.1 |
| Apache Pekko Kafka connector | 1.2.0 |
| Apache Avro | 1.12.2 |
| Apache Parquet | 1.18.1 |
| Prometheus Java client | 0.16.0 |

---

## Scenario Structure

A scenario is a **directory of JSON files** read at startup:

```
<scenario>/
├── simulation.json            ← manifest (required)
├── scenario_metadata.json     ← statistics / description (optional)
└── data/
    ├── city_map.json          ← in-memory routing graph (required)
    ├── transit_map.json       ← transit stop index for dynamic mode choice (optional)
    ├── nodes_0.json           ← Node actors (may be split across files)
    ├── links_0.json           ← Link actors
    ├── rail_links_0.json      ← RailLink actors
    ├── traffic_signals_0.json
    ├── bus_stops_0.json
    ├── bus_stations_0.json
    ├── buses_0.json           ← pre-defined departures
    ├── subway_stations_0.json
    ├── persons_0.json
    └── cars_0.json            ← car assets owned by Persons
```

### Link actor JSON (MICRO example)

```json
{
  "id": 1001,
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "startTick": 0,
      "from": 1,
      "to": 2,
      "length": 500.0,
      "lanes": 3,
      "speedLimit": 50.0,
      "capacity": 150.0,
      "freeSpeed": 50.0,
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "laneConfigurations": [
        {"laneId": 0, "laneType": "NORMAL"},
        {"laneId": 1, "laneType": "NORMAL"},
        {"laneId": 2, "laneType": "BUS_LANE"}
      ]
    }
  }
}
```

This example uses the existing one-second, three-lane MICRO configuration.
Link speeds are in km/h; microscopic vehicle velocities are in m/s. For the
validated single-lane 0.1-second configuration and its additional constraints,
see [MICRO status and configuration](docs/MICRO_VALIDATION_STATUS.md#scope-and-configuration).

### Person actor JSON (plan-based)

```json
{
  "id": "htcaid:person;alice",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "startTick": 28800,
      "scheduleOnTimeManager": true,
      "ownedVehicles": {
        "car": {"id": "htcaid:car;alice_car", "classType": "hybrid.actor.Car"}
      },
      "originalPlan": [
        {
          "kind": "Activity",
          "activityType": "Home",
          "nodeId": "htcaid:node;home_node",
          "endTime": {"kind": "AtTick", "tick": 28800}
        },
        {
          "kind": "PrivateVehicleLeg",
          "mode": "Car",
          "vehicle": {"id": "htcaid:car;alice_car", "classType": "hybrid.actor.Car"}
        },
        {
          "kind": "Activity",
          "activityType": "Work",
          "nodeId": "htcaid:node;work_node",
          "endTime": {"kind": "AtTick", "tick": 64800}
        }
      ]
    }
  }
}
```

For the complete entity reference see **[Scenario Modeling Guide](docs/SCENARIO_MODELING.md)**.

---

## Configuration

Configuration is hierarchical (highest priority first):

1. **Environment variables** — `HTC_*` prefix
2. **JSON scenario** — `simulation.json` / `content` fields
3. **`application.conf`** — Pekko + HTC defaults
4. **Docker / k8s** — injected via env or ConfigMap

### Key environment variables

| Variable | Default | Description |
|---|---|---|
| `HTC_SIMULATION_CONFIG_FILE` | — | Path to `simulation.json` |
| `HTC_API_ENABLED` | `false` | Enable REST API |
| `HTC_API_PORT` | `8080` | REST API port |
| `HTC_SCENARIOS_DIR` | `/app/simulations` | Root directory for scenarios |
| `HTC_REPORT_TYPE` | `json` | Output format: `json`, `csv`, `clickhouse`, `parquet` |

---

## REST API

When `HTC_API_ENABLED=true` the simulator starts in **Idle** state and exposes:

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/health` | Health check |
| `GET` | `/api/v1/simulation/status` | Current simulation status |
| `POST` | `/api/v1/simulation/start` | Start (or resume) simulation |
| `POST` | `/api/v1/simulation/pause` | Pause execution |
| `POST` | `/api/v1/simulation/stop` | Stop and finalise |
| `GET` | `/api/v1/scenarios` | List available scenarios |
| `POST` | `/api/v1/scenarios/load` | Load a scenario by name |
| `GET` | `/api/v1/settings` | Read runtime settings |
| `PUT` | `/api/v1/settings` | Override runtime settings |

Full endpoint reference: **[API Reference](docs/API.md)**

### Simulation lifecycle states

```
Idle ──► Loading ──► Running ──► Stopped
                       │
                     Paused ──► Running
```

---

## Reporting & Output

`ReportManager` collects per-tick metrics and writes them in the configured format:

| Format | Class | Notes |
|---|---|---|
| JSON | `JsonReportData` | Default; one file per report batch |
| CSV | `CsvReportData` | Flat table; compatible with pandas / R |
| ClickHouse | `ClickHouseReportData` | Time-series DB; see [ClickHouse queries](docs/clickhouse-queries.md) |
| Parquet | `ParquetReportData` | Columnar; Snappy / Zstd compression |

**Dynamic routing weights** can use `DynamicWeightCache` strategies. Time Warp
requires `htc.routing.cache-strategy=accounted` for dynamic feedback through fixed
RoutingService actors; unaccounted shared caches are rejected. Static routing is
also supported. See [Accounted Routing](docs/ACCOUNTED_ROUTING.md).

**Prometheus metrics** are exposed on `/metrics` (default port `9090`) for Grafana dashboards
(see `k8s/monitoring/`).

---

## Infrastructure & Deployment

### Docker Compose

```bash
# Full stack (Kafka, ClickHouse, Redis, Prometheus, Grafana)
docker-compose up

# Lightweight single-node
docker-compose -f docker-compose-beluga-single.yml up
```

### Kubernetes

Manifests in `k8s/`:

```
k8s/
├── simulation-setup.yaml
├── kafka-bootstrap-config.yaml
├── kafka-connect-gcs-sink.yaml
├── service.yaml
├── rbac.yaml
├── service-account.yaml
└── monitoring/
    ├── prometheus.yaml
    ├── grafana.yaml
    └── node-exporter.yaml
```

### Terraform (GCP)

```bash
cd terraform/gcp
terraform init && terraform apply
```

---

## Project Layout

```
src/main/scala/
├── main.scala                    ← entry point
├── core/
│   ├── actor/
│   │   ├── BaseActor.scala
│   │   ├── SimulationBaseActor.scala
│   │   └── manager/
│   │       ├── SimulationManager.scala
│   │       ├── StatisticManager.scala
│   │       ├── SnapshotManager.scala
│   │       ├── DigitalTwinManager.scala
│   │       ├── MachineLearningManager.scala
│   │       ├── RandomSeedManager.scala
│   │       ├── time/               ← GlobalTimeManager, LocalTimeManagers
│   │       ├── load/               ← LoadDataManager, ProgressiveLoadDataManager
│   │       └── report/             ← ReportManager, output adapters
│   ├── api/                        ← REST API (Pekko HTTP)
│   ├── entity/event/control/rollback/
│   │                               ← RollbackHistoryHandler, EventReadJournal,
│   │                                 LoggedEvent, MessageId, SentMessage, anti-messages
│   ├── runtime/                    ← EventOrder, OrderedInputQueue, execution limits
│   │   ├── accounting/             ← accounted transport and lifecycle protocol
│   │   ├── conservative/           ← conservative channel accounting protocol
│   │   └── distributed/            ← owner-ledger round protocol
│   ├── kafka/                      ← Kafka topic management
│   └── metrics/                    ← Prometheus MetricsServer
│
└── model/
    ├── mobility/                   ← retained data types, collections and utilities
    │                               ← legacy actors removed; hybrid actors are active
    │
    └── hybrid/                     ← [CURRENT] hybrid micro-meso model
        ├── actor/                  ← Car, Bus, Bicycle, Motorcycle, Subway,
        │                           │   Person, Link, RailLink, Node,
        │                           │   BusStop, BusStation, SubwayStation,
        │                           │   TrafficSignal, PrivateVehicle, Movable
        ├── entity/
        │   ├── state/              ← *State case classes (one per actor type)
        │   │   ├── MicroCarState, MicroBusState, MicroBicycleState …
        │   │   ├── MicroMovableState (trait)
        │   │   └── plan/           ← Person plan model: PlanElement, PlanCursor,
        │   │                       │   EndTimeSpec, LatenessPolicy, RemainingQueue
        │   └── event/data/         ← MicroEnterLinkData, MicroUpdateData …
        ├── decision/                ← ModeDecisionEngine + raptor/nearest-stop-utility/
        │                           │   travel-time engines, ModeDecisionEngineRegistry
        ├── micro/
        │   ├── model/              ← CarFollowingModel, KraussModel
        │   ├── lane/               ← LaneChangeModel, MobilLaneChange
        │   ├── strategy/           ← MicroSimulationStrategy, LaneChangeStrategy
        │   └── manager/            ← LinkMicroTimeManager
        └── util/
            ├── SpeedUtil.scala     ← MESO aggregate speed (BPR-like)
            ├── ModeChoiceUtil.scala
            ├── CityMapUtil.scala
            ├── TransitMapUtil.scala
            ├── DynamicWeightCache.scala
            ├── VehicleSimulationConfig.scala
            └── cache/              ← InMemory, Redis, Kafka cache strategies
```

---

## Documentation

| Document | Description |
|---|---|
| [Load Balancing and Attribution](docs/LOAD_BALANCING.md) | Distribution with/without runtime rebalancing, conservative migration scope, snapshots, review findings and research contribution assessment |
| [Applied Load-Balancing Literature](docs/LOAD_BALANCING_LITERATURE.md) | Mobility, spatial simulation and PDES papers mapped to HTC strategies, with matching limits and source-access notes |
| [Distributed Allocation Validation](docs/DISTRIBUTED_ALLOCATION_VALIDATION_2026_10_25.md) | Separate-JVM allocation ownership, migration and restore evidence, reproduction commands and remaining limits |
| [MICRO Validation Status](docs/MICRO_VALIDATION_STATUS.md) | Consolidated implementation, four scientific priorities, current results, configuration, limits and next acceptance gates |
| [MICRO Cross-Link Following](docs/MICRO_CROSS_LINK_FOLLOWING.md) | Leader observations, checkpoint/replay contract, v2 ablations, fresh demand results and reproduction |
| [MICRO Scientific Validation](docs/MICRO_SCIENTIFIC_VALIDATION.md) | Original v1 clock/convergence study, measurement definitions and retained field pilot |
| [Junction Geometry](docs/JUNCTION_GEOMETRY.md) | Fixed MICRO connectors, distance and turn-speed contract, and original geometry evidence |
| [Work Recovery](docs/WORK_RECOVERY_2026_10_24.md) | Accepted implementation/build references, evidence locations and recovered milestone history |
| [Distributed Time Warp Runtime](docs/DISTRIBUTED_OPTIMISTIC_RUNTIME.md) | Production LTM-owned accounting, rollback, GVT, commitment and supported scope |
| [Multi-JVM Validation](docs/MULTI_JVM_POLICY_VALIDATION_2026_10_24.md) | Cross-process transport, transit startup fixes, policy parity and worker-loss acceptance |
| [Bounded Optimistic Execution](docs/BOUNDED_OPTIMISTIC_EXECUTION.md) | Execution windows, event budgets and safe history collection |
| [Progressive Optimistic Input](docs/PROGRESSIVE_OPTIMISTIC_RUNTIME.md) | Correlated JSON/SQLite enrollment windows and accepted-input bounds |
| [Runtime Actor Birth](docs/RUNTIME_ACTOR_BIRTH.md) | Cancellable station-created Bus/Subway lifecycle |
| [Accounted Routing](docs/ACCOUNTED_ROUTING.md) | Dynamic costs and correlated route/mode queries through rollback-aware actors |
| [Walking and Mixed Trips](docs/WALKING_DISTANCE.md) | Measured walking, preserved multimodal distance, trip completion and physical validation |
| [Horizon and Transit Reporting](docs/HORIZON_AND_TRANSIT_REPORTING.md) | Passenger odometers, rail arrivals and censored unfinished journeys |
| [Extensible Time Warp Runtime](docs/TIME_WARP_EXTENSIBLE_RUNTIME.md) | Current core/model audit, conservative and optimistic architecture, implementation stages and acceptance gates |
| [Time Warp Validation](docs/TIME_WARP_VALIDATION_2026_10_22.md) | Regression tests and bounded scenario evidence for the runtime foundations |
| [Scenario Modeling Guide](docs/SCENARIO_MODELING.md) | Full JSON schema for all entity types, dependency graph, ID conventions |
| [Road Infrastructure](docs/ROAD_INFRASTRUCTURE.md) | Link (MESO/MICRO), Node, TrafficSignal internals |
| [Person Agent](docs/PERSON_AGENT.md) | Activity model, mode choice, trip lifecycle |
| [Bus Agent](docs/BUS_AGENT.md) | Bus route, boarding/alighting, MICRO support |
| [Bus Stop Agent](docs/BUS_STOP_AGENT.md) | Passenger queue management |
| [Bus Station Agent](docs/BUS_STATION_AGENT.md) | Route lifecycle, departure scheduling |
| [Subway Agent](docs/SUBWAY_AGENT.md) | Rail link navigation, platform interactions |
| [Subway Station Agent](docs/SUBWAY_STATION_AGENT.md) | Train spawning, headway management |
| [API Reference](docs/API.md) | REST API endpoints, request/response schemas |
| [ClickHouse Queries](docs/clickhouse-queries.md) | Sample analytical queries |
| [Known Gaps](docs/KNOWN_GAPS.md) | Honest audit: what's documented vs. what's actually implemented/tested |
| [Message Exchange Estimate](docs/MESSAGE_EXCHANGE_ESTIMATE.md) | Order-of-magnitude message-flow taxonomy and per-scenario estimate (São Paulo vs Toulouse) |

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -am 'Add my feature'`
4. Push and open a Pull Request

All new actor types should live under `model.hybrid`, extend `SimulationBaseActor`, and be
documented in the relevant `docs/` file.

---

## Academic Usage

This system was developed for research in **urban mobility** and **traffic simulation**.

```bibtex
@software{hyperbolic_time_chamber,
  title  = {Hyperbolic Time Chamber: Hybrid Micro-Meso Multi-Agent Traffic Simulation Framework},
  author = {Rocha, Francisco Wallison and Francesquini, Emilio and Cordeiro, Daniel},
  year   = {2025},
  url    = {https://github.com/fwrock/hyperbolic-time-chamber},
  note   = {Hybrid micro-meso simulation with activity-based Person agents}
}
```

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
