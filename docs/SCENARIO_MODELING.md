# Scenario Modeling Guide — Hybrid Urban Mobility Model

This guide explains how to define and structure a simulation scenario for the **Hybrid Micro-Meso Urban Mobility Model** in HTC. It covers every entity type, its JSON schema, the dependency graph between entities, and the `simulation.json` manifest that ties everything together.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Scenario File Structure](#2-scenario-file-structure)
3. [simulation.json — Manifest](#3-simulationjson--manifest)
4. [Entity Reference](#4-entity-reference)
   - [4.1 Node](#41-node)
   - [4.2 Link (Road)](#42-link-road)
   - [4.3 RailLink](#43-raillink)
   - [4.4 TrafficSignal](#44-trafficsignal)
   - [4.5 BusStop](#45-busstop)
   - [4.6 BusStation](#46-busstation)
   - [4.7 Bus](#47-bus)
   - [4.8 SubwayStation](#48-subwaystation)
   - [4.9 Subway (Train)](#49-subway-train)
   - [4.10 Car](#410-car)
   - [4.11 Person](#411-person)
5. [city_map.json](#5-city_mapjson)
6. [ID Naming Conventions](#6-id-naming-conventions)
7. [Dependency Graph](#7-dependency-graph)
8. [Loading Strategies and Lifecycle](#8-loading-strategies-and-lifecycle)
9. [Simulation Modes: MESO vs MICRO](#9-simulation-modes-meso-vs-micro)
10. [Minimal Working Example](#10-minimal-working-example)
11. [Scaling Data Files](#11-scaling-data-files)
12. [Generating Scenarios from Real Data](#12-generating-scenarios-from-real-data)
13. [Dynamic Mode Choice](#13-dynamic-mode-choice)

---

## 1. Overview

A HTC hybrid scenario is a **collection of JSON files** that define the road network, transit infrastructure, and agents (persons and vehicles). The simulator reads these files at startup, instantiates one actor per entity, and then runs the discrete-event simulation.

The entity types available in the hybrid model are:

| Actor class | Role |
|---|---|
| `hybrid.actor.Node` | Road / rail intersection or endpoint |
| `hybrid.actor.Link` | Directed road segment (MESO or MICRO) |
| `hybrid.actor.RailLink` | Directed rail segment (subway-only) |
| `hybrid.actor.TrafficSignal` | Traffic signal controller |
| `hybrid.actor.BusStop` | Physical bus stop (passengers wait here) |
| `hybrid.actor.BusStation` | Bus route manager (spawns Bus actors) |
| `hybrid.actor.Bus` | Bus vehicle following a fixed route |
| `hybrid.actor.SubwayStation` | Subway station (spawns Subway actors) |
| `hybrid.actor.Subway` | Subway/metro train following rail links |
| `hybrid.actor.Car` | Private car (activated by Person) |
| `hybrid.actor.Person` | Agent with a daily activity schedule |

---

## 2. Scenario File Structure

```
<scenario_name>/
├── simulation.json            ← manifest (required)
├── scenario_metadata.json     ← metadata/statistics (optional)
├── GENERATION_REPORT.md       ← human-readable generation report (optional)
└── data/
    ├── city_map.json          ← in-memory graph (required)
    ├── transit_map.json       ← transit stop index for dynamic mode choice (optional)
    ├── nodes_0.json           ← Node actors (may be split into multiple files)
    ├── nodes_1.json
    ├── links_0.json           ← Link actors (may be split)
    ├── links_1.json
    ├── rail_links_0.json      ← RailLink actors
    ├── traffic_signals_0.json ← TrafficSignal actors
    ├── bus_stops_0.json       ← BusStop actors
    ├── bus_stations_0.json    ← BusStation actors
    ├── buses_0.json           ← Bus actors (pre-defined departures)
    ├── subway_stations_0.json ← SubwayStation actors
    ├── persons_0.json         ← Person agents
    └── cars_0.json            ← Car assets (owned by Persons)
```

> **Note:** Large entity sets (e.g., nodes, links) can be split across numbered files (`nodes_0.json`, `nodes_1.json`, …). Each file is registered separately in `simulation.json`.

---

## 3. simulation.json — Manifest

`simulation.json` is the single entry point. It defines simulation timing and lists every data source.

```json
{
  "id": "my_scenario",
  "name": "My City Scenario",
  "description": "A description of the scenario.",
  "startTick": 0,
  "endTick": 86400,
  "startRealTime": "2025-01-01T00:00:00.000",
  "timeUnit": "seconds",
  "timeStep": 1.0,
  "duration": 86400,
  "tickDuration": 1.0,
  "randomSeed": 42,
  "cityMapFile": "/app/.../data/city_map.json",
  "postLoadRegistrationClasses": [
    "org.interscity.htc.model.hybrid.actor.BusStop",
    "org.interscity.htc.model.hybrid.actor.BusStation",
    "org.interscity.htc.model.hybrid.actor.SubwayStation"
  ],
  "actorsDataSources": [
    {
      "id": "nodes_0",
      "classType": "hybrid.actor.Node",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "EAGER",
      "entityLifecycle": "STATIC",
      "dataSource": {
        "sourceType": "json",
        "info": { "path": "/app/.../data/nodes_0.json" }
      }
    },
    {
      "id": "links_0",
      "classType": "hybrid.actor.Link",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "EAGER",
      "entityLifecycle": "STATIC",
      "dataSource": {
        "sourceType": "json",
        "info": { "path": "/app/.../data/links_0.json" }
      }
    },
    {
      "id": "persons_0",
      "classType": "hybrid.actor.Person",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "PROGRESSIVE",
      "entityLifecycle": "DYNAMIC",
      "dataSource": {
        "sourceType": "json",
        "info": { "path": "/app/.../data/persons_0.json" }
      }
    }
  ]
}
```

### Key fields

| Field | Description |
|---|---|
| `startTick` / `endTick` | Simulation time window (usually `0` to `86400` for a full day in seconds) |
| `tickDuration` | Duration of one tick in real-world seconds (`1.0` = 1 s/tick) |
| `cityMapFile` | Path to the in-memory road graph used for routing |
| `postLoadRegistrationClasses` | Classes that must register with their parent node after load (BusStop, BusStation, SubwayStation) |
| `loadingStrategy` | `EAGER` = load at startup; `PROGRESSIVE` = load on demand |
| `entityLifecycle` | `STATIC` = actor lives for the full simulation; `DYNAMIC` = actor may be created/destroyed |

---

## 4. Entity Reference

Every entity follows the same envelope schema:

```json
{
  "id": "htcaid:<type>;<unique-identifier>",
  "typeActor": "hybrid.actor.<ActorClass>",
  "data": {
    "dataType": "model.hybrid.entity.state.<StateClass>",
    "content": { ... }
  },
  "dependencies": { ... }
}
```

---

### 4.1 Node

Represents a road intersection, endpoint, or any geographic point in the network.

```json
{
  "id": "htcaid:node;42",
  "typeActor": "hybrid.actor.Node",
  "data": {
    "dataType": "model.hybrid.entity.state.NodeState",
    "content": {
      "startTick": 0,
      "latitude": 7393121.10,
      "longitude": 326090.09,
      "links": [],
      "connections": {},
      "signals": {},
      "busStops": {},
      "subwayStations": {},
      "hasHybridConnections": false,
      "conflictZones": [],
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {}
}
```

**Content fields:**

| Field | Type | Description |
|---|---|---|
| `latitude` / `longitude` | `Double` | Coordinates in the project's CRS (UTM metres recommended) |
| `links` | `List[String]` | Outgoing link IDs (populated at runtime by links registering themselves) |
| `connections` | `Map[String, Identify]` | Link-to-node routing table (populated at runtime) |
| `signals` | `Map[String, SignalState]` | Current signal state per incoming link (updated by TrafficSignal) |
| `busStops` | `Map[String, Identify]` | Bus stops registered at this node |
| `subwayStations` | `Map[String, Identify]` | Subway stations registered at this node |
| `hasHybridConnections` | `Boolean` | `true` if any adjacent link is in MICRO mode |
| `scheduleOnTimeManager` | `Boolean` | Usually `false` for static infrastructure nodes |

> **Tip:** `latitude`/`longitude` are used for routing and GPS calculations. Keep them consistent with the CRS used in `city_map.json`.

---

### 4.2 Link (Road)

A directed road segment connecting two nodes. Supports both MESO (aggregate) and MICRO (individual vehicle) simulation.

```json
{
  "id": "htcaid:link;1001",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "startTick": 0,
      "from": "htcaid:node;10",
      "to": "htcaid:node;11",
      "length": 250.0,
      "lanes": 2,
      "speedLimit": 50.0,
      "freeSpeed": 50.0,
      "capacity": 1800.0,
      "simulationMode": "MESO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "laneConfigurations": [
        { "laneId": 0, "type": "normal", "width": 3.5, "speedLimit": null },
        { "laneId": 1, "type": "normal", "width": 3.5, "speedLimit": null }
      ],
      "linkType": "secondary",
      "congestionFactor": 1.0,
      "currentSpeed": 50.0,
      "registered": [],
      "vehiclesByLane": {},
      "modes": ["car", "car_passenger"],
      "name": "Avenida Paulista"
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;10", "classType": "hybrid.actor.Node" },
    "to_node":   { "id": "htcaid:node;11", "classType": "hybrid.actor.Node" }
  }
}
```

**Content fields:**

| Field | Type | Description |
|---|---|---|
| `from` / `to` | `String` | IDs of the source and destination nodes |
| `length` | `Double` | Segment length in metres |
| `lanes` | `Int` | Number of lanes |
| `speedLimit` | `Double` | Legal speed limit in km/h |
| `freeSpeed` | `Double` | Free-flow speed in km/h (often equal to `speedLimit`) |
| `capacity` | `Double` | Maximum vehicles per hour |
| `simulationMode` | `"MESO"` \| `"MICRO"` | Simulation mode for this link |
| `microTimeStep` | `Double` | Sub-tick duration in seconds (used in MICRO mode) |
| `microTicksPerGlobalTick` | `Int` | Sub-ticks per global tick (e.g., `10` → 0.1 s resolution) |
| `laneConfigurations` | `List` | Per-lane configuration. Types: `normal`, `bus_lane`, `bike_lane` |
| `linkType` | `String` | OSM highway type (`motorway`, `primary`, `secondary`, `residential`, …) |
| `congestionFactor` | `Double` | Multiplier for congestion calculations (`1.0` = no bias) |
| `modes` | `List[String]` | Allowed vehicle modes (`car`, `bus`, `bicycle`, …) |

**Dependencies:** Both `from_node` and `to_node` must reference valid Node IDs.

---

### 4.3 RailLink

A directed rail segment used **exclusively** by Subway actors. Road vehicles are rejected by the actor.

```json
{
  "id": "htcaid:rail_link;line_1_segment_0",
  "typeActor": "hybrid.actor.RailLink",
  "data": {
    "dataType": "model.hybrid.entity.state.RailLinkState",
    "content": {
      "from": "htcaid:node;100",
      "to": "htcaid:node;101",
      "length": 1200.0,
      "lanes": 2,
      "speedLimit": 80.0,
      "capacity": 10.0,
      "freeSpeed": 80.0,
      "railType": "SUBWAY",
      "subwayLine": "line_1",
      "fromStation": "htcaid:subwaystation;station_A",
      "toStation": "htcaid:subwaystation;station_B",
      "gradient": 0.0,
      "curvature": 0.0,
      "simulationMode": "MESO",
      "laneConfigurations": [],
      "vehiclesByLane": {},
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "registered": [],
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {
    "from_node":    { "id": "htcaid:node;100",               "classType": "hybrid.actor.Node" },
    "to_node":      { "id": "htcaid:node;101",               "classType": "hybrid.actor.Node" },
    "from_station": { "id": "htcaid:subwaystation;station_A", "classType": "hybrid.actor.SubwayStation" },
    "to_station":   { "id": "htcaid:subwaystation;station_B", "classType": "hybrid.actor.SubwayStation" }
  }
}
```

| Field | Description |
|---|---|
| `railType` | Always `"SUBWAY"` for metro/subway lines |
| `subwayLine` | Line identifier — must match the line key in SubwayStation's `lines` map |
| `fromStation` / `toStation` | SubwayStation IDs at each end |
| `gradient` / `curvature` | Physical properties affecting speed (set `0.0` if unknown) |

---

### 4.4 TrafficSignal

Controls the green/red phases for a set of links at one or more nodes.

```json
{
  "id": "htcaid:signal;signal_42",
  "typeActor": "hybrid.actor.TrafficSignal",
  "data": {
    "dataType": "model.hybrid.entity.state.TrafficSignalState",
    "content": {
      "startTick": 0,
      "cycleDuration": 120,
      "offset": 30,
      "nodes": ["htcaid:node;200"],
      "phases": [
        {
          "phaseId": 0,
          "duration": 60,
          "greenLinks": ["htcaid:link;2001"],
          "yellowDuration": 3,
          "allRedDuration": 2
        },
        {
          "phaseId": 1,
          "duration": 60,
          "greenLinks": ["htcaid:link;2002"],
          "yellowDuration": 3,
          "allRedDuration": 2
        }
      ],
      "signalStates": {
        "htcaid:link;2001": { "state": "Red", "timeInState": 0 },
        "htcaid:link;2002": { "state": "Red", "timeInState": 0 }
      }
    }
  },
  "dependencies": {
    "node": { "id": "htcaid:node;200", "classType": "hybrid.actor.Node" }
  }
}
```

| Field | Description |
|---|---|
| `cycleDuration` | Total cycle length in ticks (seconds) |
| `offset` | Phase offset in ticks — used to stagger signals on a corridor |
| `phases[].greenLinks` | Links that receive green during this phase |
| `phases[].duration` | Duration of the phase in ticks |
| `signalStates` | Initial state for each controlled link (usually all `"Red"`) |

---

### 4.5 BusStop

A physical stop where passengers board and alight. One BusStop per stop per direction.

```json
{
  "id": "htcaid:busstop;stop_001",
  "typeActor": "hybrid.actor.BusStop",
  "data": {
    "dataType": "model.hybrid.entity.state.BusStopState",
    "content": {
      "nodeId": "htcaid:node;300",
      "label": "Central Station",
      "people": {}
    }
  },
  "dependencies": {
    "node": { "id": "htcaid:node;300", "classType": "hybrid.actor.Node" }
  }
}
```

| Field | Description |
|---|---|
| `nodeId` | The node where this stop is physically located |
| `label` | Human-readable name — must match the route label used in BusStation |
| `people` | Passenger queues per bus label (populated at runtime) |

> **Important:** `label` is the key that connects BusStop ↔ BusStation ↔ Bus. All three must use the same label string for a given route.

---

### 4.6 BusStation

The route manager for a bus line. Spawns Bus actors according to a schedule and computes routes.

```json
{
  "id": "htcaid:busstation;route_42",
  "typeActor": "hybrid.actor.BusStation",
  "data": {
    "dataType": "model.hybrid.entity.state.BusStationState",
    "content": {
      "startTick": 25140,
      "name": "Route 42 (operator)",
      "origin": "htcaid:node;300",
      "destination": "htcaid:node;350",
      "interval": 1200,
      "busStops": {
        "htcaid:busstop;stop_001": "htcaid:node;300",
        "htcaid:busstop;stop_002": "htcaid:node;310",
        "htcaid:busstop;stop_003": "htcaid:node;350"
      },
      "buses": [
        {
          "actorId": "htcaid:bus;route_42_bus_0",
          "capacity": 80,
          "size": 12.0,
          "numberOfPorts": 2,
          "label": "42"
        }
      ],
      "goingRoute": null,
      "goingBestCost": 1000000000000.0,
      "returningRoute": null,
      "returningBestCost": 1000000000000.0,
      "status": "Start"
    }
  },
  "dependencies": {
    "htcaid:node;300": { "id": "htcaid:node;300", "classType": "hybrid.actor.Node" },
    "htcaid:node;310": { "id": "htcaid:node;310", "classType": "hybrid.actor.Node" },
    "htcaid:node;350": { "id": "htcaid:node;350", "classType": "hybrid.actor.Node" }
  }
}
```

| Field | Description |
|---|---|
| `startTick` | First dispatch tick |
| `interval` | Headway between successive dispatches (seconds/ticks) |
| `busStops` | Map from BusStop ID → Node ID for all stops on the route |
| `buses` | Pre-defined bus definitions (one entry per bus; multiple entries for multiple initial buses) |
| `buses[].label` | Route label — **must match** `BusStop.label` and `Bus.label` |
| `goingRoute` / `returningRoute` | Computed at runtime; set to `null` initially |

**Dependencies:** Every Node referenced in `busStops` values must appear in `dependencies`.

---

### 4.7 Bus

A bus vehicle. Usually created dynamically by BusStation, but can be pre-defined in a JSON file.

```json
{
  "id": "htcaid:bus;route_42_bus_0",
  "typeActor": "hybrid.actor.Bus",
  "data": {
    "dataType": "model.hybrid.entity.state.BusState",
    "content": {
      "startTick": 25200,
      "label": "42",
      "capacity": 80,
      "origin": "htcaid:node;300",
      "destination": "htcaid:node;350",
      "routeId": "42",
      "stopSequence": [
        { "stopId": "htcaid:busstop;stop_001", "node": "htcaid:node;300", "arrivalOffset": 0,   "departureOffset": 0 },
        { "stopId": "htcaid:busstop;stop_002", "node": "htcaid:node;310", "arrivalOffset": 120, "departureOffset": 120 },
        { "stopId": "htcaid:busstop;stop_003", "node": "htcaid:node;350", "arrivalOffset": 240, "departureOffset": 240 }
      ],
      "bestRoute": null,
      "currentNode": null,
      "distance": 0.0,
      "status": "Start"
    }
  },
  "dependencies": {}
}
```

| Field | Description |
|---|---|
| `label` | Route label — shared with BusStop and BusStation |
| `capacity` | Maximum passenger count |
| `stopSequence` | Ordered list of stops with arrival/departure offsets in seconds |
| `stopSequence[].arrivalOffset` | Seconds after `startTick` to arrive at this stop |

---

### 4.8 SubwayStation

Manages one or more subway lines passing through a station. Spawns Subway (train) actors.

```json
{
  "id": "htcaid:subwaystation;station_A",
  "typeActor": "hybrid.actor.SubwayStation",
  "data": {
    "dataType": "model.hybrid.entity.state.SubwayStationState",
    "content": {
      "startTick": 0,
      "name": "Central",
      "nodeId": "htcaid:node;100",
      "terminal": true,
      "garage": true,
      "lines": {
        "line_1": { "interval": 300, "nextTick": 0 }
      },
      "subways": {
        "line_1": [
          {
            "line": "line_1",
            "actorId": "htcaid:subway;line_1_fwd_train_0",
            "capacity": 1200,
            "numberOfPorts": 8,
            "velocity": 22.22,
            "stopTime": 30
          }
        ]
      },
      "linesRoute": {
        "line_1": [
          {
            "stationNode": {
              "stationId": "htcaid:subwaystation;station_B",
              "nodeId": "htcaid:node;101"
            },
            "railLinkId": "htcaid:rail_link;line_1_segment_0"
          }
        ]
      },
      "people": {},
      "status": "Start"
    }
  },
  "dependencies": {
    "node": { "id": "htcaid:node;100", "classType": "hybrid.actor.Node" }
  }
}
```

| Field | Description |
|---|---|
| `terminal` | `true` if this is the origin terminus of the line |
| `garage` | `true` if trains are stored here (dispatched from here) |
| `lines` | Map of line ID → `{interval, nextTick}` |
| `subways` | Map of line ID → list of train definitions to dispatch |
| `subways[].velocity` | Cruising speed in m/s (e.g., `22.22` ≈ 80 km/h) |
| `subways[].stopTime` | Dwell time at each station in seconds |
| `linesRoute` | Ordered list of `{stationNode, railLinkId}` pairs forming the line's path |

---

### 4.9 Subway (Train)

A subway train. Created dynamically by SubwayStation; can also be pre-defined.

The train state is primarily managed by the SubwayStation that spawns it. Pre-defined entries in a JSON file are rarely needed — the SubwayStation's `subways` field is sufficient.

---

### 4.10 Car

A private car **asset** owned by a Person. Sits **Parked** until the owning Person sends a `StartTrip` message.

```json
{
  "id": "htcaid:car;person_42_v_car",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "startTick": 28800,
      "origin": "htcaid:node;500",
      "destination": "htcaid:node;500",
      "actorType": "Car",
      "size": 4.5,
      "currentSimulationMode": "MESO",
      "microState": null,
      "status": "Parked",
      "bestRoute": null,
      "currentNode": "htcaid:node;500",
      "distance": 0.0,
      "eventCount": 0,
      "driverAttributes": {
        "aggressiveness": 0.5,
        "reactionTimeFactor": 1.0,
        "speedFactor": 1.0,
        "minGapFactor": 1.0
      },
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;500", "classType": "hybrid.actor.Node" },
    "to_node":   { "id": "htcaid:node;500", "classType": "hybrid.actor.Node" }
  }
}
```

| Field | Description |
|---|---|
| `status` | Always `"Parked"` at creation |
| `origin` / `destination` | Parking location node (both set to the same node initially) |
| `driverAttributes.aggressiveness` | `[0, 1]` — influences gap acceptance and lane changes |
| `driverAttributes.reactionTimeFactor` | Multiplier on the base reaction time (default base = 1 s) |
| `driverAttributes.speedFactor` | Multiplier on the desired speed |
| `driverAttributes.minGapFactor` | Multiplier on the minimum gap |
| `scheduleOnTimeManager` | `false` — the car wakes up only when Person activates it |

---

### 4.11 Person

An agent with a **daily activity schedule**. Manages mode choice and activates vehicles for each trip.

```json
{
  "id": "htcaid:person;42",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "home",
          "nodeId": "htcaid:node;500",
          "endTime": "28800",
          "arrivalLogistics": null
        },
        {
          "sequence": 1,
          "activityType": "work",
          "nodeId": "htcaid:node;600",
          "endTime": "64800",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": { "id": "htcaid:car;person_42_v_car", "classType": "hybrid.actor.Car" },
            "driverAttributes": {
              "aggressiveness": 0.5,
              "maxSpeedFactor": 1.0,
              "reactionTime": 1.0,
              "minGapFactor": 1.0
            }
          }
        },
        {
          "sequence": 2,
          "activityType": "home",
          "nodeId": "htcaid:node;500",
          "endTime": "86400",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": { "id": "htcaid:car;person_42_v_car", "classType": "hybrid.actor.Car" },
            "driverAttributes": {
              "aggressiveness": 0.6,
              "maxSpeedFactor": 0.95,
              "reactionTime": 1.1,
              "minGapFactor": 1.05
            }
          }
        }
      ],
      "currentActivityIndex": 0,
      "ownedVehicles": {
        "car": { "id": "htcaid:car;person_42_v_car", "classType": "hybrid.actor.Car" }
      },
      "currentTripVehicleId": null,
      "currentTripStartTick": null,
      "totalDistanceTraveled": 0.0,
      "completedTrips": 0
    }
  },
  "dependencies": {}
}
```

**Activity fields:**

| Field | Description |
|---|---|
| `sequence` | Zero-based activity index |
| `activityType` | Any label: `"home"`, `"work"`, `"school"`, `"leisure"`, … |
| `nodeId` | Location node for this activity |
| `endTime` | Tick at which the person leaves for the **next** activity. Last activity uses `"86400"` (or simulation end). Set to `"0"` if no return trip is defined. |
| `arrivalLogistics` | `null` for the first activity; non-null for subsequent activities |

**Arrival logistics — mode values:**

| `mode` | Description |
|---|---|
| `"car"` | Uses the owned Car asset |
| `"pt"` | Public transport (bus or subway, resolved by routing) |
| `"walk"` | Walking (mesoscopic, no vehicle) |
| `"bicycle"` | Uses owned Bicycle asset |
| `"motorcycle"` | Uses owned Motorcycle asset |

**`ownedVehicles`:** Map from mode string to the vehicle's `Identify`. Required when `mode` is `"car"`, `"bicycle"`, or `"motorcycle"`.

**Dynamic mode choice fields** (optional — only relevant when `enableDynamicModeChoice: true`):

| Field | Type | Default | Description |
|---|---|---|---|
| `enableDynamicModeChoice` | `Boolean` | `false` | Enable utility-based mode re-evaluation at each departure |
| `modeChoiceWeights.betaMode` | `Double` | `1.0` | Scale applied to mode preference score |
| `modeChoiceWeights.betaAccess` | `Double` | `0.001` | Per-metre penalty for walking to a boarding stop |
| `modeChoiceWeights.betaEgress` | `Double` | `0.001` | Per-metre penalty for walking from an alighting stop |
| `modeChoiceWeights.modePrefSubway` | `Double` | `2.0` | Intrinsic preference score for subway |
| `modeChoiceWeights.modePrefBus` | `Double` | `1.0` | Intrinsic preference score for bus |
| `modeChoiceWeights.modePrefWalk` | `Double` | `0.0` | Intrinsic preference score for walking (reference) |
| `modeChoiceWeights.maxAccessDistanceM` | `Double` | `1500.0` | Max haversine radius (m) to search for stops |
| `modeChoiceWeights.maxWalkDistanceM` | `Double` | `2000.0` | Max O→D distance (m) for walking to be a candidate |

**`arrivalLogistics.fixedMode`:** when `true`, the leg is never re-evaluated even if `enableDynamicModeChoice` is active on the Person. Useful for car trips that must always remain as car regardless of transit availability.

---

## 5. city_map.json and transit_map.json

`city_map.json` is the **in-memory routing graph** loaded at simulator startup. Every router query (`GPSUtil.calcRoute`) uses this graph. It must contain all nodes and directed edges (links) present in the scenario.

Minimal structure (exact schema depends on the routing implementation):

```json
{
  "nodes": {
    "htcaid:node;10": { "lat": 7393000.0, "lon": 326000.0 },
    "htcaid:node;11": { "lat": 7393250.0, "lon": 326000.0 }
  },
  "edges": [
    {
      "id": "htcaid:link;1001",
      "from": "htcaid:node;10",
      "to": "htcaid:node;11",
      "weight": 250.0,
      "length": 250.0
    }
  ]
}
```

> The `weight` field is used by the shortest-path algorithm; it can be the physical `length` or a generalised cost.

### transit_map.json

`transit_map.json` is an **optional** flat JSON array of transit access points (bus stops and subway stations) used exclusively by the [dynamic mode choice](#13-dynamic-mode-choice) system. It is **not** required for static-schedule simulations.

The file is configured via:
- Environment variable: `HTC_MOBILITY_TRANSIT_MAP_FILE`
- Config key: `htc.mobility.transit-map-file`

If neither is set, `TransitMapUtil.isAvailable` returns `false` and the system falls back to static logistics silently.

**Schema (flat array — no wrapper object):**

```json
[
  {
    "id": "htcaid:stop;bus_stop_123",
    "actorId": "htcaid:busstop;stop_001",
    "actorClassType": "hybrid.actor.BusStop",
    "nodeId": "htcaid:node;300",
    "latitude": -23.5505,
    "longitude": -46.6333,
    "stopType": "bus",
    "lines": ["42", "68"]
  },
  {
    "id": "htcaid:stop;metro_central",
    "actorId": "htcaid:subwaystation;station_A",
    "actorClassType": "hybrid.actor.SubwayStation",
    "nodeId": "htcaid:node;100",
    "latitude": -23.5461,
    "longitude": -46.6388,
    "stopType": "subway",
    "lines": ["blue_line"]
  }
]
```

**Field reference:**

| Field | Description |
|---|---|
| `id` | Unique stop identifier (used internally by `TransitMapUtil`) |
| `actorId` | Entity ID of the BusStop or SubwayStation actor — used for `RegisterPassenger` messages |
| `actorClassType` | Shard class type for actor routing (`hybrid.actor.BusStop` / `hybrid.actor.SubwayStation`) |
| `nodeId` | Road-network node ID where the stop is located |
| `latitude` / `longitude` | WGS-84 coordinates — used for haversine distance calculations |
| `stopType` | `"bus"` or `"subway"` |
| `lines` | List of line labels served — must match `BusStop.label` / `SubwayStation.lines` keys |

> **Note:** Distances between stops and origin/destination points are computed using the **haversine formula** (great-circle distance) rather than network Dijkstra, which makes the transit index an order of magnitude cheaper to query than full routing.

---

## 6. ID Naming Conventions

All IDs use the `htcaid:<type>;<identifier>` pattern:

| Entity | Pattern | Example |
|---|---|---|
| Node | `htcaid:node;<osm_id>` | `htcaid:node;573641` |
| Link | `htcaid:link;<numeric_id>` | `htcaid:link;1001` |
| Rail link | `htcaid:rail_link;<line>_segment_<n>` | `htcaid:rail_link;line_1_segment_0` |
| Traffic signal | `htcaid:signal;signal_<n>` | `htcaid:signal;signal_42` |
| Bus stop | `htcaid:busstop;<operator>_<stop_id>` | `htcaid:busstop;sptrans_18848` |
| Bus station | `htcaid:busstation;<route_id>` | `htcaid:busstation;route_42` |
| Bus | `htcaid:bus;<route_id>_bus_<n>` | `htcaid:bus;route_42_bus_0` |
| Subway station | `htcaid:subwaystation;station_<osm_id>` | `htcaid:subwaystation;station_A` |
| Subway | `htcaid:subway;<line>_fwd_train_<n>` | `htcaid:subway;line_1_fwd_train_0` |
| Car | `htcaid:car;<person_id>_v_car` | `htcaid:car;person_42_v_car` |
| Person | `htcaid:person;<person_id>` | `htcaid:person;42` |

---

## 7. Dependency Graph

The figure below shows which entities must exist before another can be fully initialised:

```
Node ←── Link (from_node, to_node)
Node ←── RailLink (from_node, to_node, from_station, to_station)
Node ←── TrafficSignal (node)
Node ←── BusStop (node)      ──── [post-load] ──→ Node registers BusStop
Node ←── BusStation (nodes)  ──── [post-load] ──→ Node registers BusStation
Node ←── SubwayStation (node) ─── [post-load] ──→ Node registers SubwayStation
Car  ←── Person (ownedVehicles)
```

**Loading order that satisfies all dependencies:**

1. `Node` (EAGER)
2. `Link`, `RailLink`, `TrafficSignal` (EAGER)
3. `BusStop`, `BusStation`, `SubwayStation` (EAGER, post-load registration)
4. `Bus` (pre-defined departures, PROGRESSIVE)
5. `Car`, `Bicycle`, `Motorcycle` (PROGRESSIVE, `Parked` until Person activates)
6. `Person` (PROGRESSIVE)

---

## 8. Loading Strategies and Lifecycle

| Strategy | Description | Typical use |
|---|---|---|
| `EAGER` | All actors loaded at simulation startup | Infrastructure (nodes, links, signals) |
| `PROGRESSIVE` | Actors loaded in batches as needed | Agents (persons, vehicles) |

| Lifecycle | Description | Typical use |
|---|---|---|
| `STATIC` | Actor exists for the full simulation | Infrastructure |
| `DYNAMIC` | Actor can be created or destroyed mid-simulation | Vehicles, persons |

---

## 9. Simulation Modes: MESO vs MICRO

Each Link independently selects its simulation mode via the `simulationMode` field.

| Mode | Behaviour | When to use |
|---|---|---|
| `"MESO"` | Aggregate speed-density model; vehicles move through the link in bulk | City-scale networks, outer areas |
| `"MICRO"` | Car-following (Krauss model) + lane changes; individual vehicle dynamics | Corridors of interest, downtown areas, BRT lanes |

**Enabling MICRO on a link:**

```json
{
  "simulationMode": "MICRO",
  "microTimeStep": 0.1,
  "microTicksPerGlobalTick": 10,
  "laneConfigurations": [
    { "laneId": 0, "type": "normal",   "width": 3.5, "speedLimit": null },
    { "laneId": 1, "type": "bus_lane", "width": 3.5, "speedLimit": 60.0 }
  ]
}
```

When a link is in MICRO mode, set `hasHybridConnections: true` on any adjacent Node.

**Lane types:**

| Type | Description |
|---|---|
| `"normal"` | Mixed traffic |
| `"bus_lane"` | Restricted to buses |
| `"bike_lane"` | Restricted to bicycles |

---

## 10. Minimal Working Example

The following is the smallest possible scenario — two nodes, one link, one car, one person — that exercises the full hybrid pipeline.

### Directory layout

```
my_scenario/
├── simulation.json
└── data/
    ├── city_map.json
    ├── nodes.json
    ├── links.json
    ├── cars.json
    └── persons.json
```

### data/nodes.json

```json
[
  {
    "id": "htcaid:node;1",
    "typeActor": "hybrid.actor.Node",
    "data": {
      "dataType": "model.hybrid.entity.state.NodeState",
      "content": {
        "startTick": 0, "latitude": 7393000.0, "longitude": 326000.0,
        "links": [], "connections": {}, "signals": {},
        "busStops": {}, "subwayStations": {},
        "hasHybridConnections": false, "conflictZones": [],
        "scheduleOnTimeManager": false
      }
    },
    "dependencies": {}
  },
  {
    "id": "htcaid:node;2",
    "typeActor": "hybrid.actor.Node",
    "data": {
      "dataType": "model.hybrid.entity.state.NodeState",
      "content": {
        "startTick": 0, "latitude": 7393500.0, "longitude": 326000.0,
        "links": [], "connections": {}, "signals": {},
        "busStops": {}, "subwayStations": {},
        "hasHybridConnections": false, "conflictZones": [],
        "scheduleOnTimeManager": false
      }
    },
    "dependencies": {}
  }
]
```

### data/links.json

```json
[
  {
    "id": "htcaid:link;1",
    "typeActor": "hybrid.actor.Link",
    "data": {
      "dataType": "model.hybrid.entity.state.LinkState",
      "content": {
        "startTick": 0,
        "from": "htcaid:node;1", "to": "htcaid:node;2",
        "length": 500.0, "lanes": 2,
        "speedLimit": 50.0, "freeSpeed": 50.0, "capacity": 1800.0,
        "simulationMode": "MESO",
        "microTimeStep": 0.1, "microTicksPerGlobalTick": 10,
        "laneConfigurations": [
          { "laneId": 0, "type": "normal", "width": 3.5, "speedLimit": null },
          { "laneId": 1, "type": "normal", "width": 3.5, "speedLimit": null }
        ],
        "linkType": "primary", "congestionFactor": 1.0,
        "currentSpeed": 50.0, "registered": [], "vehiclesByLane": {},
        "modes": ["car", "car_passenger"], "name": "Main Street"
      }
    },
    "dependencies": {
      "from_node": { "id": "htcaid:node;1", "classType": "hybrid.actor.Node" },
      "to_node":   { "id": "htcaid:node;2", "classType": "hybrid.actor.Node" }
    }
  }
]
```

### data/cars.json

```json
[
  {
    "id": "htcaid:car;p1_v_car",
    "typeActor": "hybrid.actor.Car",
    "data": {
      "dataType": "model.hybrid.entity.state.CarState",
      "content": {
        "startTick": 28800, "origin": "htcaid:node;1", "destination": "htcaid:node;1",
        "actorType": "Car", "size": 4.5,
        "currentSimulationMode": "MESO", "microState": null, "status": "Parked",
        "bestRoute": null, "currentNode": "htcaid:node;1",
        "distance": 0.0, "eventCount": 0,
        "driverAttributes": {
          "aggressiveness": 0.5, "reactionTimeFactor": 1.0,
          "speedFactor": 1.0, "minGapFactor": 1.0
        },
        "scheduleOnTimeManager": false
      }
    },
    "dependencies": {
      "from_node": { "id": "htcaid:node;1", "classType": "hybrid.actor.Node" },
      "to_node":   { "id": "htcaid:node;1", "classType": "hybrid.actor.Node" }
    }
  }
]
```

### data/persons.json

```json
[
  {
    "id": "htcaid:person;1",
    "typeActor": "hybrid.actor.Person",
    "data": {
      "dataType": "model.hybrid.entity.state.PersonState",
      "content": {
        "dailySchedule": [
          {
            "sequence": 0,
            "activityType": "home",
            "nodeId": "htcaid:node;1",
            "endTime": "28800",
            "arrivalLogistics": null
          },
          {
            "sequence": 1,
            "activityType": "work",
            "nodeId": "htcaid:node;2",
            "endTime": "86400",
            "arrivalLogistics": {
              "mode": "car",
              "vehicle": { "id": "htcaid:car;p1_v_car", "classType": "hybrid.actor.Car" },
              "driverAttributes": {
                "aggressiveness": 0.5, "maxSpeedFactor": 1.0,
                "reactionTime": 1.0, "minGapFactor": 1.0
              }
            }
          }
        ],
        "currentActivityIndex": 0,
        "ownedVehicles": {
          "car": { "id": "htcaid:car;p1_v_car", "classType": "hybrid.actor.Car" }
        },
        "currentTripVehicleId": null, "currentTripStartTick": null,
        "totalDistanceTraveled": 0.0, "completedTrips": 0
      }
    },
    "dependencies": {}
  }
]
```

### simulation.json

```json
{
  "id": "my_scenario",
  "name": "Minimal Example",
  "description": "Two-node, one-link, one-car, one-person scenario.",
  "startTick": 0,
  "endTick": 86400,
  "startRealTime": "2025-01-01T00:00:00.000",
  "timeUnit": "seconds",
  "timeStep": 1.0,
  "duration": 86400,
  "tickDuration": 1.0,
  "randomSeed": 42,
  "cityMapFile": "/app/.../data/city_map.json",
  "postLoadRegistrationClasses": [],
  "actorsDataSources": [
    {
      "id": "nodes", "classType": "hybrid.actor.Node",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "EAGER", "entityLifecycle": "STATIC",
      "dataSource": { "sourceType": "json", "info": { "path": "/app/.../data/nodes.json" } }
    },
    {
      "id": "links", "classType": "hybrid.actor.Link",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "EAGER", "entityLifecycle": "STATIC",
      "dataSource": { "sourceType": "json", "info": { "path": "/app/.../data/links.json" } }
    },
    {
      "id": "cars", "classType": "hybrid.actor.Car",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "PROGRESSIVE", "entityLifecycle": "DYNAMIC",
      "dataSource": { "sourceType": "json", "info": { "path": "/app/.../data/cars.json" } }
    },
    {
      "id": "persons", "classType": "hybrid.actor.Person",
      "creationType": "LoadBalancedDistributed",
      "loadingStrategy": "PROGRESSIVE", "entityLifecycle": "DYNAMIC",
      "dataSource": { "sourceType": "json", "info": { "path": "/app/.../data/persons.json" } }
    }
  ]
}
```

---

## 11. Scaling Data Files

For large scenarios, split entity arrays across numbered files to control memory usage and parallelise loading:

```
nodes_0.json   ← first 50,000 nodes
nodes_1.json   ← next  50,000 nodes
...
```

Register each file as a separate entry in `actorsDataSources` with a unique `id`:

```json
{ "id": "nodes_0", "classType": "hybrid.actor.Node", ... },
{ "id": "nodes_1", "classType": "hybrid.actor.Node", ... }
```

The recommended maximum items per file is **50,000** (see `itemsPerFile` in the generation configuration).

---

## 12. Generating Scenarios from Real Data

The São Paulo scenario referenced in this repository was generated from:

| Source | Data |
|---|---|
| OpenStreetMap (OSM) | Road network (nodes, links) and rail infrastructure |
| GTFS feed | Bus routes, stops, schedules |
| EQAsim (MATSim) | Synthetic population activity chains |

The generation script (`generate_hybrid_input.py`) handles:

1. **OSM → Nodes + Links:** Converts the road graph, assigns `simulationMode` (`MESO` by default; `MICRO` for selected highway types), and splits into sharded files.
2. **GTFS → BusStops + BusStations + Buses:** Maps transit stops to nearest OSM nodes, builds route schedules.
3. **OSM rail/subway → RailLinks + SubwayStations:** Extracts rail topology, creates station-to-station rail segments.
4. **EQAsim population → Persons + Cars:** Samples the synthetic population at a given rate, maps activity locations to the nearest network node, and assigns private vehicle assets.
5. **OSM traffic signals → TrafficSignals:** Extracts signal-controlled intersections and assigns phase plans.

### Coordinate system

All coordinates in the scenario files are in **UTM metres** (WGS84 UTM zone 23S for São Paulo). If you are modelling a different city, convert all coordinates to the appropriate UTM zone before generating the scenario. The routing graph (`city_map.json`) and the actor coordinates must use the same CRS.

### Sample rate

The `sampleRate` parameter in the generation configuration controls what fraction of the full population is emitted. For São Paulo:

- Full population: ~19.8 million persons
- 0.1% sample: ~19,600 persons, ~3,000 cars

Increase the sample rate for higher fidelity; decrease it for faster prototype runs.

---

*For the actor API reference, see [API.md](API.md).*  
*For road infrastructure details, see [ROAD_INFRASTRUCTURE.md](ROAD_INFRASTRUCTURE.md).*  
*For bus system details, see [BUS_AGENT.md](BUS_AGENT.md), [BUS_STATION_AGENT.md](BUS_STATION_AGENT.md), [BUS_STOP_AGENT.md](BUS_STOP_AGENT.md).*  
*For subway details, see [SUBWAY_AGENT.md](SUBWAY_AGENT.md), [SUBWAY_STATION_AGENT.md](SUBWAY_STATION_AGENT.md).*  
*For person modelling, see [PERSON_AGENT.md](PERSON_AGENT.md).*

---

## 13. Dynamic Mode Choice

The dynamic mode choice system allows Person agents to **select their transport mode at runtime** instead of following a pre-defined schedule. When enabled, the agent evaluates all accessible transit options and walking at each trip departure and picks the one with the highest utility score.

### When to use

| Scenario | Recommended setting |
|---|---|
| Replaying a fixed observed demand matrix | `enableDynamicModeChoice: false` (default) — exact schedule, fastest simulation |
| Studying how persons react to new transit lines or service changes | `enableDynamicModeChoice: true` — persons adapt to the current network |
| Mixed: some persons are flexible, others have fixed trips | Set per-person; use `fixedMode: true` on individual legs to protect specific trips |

### Utility model

For each candidate `(mode, boardingStop, alightingStop)` the system computes:

$$U = \beta_{\text{mode}} \times \text{pref}(m) - \beta_{\text{access}} \times d_{\text{access}} - \beta_{\text{egress}} \times d_{\text{egress}}$$

where $d_{\text{access}}$ and $d_{\text{egress}}$ are haversine distances in metres. The option with the highest $U$ wins.

**Default preferences:** subway (2.0) > bus (1.0) > walk (0.0). With the default betas (`0.001`), a 1 km access walk incurs a penalty of 1.0 — equal to the preference gap between bus and walking. This means a bus is preferred over walking only if the boarding stop is within ~1 km.

### Compatibility rules

The following table summarises when dynamic re-evaluation is skipped and the original logistics are used as-is:

| Condition | Dynamic evaluation? |
|---|---|
| `enableDynamicModeChoice: false` | No — static schedule |
| `arrivalLogistics.vehicle` is set | No — private vehicle trip |
| `arrivalLogistics.fixedMode: true` | No — leg explicitly locked |
| `transit_map.json` not configured | No — `TransitMapUtil` unavailable |
| Origin or destination node not in `city_map.json` | No — cannot compute haversine |

### JSON example — Person with dynamic mode choice

```json
{
  "id": "htcaid:person;42",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "startTick": 0,
      "scheduleOnTimeManager": true,
      "enableDynamicModeChoice": true,
      "modeChoiceWeights": {
        "betaMode": 1.0,
        "betaAccess": 0.001,
        "betaEgress": 0.001,
        "modePrefSubway": 2.0,
        "modePrefBus": 1.0,
        "modePrefWalk": 0.0,
        "maxAccessDistanceM": 1500.0,
        "maxWalkDistanceM": 2000.0
      },
      "ownedVehicles": {
        "car": { "id": "htcaid:car;p42_v_car", "classType": "hybrid.actor.Car" }
      },
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "home",
          "nodeId": "htcaid:node;500",
          "endTime": "28800",
          "arrivalLogistics": null
        },
        {
          "sequence": 1,
          "activityType": "work",
          "nodeId": "htcaid:node;600",
          "endTime": "64800",
          "arrivalLogistics": {
            "mode": "bus",
            "fixedMode": false
          }
        },
        {
          "sequence": 2,
          "activityType": "home",
          "nodeId": "htcaid:node;500",
          "endTime": "86400",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": { "id": "htcaid:car;p42_v_car", "classType": "hybrid.actor.Car" },
            "fixedMode": true
          }
        }
      ],
      "currentActivityIndex": 0,
      "totalDistanceTraveled": 0.0,
      "completedTrips": 0
    }
  },
  "dependencies": {}
}
```

In the example above:
- The **work trip** (sequence 1) has `fixedMode: false` — it will be re-evaluated at tick 28800. If a subway station is within 1500 m of node 500 and a closer alighting stop exists near node 600, the person will switch to subway.
- The **return trip** (sequence 2) has `fixedMode: true` — always uses the car regardless of transit availability.

### Generating `transit_map.json` from GTFS

When generating a scenario from GTFS data, extract stop positions and line memberships and write them as a flat array. Each stop that serves multiple lines should appear **once** with all its lines in the `lines` array. The `actorId` and `actorClassType` must match exactly the corresponding BusStop/SubwayStation actor IDs in the scenario.

```python
# Pseudocode — GTFS → transit_map.json
stops = []
for stop in gtfs.stops:
    lines = [trip.route_id for trip in gtfs.trips_at(stop.stop_id)]
    stops.append({
        "id": f"htcaid:stop;{stop.stop_id}",
        "actorId": f"htcaid:busstop;{stop.stop_id}",
        "actorClassType": "hybrid.actor.BusStop",
        "nodeId": nearest_node(stop.lat, stop.lon),
        "latitude": stop.lat,
        "longitude": stop.lon,
        "stopType": "bus",
        "lines": list(set(lines))
    })
json.dump(stops, open("transit_map.json", "w"))  # flat array, no wrapper
```

### Runtime behaviour

At each trip departure (`startNextTrip`):

1. `ModeChoiceUtil.chooseBestLogistics` is called with the origin and destination node IDs.
2. `TransitMapUtil.nearestStops` finds up to 5 stops of each type within `maxAccessDistanceM`.
3. For each reachable boarding stop × line, the closest alighting stop (on the same line, nearest to destination) is found.
4. All candidates (bus options, subway options, walking if distance ≤ `maxWalkDistanceM`) are scored.
5. The highest-scoring `ArrivalLogistics` is returned and used to initiate the trip. If no better option exists, the original logistics are used unchanged.

> **Performance note:** `TransitMapUtil` performs a linear scan over stops filtered by type. For large stop sets (> 10,000), consider implementing a spatial index (k-d tree). For typical city scenarios with 2,000–5,000 stops this is negligible compared to actor message processing.
