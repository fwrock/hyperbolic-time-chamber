# Hybrid Input Model - Data Specification

## Overview

This document defines the **Hybrid Input Model** for the Hyperbolic Time Chamber simulator, supporting both **mesoscopic** and **microscopic** simulation modes. It provides specifications for converting data from external sources (eqasim, MATSim, OpenStreetMap, SUMO) into the hybrid format.

---

## Table of Contents

1. [Current Mesoscopic Model](#current-mesoscopic-model)
2. [Hybrid Model Extensions](#hybrid-model-extensions)
3. [Input Data Sources](#input-data-sources)
4. [Hybrid JSON Schema](#hybrid-json-schema)
5. [Conversion Requirements](#conversion-requirements)
6. [Examples](#examples)
7. [Prompt for Script Generation](#prompt-for-script-generation)

---

## 1. Current Mesoscopic Model

### 1.1 Car (Movable Entity)

```json
{
  "id": "htcaid:car;trip_1",
  "name": "Node60609822",
  "typeActor": "mobility.actor.Car",
  "data": {
    "dataType": "model.mobility.entity.state.CarState",
    "content": {
      "startTick": 154,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "linkOrigin": "htcaid:link;2067",
      "gpsId": "htcaid:gps;1",
      "scheduleOnTimeManager": true
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;60609822", "resourceId": "htcrid:node;5", "classType": "mobility.actor.Node", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;4922987596", "resourceId": "htcrid:node;4", "classType": "mobility.actor.Node", "actorType": "LoadBalancedDistributed" },
    "gps": { "id": "htcaid:gps;1", "resourceId": "htcrid:gps;1", "classType": "mobility.actor.GPS", "actorType": "PoolDistributed" }
  }
}
```

### 1.2 Link (Infrastructure)

```json
{
  "id": "htcaid:link;1",
  "name": "Client1",
  "typeActor": "mobility.actor.Link",
  "data": {
    "dataType": "model.mobility.entity.state.LinkState",
    "content": {
      "startTick": 0,
      "from_node": "htcaid:node;394923340",
      "to_node": "htcaid:node;2033271141",
      "capperiod": "01:00:00",
      "effectivecellsize": 7.5,
      "effectivelanewidth": 3.75,
      "length": 13.539593517363432,
      "lanes": 1,
      "freeSpeed": 4.166666666666667,
      "capacity": 600.0,
      "permlanes": 1.0,
      "modes": ["car"],
      "linkType": "residential",
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;394923340", "resourceId": "htcrid:node;3", "classType": "mobility.actor.Node", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;2033271141", "resourceId": "htcrid:node;2", "classType": "mobility.actor.Node", "actorType": "LoadBalancedDistributed" }
  }
}
```

### 1.3 Node (Infrastructure)

```json
{
  "id": "htcaid:node;1001568643",
  "name": "Node1001568643",
  "typeActor": "mobility.actor.Node",
  "data": {
    "dataType": "mobility.entity.state.NodeState",
    "content": {
      "startTick": 0,
      "latitude": "-7347433.28816257",
      "longitude": "-2852981.6323715686",
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {}
}
```

### 1.4 Map Structure

```json
{
  "nodes": [
    { "id": "htcaid:node;1001568643", "classType": "mobility.actor.Node", "resourceId": "htcrid:node;1", "latitude": "-7347433.28816257", "longitude": "-2852981.6323715686" }
  ],
  "edges": [
    { "source_id": "htcaid:node;394923340", "target_id": "htcaid:node;2033271141", "weight": 13.539593517363432, "label": { "id": "htcaid:link;1", "resourceId": "htcrid:link;1", "classType": "mobility.actor.Link", "length": 13.539593517363432 } }
  ],
  "directed": true
}
```

---

## 2. Hybrid Model Extensions

### 2.1 Key Changes

1. **Actor Types**: `mobility.actor.*` → `hybrid.actor.*` (e.g., `HybridCar`, `HybridLink`)
2. **State Types**: `model.mobility.entity.state.*` → `model.hybrid.entity.state.*`
3. **Simulation Mode**: Links have a `simulationMode` field (`MESO` or `MICRO`)
4. **Microscopic Parameters**: Links in MICRO mode require additional fields
5. **Vehicle-Specific Configs**: Support for different vehicle types (Car, Bus, Bicycle, Motorcycle)

### 2.2 Hybrid Car Actor

```json
{
  "id": "htcaid:car;trip_1",
  "name": "Car_trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "startTick": 154,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "linkOrigin": "htcaid:link;2067",
      "gpsId": "htcaid:gps;1",
      "scheduleOnTimeManager": true,
      
      "currentSimulationMode": "MESO",
      
      "microParameters": {
        "maxAcceleration": 2.6,
        "maxDeceleration": 4.5,
        "minGap": 2.0,
        "desiredVelocity": 13.89,
        "reactionTime": 1.0,
        "vehicleLength": 4.5,
        "aggressiveness": 0.5
      }
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;60609822", "resourceId": "htcrid:node;5", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;4922987596", "resourceId": "htcrid:node;4", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" },
    "gps": { "id": "htcaid:gps;1", "resourceId": "htcrid:gps;1", "classType": "mobility.actor.GPS", "actorType": "PoolDistributed" }
  }
}
```

### 2.3 Hybrid Link Actor (MESO Mode)

```json
{
  "id": "htcaid:link;1",
  "name": "Link_1_Residential",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "startTick": 0,
      "from_node": "htcaid:node;394923340",
      "to_node": "htcaid:node;2033271141",
      
      "length": 13.539593517363432,
      "lanes": 1,
      "freeSpeed": 4.166666666666667,
      "capacity": 600.0,
      "speedLimit": 15.0,
      "modes": ["car"],
      "linkType": "residential",
      
      "simulationMode": "MESO",
      
      "capperiod": "01:00:00",
      "effectivecellsize": 7.5,
      "effectivelanewidth": 3.75,
      "permlanes": 1.0,
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;394923340", "resourceId": "htcrid:node;3", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;2033271141", "resourceId": "htcrid:node;2", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" }
  }
}
```

### 2.4 Hybrid Link Actor (MICRO Mode)

```json
{
  "id": "htcaid:link;downtown_main_st",
  "name": "Link_Downtown_MainStreet",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "startTick": 0,
      "from_node": "htcaid:node;intersection_01",
      "to_node": "htcaid:node;intersection_02",
      
      "length": 500.0,
      "lanes": 3,
      "freeSpeed": 13.89,
      "capacity": 1800.0,
      "speedLimit": 50.0,
      "modes": ["car", "bus", "motorcycle"],
      "linkType": "primary",
      
      "simulationMode": "MICRO",
      
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      
      "laneConfigurations": [
        { "laneId": 0, "type": "normal", "width": 3.5, "speedLimit": 50.0 },
        { "laneId": 1, "type": "normal", "width": 3.5, "speedLimit": 50.0 },
        { "laneId": 2, "type": "bus_lane", "width": 3.5, "speedLimit": 50.0, "allowedModes": ["bus"] }
      ],
      
      "carFollowingModel": "Krauss",
      "laneChangeModel": "MOBIL",
      
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;intersection_01", "resourceId": "htcrid:node;5", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;intersection_02", "resourceId": "htcrid:node;6", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" }
  }
}
```

### 2.5 Hybrid Node Actor

```json
{
  "id": "htcaid:node;intersection_01",
  "name": "Node_Intersection_01",
  "typeActor": "hybrid.actor.Node",
  "data": {
    "dataType": "model.hybrid.entity.state.NodeState",
    "content": {
      "startTick": 0,
      "latitude": "-7347433.28816257",
      "longitude": "-2852981.6323715686",
      
      "hasHybridConnections": true,
      "hasMicroLinks": true,
      
      "conflictZones": [
        {
          "zone_id": "cz_01",
          "entering_lanes": ["htcaid:link;1:lane:0", "htcaid:link;2:lane:1"],
          "priority_rule": "right_of_way"
        }
      ],
      
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {}
}
```

### 2.6 Other Vehicle Types

#### Bus
```json
{
  "id": "htcaid:bus;route_101_trip_1",
  "typeActor": "hybrid.actor.Bus",
  "data": {
    "dataType": "model.hybrid.entity.state.BusState",
    "content": {
      "startTick": 200,
      "label": "Route_101",
      "capacity": 80,
      "origin": "htcaid:node;bus_terminal",
      "destination": "htcaid:node;city_center",
      "busStops": {
        "stop_1": "htcaid:node;stop_av_paulista",
        "stop_2": "htcaid:node;stop_central_park"
      },
      "microParameters": {
        "maxAcceleration": 1.2,
        "maxDeceleration": 3.5,
        "minGap": 3.0,
        "desiredVelocity": 11.11,
        "reactionTime": 1.5,
        "vehicleLength": 12.0
      }
    }
  }
}
```

#### Bicycle
```json
{
  "id": "htcaid:bicycle;rider_42",
  "typeActor": "hybrid.actor.Bicycle",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridBicycleState",
    "content": {
      "startTick": 100,
      "origin": "htcaid:node;residential_area",
      "destination": "htcaid:node;downtown",
      "microParameters": {
        "maxAcceleration": 1.0,
        "maxDeceleration": 3.0,
        "minGap": 1.5,
        "desiredVelocity": 5.56,
        "reactionTime": 1.2,
        "vehicleLength": 2.0,
        "prefersBikeLane": true
      }
    }
  }
}
```

#### Motorcycle
```json
{
  "id": "htcaid:motorcycle;rider_99",
  "typeActor": "hybrid.actor.Motorcycle",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridMotorcycleState",
    "content": {
      "startTick": 120,
      "origin": "htcaid:node;suburb",
      "destination": "htcaid:node;business_district",
      "microParameters": {
        "maxAcceleration": 3.5,
        "maxDeceleration": 5.0,
        "minGap": 1.5,
        "desiredVelocity": 16.67,
        "reactionTime": 0.9,
        "vehicleLength": 2.5,
        "canFilterLanes": true,
        "aggressiveness": 0.7
      }
    }
  }
}
```

---

## 3. Input Data Sources

### 3.1 eqasim + MATSim

**Source**: https://github.com/eqasim-org

**Output Format**: MATSim XML files
- `network.xml` - Road network (links, nodes)
- `population.xml` - Synthetic population with activities and plans
- `vehicles.xml` - Vehicle fleet definitions
- `facilities.xml` - Activity locations

**Required Conversions**:
1. **Network**: MATSim links → HTC HybridLink (with mode selection logic)
2. **Population**: MATSim plans → HTC trip definitions (Car, Bus, etc.)
3. **Coordinates**: Geographic (lat/lon) or projected (x/y) → HTC coordinate system
4. **Time**: MATSim time strings → HTC tick numbers

### 3.2 OpenStreetMap (OSM)

**Source**: OpenStreetMap data (via Overpass API or `.osm.pbf` files)

**Output Format**: OSM XML/PBF with road network

**Required Conversions**:
1. **Ways → Links**: OSM ways with `highway` tag → HybridLink
2. **Nodes → Nodes**: OSM nodes at way intersections → HybridNode
3. **Tags → Attributes**: OSM tags (maxspeed, lanes, highway type) → link properties
4. **Geometry**: OSM coordinates → HTC coordinate system
5. **Mode Selection**: Highway type → simulationMode (MESO/MICRO)

### 3.3 SUMO

**Source**: SUMO network and route files

**Output Format**: SUMO XML
- `.net.xml` - Network definition (micro-level detail)
- `.rou.xml` - Routes and vehicle definitions
- `.typ.xml` - Type definitions

**Required Conversions**:
1. **SUMO edges → HTC Links**: Map SUMO edges (with lanes) to HybridLink
2. **SUMO junctions → HTC Nodes**: Junction logic → HybridNode + conflict zones
3. **SUMO vehicles → HTC vehicles**: Vehicle types and car-following params → microParameters
4. **Lane-level detail**: SUMO lane configs → laneConfigurations array

---

## 4. Hybrid JSON Schema

### 4.1 Actor Types

| Actor Type | Class | State Type |
|------------|-------|------------|
| Car | `hybrid.actor.HybridCar` | `model.hybrid.entity.state.HybridCarState` |
| Bus | `hybrid.actor.HybridBus` | `model.hybrid.entity.state.HybridBusState` |
| Bicycle | `hybrid.actor.HybridBicycle` | `model.hybrid.entity.state.HybridBicycleState` |
| Motorcycle | `hybrid.actor.HybridMotorcycle` | `model.hybrid.entity.state.HybridMotorcycleState` |
| Subway | `hybrid.actor.HybridSubway` | `model.hybrid.entity.state.HybridSubwayState` |
| Person | `hybrid.actor.HybridPerson` | `model.hybrid.entity.state.HybridPersonState` |
| Link | `hybrid.actor.HybridLink` | `model.hybrid.entity.state.HybridLinkState` |
| Node | `hybrid.actor.HybridNode` | `model.hybrid.entity.state.HybridNodeState` |

### 4.2 Required Fields

#### HybridLink (All Modes)
```javascript
{
  "from_node": String,          // Node ID
  "to_node": String,            // Node ID
  "length": Double,             // meters
  "lanes": Int,                 // total lanes
  "freeSpeed": Double,          // m/s (for meso calculation)
  "capacity": Double,           // veh/hour (for meso)
  "speedLimit": Double,         // km/h (for micro)
  "simulationMode": String,     // "MESO" or "MICRO"
  "modes": [String],            // ["car", "bus", "bicycle", "motorcycle"]
  "linkType": String            // OSM highway type or custom
}
```

#### HybridLink (MICRO Mode Only)
```javascript
{
  "microTimeStep": Double,              // seconds (default: 0.1)
  "microTicksPerGlobalTick": Int,       // default: 10
  "laneConfigurations": [               // per-lane config
    {
      "laneId": Int,
      "type": String,                   // "normal", "bus_lane", "bike_lane"
      "width": Double,                  // meters
      "speedLimit": Double,             // km/h
      "allowedModes": [String]          // optional restriction
    }
  ],
  "carFollowingModel": String,          // "Krauss", "IDM", "Gipps"
  "laneChangeModel": String             // "MOBIL", "Simple"
}
```

#### HybridCar/Bus/Bicycle/Motorcycle
```javascript
{
  "startTick": Int,                     // simulation tick to spawn
  "origin": String,                     // Node ID
  "destination": String,                // Node ID
  "linkOrigin": String,                 // optional: starting link
  "currentSimulationMode": String,      // "MESO" (initial)
  "microParameters": {                  // vehicle-specific physics
    "maxAcceleration": Double,          // m/s²
    "maxDeceleration": Double,          // m/s²
    "minGap": Double,                   // meters
    "desiredVelocity": Double,          // m/s
    "reactionTime": Double,             // seconds
    "vehicleLength": Double             // meters
  }
}
```

### 4.3 Default Micro Parameters by Vehicle Type

| Vehicle | Length (m) | Max Accel (m/s²) | Max Decel (m/s²) | Min Gap (m) | Desired Speed (m/s) | Reaction Time (s) |
|---------|------------|------------------|------------------|-------------|---------------------|-------------------|
| Car | 4.5 | 2.6 | 4.5 | 2.0 | 13.89 | 1.0 |
| Bus | 12.0 | 1.2 | 3.5 | 3.0 | 11.11 | 1.5 |
| Bicycle | 2.0 | 1.0 | 3.0 | 1.5 | 5.56 | 1.2 |
| Motorcycle | 2.5 | 3.5 | 5.0 | 1.5 | 16.67 | 0.9 |

---

## 5. Conversion Requirements

### 5.1 Link Mode Selection Logic

**Decision Tree for `simulationMode`**:

```
IF (link is in CBD or downtown area)
  AND (link has high traffic volume OR complex intersection)
  AND (link length < 1000m)
THEN
  simulationMode = "MICRO"
ELSE
  simulationMode = "MESO"
```

**Alternative: By Road Type**:
```
MICRO:
  - primary roads in city center
  - roads with traffic signals
  - BRT corridors
  - areas with mixed traffic (car, bus, bicycle)

MESO:
  - highways
  - residential streets
  - rural roads
  - long-distance links (> 2km)
```

### 5.2 Coordinate System Conversion

**From MATSim/OSM (lat/lon) to HTC**:
```
1. If MATSim uses projected coordinates (x, y):
   - Convert to lat/lon using inverse projection (e.g., UTM)
   
2. If OSM provides lat/lon:
   - Use directly or apply local projection
   
3. For HTC format:
   - Store as strings with high precision
   - Example: "-7347433.28816257", "-2852981.6323715686"
```

### 5.3 Time Conversion

**From MATSim time string to HTC tick**:
```
MATSim time: "08:30:00" (HH:MM:SS)
Tick calculation: tick = (hour * 3600 + minute * 60 + second) / TICK_DURATION

Example (TICK_DURATION = 1 second):
  "08:30:00" → tick = (8*3600 + 30*60 + 0) / 1 = 30600
  "06:34:00" → tick = 154 (as in example)
```

### 5.4 Network Topology

**Required Validations**:
1. All links must reference existing nodes
2. Node coordinates must be valid
3. Link length must match Haversine distance between nodes (±10%)
4. No duplicate IDs
5. All dependencies must be resolvable

---

## 6. Examples

### 6.1 Complete Scenario (Hybrid)

**Directory Structure**:
```
scenario_sao_paulo/
├── actors/
│   ├── cars.json           # All car actors
│   ├── buses.json          # All bus actors
│   ├── bicycles.json       # All bicycle actors
│   ├── motorcycles.json    # All motorcycle actors
│   ├── links.json          # All link actors
│   └── nodes.json          # All node actors
└── map.json                # Graph structure
```

**cars.json (sample)**:
```json
[
  {
    "id": "htcaid:car;trip_1",
    "name": "Car_trip_1",
    "typeActor": "hybrid.actor.Car",
    "data": {
      "dataType": "model.hybrid.entity.state.CarState",
      "content": {
        "startTick": 30600,
        "origin": "htcaid:node;home_location",
        "destination": "htcaid:node;work_location",
        "scheduleOnTimeManager": true,
        "microParameters": {
          "maxAcceleration": 2.6,
          "maxDeceleration": 4.5,
          "minGap": 2.0,
          "desiredVelocity": 13.89,
          "reactionTime": 1.0,
          "vehicleLength": 4.5
        }
      }
    },
    "dependencies": {
      "from_node": { "id": "htcaid:node;home_location", "resourceId": "htcrid:node;1", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" },
      "to_node": { "id": "htcaid:node;work_location", "resourceId": "htcrid:node;2", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" }
    }
  }
]
```

**links.json (sample with mixed modes)**:
```json
[
  {
    "id": "htcaid:link;residential_01",
    "typeActor": "hybrid.actor.Link",
    "data": {
      "dataType": "model.hybrid.entity.state.HybridLinkState",
      "content": {
        "from_node": "htcaid:node;home_location",
        "to_node": "htcaid:node;intersection_local",
        "length": 120.5,
        "lanes": 2,
        "freeSpeed": 8.33,
        "capacity": 800.0,
        "speedLimit": 30.0,
        "simulationMode": "MESO",
        "modes": ["car", "bicycle"],
        "linkType": "residential"
      }
    }
  },
  {
    "id": "htcaid:link;downtown_avenue",
    "typeActor": "hybrid.actor.Link",
    "data": {
      "dataType": "model.hybrid.entity.state.HybridLinkState",
      "content": {
        "from_node": "htcaid:node;intersection_central",
        "to_node": "htcaid:node;city_center",
        "length": 650.0,
        "lanes": 4,
        "freeSpeed": 13.89,
        "capacity": 2400.0,
        "speedLimit": 60.0,
        "simulationMode": "MICRO",
        "microTimeStep": 0.1,
        "microTicksPerGlobalTick": 10,
        "laneConfigurations": [
          { "laneId": 0, "type": "normal", "width": 3.5, "speedLimit": 60.0 },
          { "laneId": 1, "type": "normal", "width": 3.5, "speedLimit": 60.0 },
          { "laneId": 2, "type": "normal", "width": 3.5, "speedLimit": 60.0 },
          { "laneId": 3, "type": "bus_lane", "width": 3.5, "speedLimit": 60.0, "allowedModes": ["bus"] }
        ],
        "carFollowingModel": "Krauss",
        "laneChangeModel": "MOBIL",
        "modes": ["car", "bus"],
        "linkType": "primary"
      }
    }
  }
]
```

---

## 7. Prompt for Script Generation

Copy and use this prompt to generate conversion scripts:

---

### 🤖 **CONVERSION SCRIPT GENERATION PROMPT**

```markdown
# Context
I am developing a **Hybrid Mesoscopic-Microscopic Traffic Simulator** in Scala that accepts JSON input files for actors (vehicles and infrastructure). I need to convert data from external sources into the simulator's format.

# Data Sources
1. **eqasim + MATSim**: Synthetic population generator (outputs MATSim XML: network.xml, population.xml)
2. **OpenStreetMap (OSM)**: Road network data (OSM XML/PBF format)
3. **SUMO**: Microscopic traffic simulator (outputs .net.xml, .rou.xml)

# Target Format
The simulator requires JSON files following this structure:

## Actor Types
- **Vehicles**: `hybrid.actor.HybridCar`, `hybrid.actor.HybridBus`, `hybrid.actor.HybridBicycle`, `hybrid.actor.HybridMotorcycle`
- **Infrastructure**: `hybrid.actor.HybridLink`, `hybrid.actor.HybridNode`

## Required JSON Schema

### Vehicle Example (HybridCar)
```json
{
  "id": "htcaid:car;trip_1",
  "name": "Car_trip_1",
  "typeActor": "hybrid.actor.HybridCar",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridCarState",
    "content": {
      "startTick": 30600,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "linkOrigin": "htcaid:link;2067",
      "scheduleOnTimeManager": true,
      "microParameters": {
        "maxAcceleration": 2.6,
        "maxDeceleration": 4.5,
        "minGap": 2.0,
        "desiredVelocity": 13.89,
        "reactionTime": 1.0,
        "vehicleLength": 4.5
      }
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;60609822", "resourceId": "htcrid:node;1", "classType": "hybrid.actor.HybridNode", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;4922987596", "resourceId": "htcrid:node;2", "classType": "hybrid.actor.HybridNode", "actorType": "LoadBalancedDistributed" }
  }
}
```

### Link Example (MICRO Mode)
```json
{
  "id": "htcaid:link;1",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "from_node": "htcaid:node;394923340",
      "to_node": "htcaid:node;2033271141",
      "length": 500.0,
      "lanes": 3,
      "freeSpeed": 13.89,
      "capacity": 1800.0,
      "speedLimit": 50.0,
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "laneConfigurations": [
        { "laneId": 0, "type": "normal", "width": 3.5, "speedLimit": 50.0 },
        { "laneId": 1, "type": "normal", "width": 3.5, "speedLimit": 50.0 },
        { "laneId": 2, "type": "bus_lane", "width": 3.5, "speedLimit": 50.0, "allowedModes": ["bus"] }
      ],
      "carFollowingModel": "Krauss",
      "laneChangeModel": "MOBIL",
      "modes": ["car", "bus"],
      "linkType": "primary"
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;394923340", "resourceId": "htcrid:node;1", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;2033271141", "resourceId": "htcrid:node;2", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" }
  }
}
```

### Link Example (MESO Mode)
```json
{
  "id": "htcaid:link;residential_01",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "from_node": "htcaid:node;home_location",
      "to_node": "htcaid:node;intersection_local",
      "length": 120.5,
      "lanes": 2,
      "freeSpeed": 8.33,
      "capacity": 800.0,
      "speedLimit": 30.0,
      "simulationMode": "MESO",
      "modes": ["car", "bicycle"],
      "linkType": "residential"
    }
  },
  "dependencies": {
    "from_node": { "id": "htcaid:node;home_location", "resourceId": "htcrid:node;3", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" },
    "to_node": { "id": "htcaid:node;intersection_local", "resourceId": "htcrid:node;4", "classType": "hybrid.actor.Node", "actorType": "LoadBalancedDistributed" }
  }
}
```

### Node Example
```json
{
  "id": "htcaid:node;1001568643",
  "typeActor": "hybrid.actor.Node",
  "data": {
    "dataType": "model.hybrid.entity.state.NodeState",
    "content": {
      "startTick": 0,
      "latitude": "-7347433.28816257",
      "longitude": "-2852981.6323715686",
      "hasHybridConnections": false,
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {}
}
```

### Map Structure
```json
{
  "nodes": [
    { "id": "htcaid:node;1001568643", "classType": "hybrid.actor.Node", "resourceId": "htcrid:node;1", "latitude": "-7347433.28816257", "longitude": "-2852981.6323715686" }
  ],
  "edges": [
    { "source_id": "htcaid:node;394923340", "target_id": "htcaid:node;2033271141", "weight": 13.539593517363432, "label": { "id": "htcaid:link;1", "resourceId": "htcrid:link;1", "classType": "hybrid.actor.Link", "length": 13.539593517363432 } }
  ],
  "directed": true
}
```

# Conversion Requirements

## 1. Link Mode Selection Logic
Determine `simulationMode` for each link using:
```
IF (link is in CBD/downtown AND length < 1000m AND high traffic volume)
  OR (link type = "primary" AND in urban area)
  OR (link has BRT corridor or bike lane)
THEN
  simulationMode = "MICRO"
ELSE
  simulationMode = "MESO"
```

## 2. Coordinate Handling
- MATSim: Convert projected (x, y) to lat/lon if needed
- OSM: Use lat/lon directly
- Store as high-precision strings

## 3. Time Conversion
- MATSim time "08:30:00" → tick = (8*3600 + 30*60) / TICK_DURATION
- Default TICK_DURATION = 1 second

## 4. Vehicle Type Mapping
| Source | Vehicle Type | Target Actor |
|--------|--------------|--------------|
| MATSim | car | hybrid.actor.HybridCar |
| MATSim | pt (bus) | hybrid.actor.HybridBus |
| OSM/SUMO | bicycle | hybrid.actor.HybridBicycle |
| OSM/SUMO | motorcycle | hybrid.actor.HybridMotorcycle |

## 5. Default Micro Parameters
| Vehicle | Length (m) | Max Accel | Max Decel | Min Gap | Desired Speed | Reaction Time |
|---------|------------|-----------|-----------|---------|---------------|---------------|
| Car | 4.5 | 2.6 | 4.5 | 2.0 | 13.89 | 1.0 |
| Bus | 12.0 | 1.2 | 3.5 | 3.0 | 11.11 | 1.5 |
| Bicycle | 2.0 | 1.0 | 3.0 | 1.5 | 5.56 | 1.2 |
| Motorcycle | 2.5 | 3.5 | 5.0 | 1.5 | 16.67 | 0.9 |

# Task
Generate **Python scripts** (or language of your choice) to convert:

1. **MATSim Network → HTC Links/Nodes**
   - Parse `network.xml`
   - Convert links and nodes
   - Apply mode selection logic
   - Generate `links.json`, `nodes.json`, `map.json`

2. **MATSim Population → HTC Vehicles**
   - Parse `population.xml`
   - Extract trips (person → car/bus actors)
   - Convert departure times to ticks
   - Generate `cars.json`, `buses.json`

3. **OSM → HTC Network**
   - Parse OSM XML/PBF
   - Extract ways (highway tag) → links
   - Extract nodes → intersections
   - Map OSM tags (maxspeed, lanes) to link attributes
   - Generate `links.json`, `nodes.json`, `map.json`

4. **SUMO Network → HTC Network**
   - Parse `.net.xml`
   - Convert edges (with lane details) → HybridLink (MICRO mode)
   - Convert junctions → HybridNode
   - Extract lane configurations
   - Generate `links.json`, `nodes.json`, `map.json`

5. **SUMO Routes → HTC Vehicles**
   - Parse `.rou.xml`
   - Convert vehicle types and car-following params
   - Generate `cars.json` with microParameters

# Output Structure
```
scenario_output/
├── actors/
│   ├── cars.json
│   ├── buses.json
│   ├── bicycles.json
│   ├── motorcycles.json
│   ├── links.json
│   └── nodes.json
└── map.json
```

# Script Requirements
- Use standard libraries (Python: xml.etree, json) or specify dependencies
- Include error handling and validation
- Log conversion statistics (# vehicles, # links, # nodes)
- Support filtering (e.g., geographic bounding box)
- Include CLI arguments for input/output paths

# Additional Context
- ID format: `htcaid:<type>;<unique_id>` (e.g., `htcaid:car;trip_1`, `htcaid:link;123`)
- Resource ID format: `htcrid:<type>;<partition_id>` (e.g., `htcrid:node;1`)
- Actor distribution: Use `LoadBalancedDistributed` for nodes/links
- All speeds: store in m/s for consistency

# References
- eqasim: https://github.com/eqasim-org
- MATSim format: https://www.matsim.org/docs
- OSM tags: https://wiki.openstreetmap.org/wiki/Map_Features
- SUMO format: https://sumo.dlr.de/docs/Networks/SUMO_Road_Networks.html

Please generate the conversion scripts with detailed comments and usage examples.
```

---

## 8. Validation Checklist

Before running the simulation, validate the generated JSON:

- [ ] All actor IDs follow `htcaid:<type>;<id>` format
- [ ] All resource IDs follow `htcrid:<type>;<partition>` format
- [ ] All node references in links exist in nodes.json
- [ ] All links in map.json edges exist in links.json
- [ ] All nodes in map.json nodes exist in nodes.json
- [ ] MICRO links have `laneConfigurations` array
- [ ] MESO links have `freeSpeed` and `capacity`
- [ ] All vehicles have valid `origin` and `destination` nodes
- [ ] startTick values are non-negative integers
- [ ] Coordinates are high-precision strings
- [ ] No duplicate IDs across all actor files
- [ ] Dependencies reference correct `classType` (hybrid.actor.*)

---

## 9. Performance Considerations

### 9.1 File Size Guidelines
- **Small scenario**: < 10,000 vehicles, < 5,000 links
- **Medium scenario**: 10,000-100,000 vehicles, 5,000-20,000 links
- **Large scenario**: > 100,000 vehicles, > 20,000 links

### 9.2 Optimization Tips
1. **Partition actors**: Split large JSON files by geographic region or actor type
2. **Resource distribution**: Balance `resourceId` partitions
3. **MICRO link placement**: Use MICRO mode sparingly (< 20% of network)
4. **Batch vehicle spawning**: Group vehicles by similar `startTick` values

---

## 10. Next Steps

1. **Review this document** and adjust schema if needed
2. **Use the prompt** (Section 7) to generate conversion scripts
3. **Test conversion** with small sample datasets
4. **Validate output** using checklist (Section 8)
5. **Run simulation** with hybrid scenario
6. **Iterate** based on simulation results

---

## Appendix A: OSM Highway Type Mapping

| OSM Highway Type | Suggested Mode | Default Speed Limit (km/h) | Typical Lanes |
|------------------|----------------|---------------------------|---------------|
| motorway | MESO | 110 | 3-4 |
| trunk | MESO | 90 | 2-3 |
| primary | MICRO (urban) / MESO (rural) | 60 | 2-3 |
| secondary | MICRO (urban) / MESO (rural) | 50 | 2 |
| tertiary | MESO | 40 | 1-2 |
| residential | MESO | 30 | 1 |
| service | MESO | 20 | 1 |
| cycleway | MESO | 20 | 1 (bicycle only) |

---

## Appendix B: MATSim Link Attributes

| MATSim Attribute | HTC Equivalent | Conversion |
|------------------|----------------|------------|
| `length` | `length` | Direct (meters) |
| `capacity` | `capacity` | Direct (veh/hour) |
| `freespeed` | `freeSpeed` | Direct (m/s) |
| `permlanes` | `lanes` | Round to integer |
| `modes` | `modes` | Split string → array |

---

## Appendix C: SUMO Vehicle Type Parameters

| SUMO Parameter | HTC Equivalent | Field Path |
|----------------|----------------|------------|
| `length` | `vehicleLength` | `microParameters.vehicleLength` |
| `accel` | `maxAcceleration` | `microParameters.maxAcceleration` |
| `decel` | `maxDeceleration` | `microParameters.maxDeceleration` |
| `minGap` | `minGap` | `microParameters.minGap` |
| `maxSpeed` | `desiredVelocity` | `microParameters.desiredVelocity` |
| `tau` | `reactionTime` | `microParameters.reactionTime` |

---

**End of Document**

For questions or issues, refer to the main project documentation at `/docs/` or open an issue on the project repository.
