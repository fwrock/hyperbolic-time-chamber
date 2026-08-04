# Hyperbolic Time Chamber *(HTC)*

![HTC Banner](https://github.com/user-attachments/assets/dddd6245-f4bd-43fc-8888-6ef73d01a221)

**A distributed, event-driven multi-agent framework for urban mobility simulation and research**

Built with Scala 3 and Apache Pekko, HTC implements a **hybrid micro-meso simulation model** where each link in the road network can independently operate in mesoscopic (aggregate flow) or microscopic (individual car-following) mode — enabling city-scale simulation with high-fidelity corridors.

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

---

## Simulation Models

### Mesoscopic (MESO)

Each link maintains a **count** of registered vehicles and computes aggregate speed via a
BPR-like density model (`SpeedUtil.linkDensitySpeed`).  The link is **passive** — vehicles
schedule their own departure tick.  No per-vehicle position or velocity is tracked.

### Microscopic (MICRO)

Links in MICRO mode own a `LinkMicroTimeManager` that wakes up **every global tick** and
executes *N* sub-ticks (default 10 × 0.1 s).  Each sub-tick runs:

1. **Car-following** — Krauss model (default); pluggable via `CarFollowingModel` interface
2. **Lane-change** — MOBIL model; pluggable via `LaneChangeModel` interface
3. **Position/velocity update** — per vehicle per lane
4. **MicroUpdateData** sent to vehicle actor; `MicroLeaveLinkData` when position ≥ link length

### Hybrid Strategy

Every `Link` carries a `simulationMode: SimulationModeEnum` flag (`MESO` | `MICRO`).  **All
vehicle types** (Car, Bus, Bicycle, Motorcycle, Subway) adopt the mode of the link they enter.
A single city map can freely mix MESO (city-wide background) and MICRO (high-fidelity
corridors, BRT lanes, intersections).

#### Krauss safe-velocity formula

$$v_{safe} = -\tau b + \sqrt{(\tau b)^2 + v_{leader}^2 + 2 b \cdot gap}$$

where $\tau$ = reaction time (1.0 s), $b$ = max deceleration (4.5 m/s²).

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
- **Trip lifecycle** — activates vehicle → waits for `TripCompletedData` → advances the plan cursor
- **Statistics** — accumulates `totalDistanceTraveled` and `completedTrips`

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

# Code formatting
sbt scalafmt

# Lint check
sbt scalafix --check
```

**Docker (pre-built image):**

```bash
docker pull uxhabam/hyperbolic-time-chamber:2.3.2-beluga
docker run -e HTC_SIMULATION_CONFIG_FILE=/app/simulations/my_scenario/simulation.json \
           -v /path/to/scenarios:/app/simulations \
           uxhabam/hyperbolic-time-chamber:2.3.2-beluga
```

### Stack versions

| Component | Version |
|---|---|
| Scala | 3.3.5 |
| Java | 21+ |
| SBT | 1.x |
| Apache Pekko | 1.5.0 |
| Pekko HTTP | 1.3.0 |
| Pekko Management | 1.2.1 |
| Apache Kafka (connectors) | 1.1.0 |
| Apache Avro | 1.12.0 |
| Apache Parquet | 1.17.0 |
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
  "id": "htcaid:link;downtown_main_st",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "from": "htcaid:node;intersection_01",
      "to":   "htcaid:node;intersection_02",
      "length": 500.0,
      "lanes": 3,
      "speedLimit": 13.89,
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "laneConfigurations": [
        {"laneId": 0, "type": "normal"},
        {"laneId": 1, "type": "normal"},
        {"laneId": 2, "type": "bus_lane"}
      ]
    }
  }
}
```

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

**Dynamic routing weights** are published to Kafka and consumed by `DynamicWeightCache`
(in-memory, Redis, or Kafka-backed strategy).

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
│   ├── kafka/                      ← Kafka topic management
│   └── metrics/                    ← Prometheus MetricsServer
│
└── model/
    ├── mobility/                   ← [LEGACY] mesoscopic-only model
    │   └── actor/                  ← Car, Bus, Subway, Person, Link, Node …
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
