# 🎯 Scenario Generator Script - Complete Specification

**Prompt for Creating a Comprehensive Simulation Scenario Generator for Hyperbolic Time Chamber**

---

## 📋 Overview

Create a Python script that generates complete, realistic traffic simulation scenarios for the Hyperbolic Time Chamber traffic simulator. The script should enable configurable generation of all simulation elements including persons, vehicles, infrastructure, and public transport.

---

## 🎯 Script Specifications

### **Script Name:** `generate_scenario.py`

### **Command-Line Interface:**

```bash
python generate_scenario.py \
  --name "city_simulation_1000" \
  --output-dir "./generated_scenarios/city_1000" \
  --persons 1000 \
  --cars 600 \
  --bicycles 250 \
  --motorcycles 150 \
  --buses 20 \
  --subways 5 \
  --nodes 200 \
  --links 400 \
  --signals 50 \
  --simulation-duration 86400 \
  --map-type grid \
  --map-size 5000 \
  --random-seed 42 \
  --verbose \
  --validate
```

### **Required Arguments:**
- `--name`: Scenario name (string)
- `--output-dir`: Output directory path (string)
- `--persons`: Number of Person actors to generate (int)

### **Optional Arguments:**
- `--cars`: Number of Car actors (default: auto-calculate from persons)
- `--bicycles`: Number of Bicycle actors (default: auto-calculate)
- `--motorcycles`: Number of Motorcycle actors (default: auto-calculate)
- `--buses`: Number of Bus actors (default: 0)
- `--subways`: Number of Subway actors (default: 0)
- `--nodes`: Number of Node actors/intersections (default: auto-calculate)
- `--links`: Number of Link actors/road segments (default: auto-calculate)
- `--signals`: Number of TrafficSignal actors (default: auto-calculate)
- `--simulation-duration`: Simulation duration in seconds (default: 86400 = 24h)
- `--map-type`: Map generation type: `grid`, `radial`, `random`, `real` (default: grid)
- `--map-size`: Map size in meters (default: 5000)
- `--random-seed`: Random seed for reproducibility (default: 42)
- `--modal-split`: Modal split percentages (default: "car:40,bicycle:20,motorcycle:10,bus:20,subway:10")
- `--peak-hours`: Peak hour definitions (default: "7-9,17-19")
- `--micro-links-percentage`: Percentage of links in MICRO mode (default: 20)
- `--verbose`: Enable verbose output (flag)
- `--validate`: Validate generated scenario (flag)
- `--format`: Output format: `split` (multiple files) or `single` (one JSON) (default: split)

---

## 📁 Generated Files Structure

```
output_dir/
├── scenario_config.json          # Main simulation configuration
├── actors/
│   ├── persons.json              # All Person actors with daily schedules
│   ├── cars.json                 # All Car actors (private vehicles)
│   ├── bicycles.json             # All Bicycle actors
│   ├── motorcycles.json          # All Motorcycle actors
│   ├── buses.json                # All Bus actors (public transport)
│   ├── subways.json              # All Subway actors
│   ├── nodes.json                # All Node actors (intersections)
│   ├── links.json                # All Link actors (road segments)
│   └── signals.json              # All TrafficSignal actors
├── metadata.json                 # Scenario metadata and statistics
└── visualization/
    ├── network_map.html          # Interactive map visualization (optional)
    └── schedule_timeline.html    # Timeline of activities (optional)
```

---

## 🏗️ Data Models Specification

### **1. Person Actor (Person-Centric Model)**

**Structure:**
```json
{
  "id": "htcaid:person;commuter_alice_001",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "Home",
          "nodeId": "htcaid:node;residential_zone_01",
          "endTime": "28800",
          "arrivalLogistics": null
        },
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "htcaid:node;business_district_05",
          "endTime": "61200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicleId": "htcaid:car;alice_sedan_001",
            "driverAttributes": {
              "aggressiveness": 0.6,
              "maxSpeedFactor": 1.1,
              "reactionTime": 0.9,
              "minGapFactor": 0.8
            }
          }
        },
        {
          "sequence": 2,
          "activityType": "Home",
          "nodeId": "htcaid:node;residential_zone_01",
          "endTime": "79200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;alice_sedan_001",
              "classType": "hybrid.actor.Car"
            },
            "driverAttributes": {
              "aggressiveness": 0.5,
              "maxSpeedFactor": 1.0,
              "reactionTime": 1.0,
              "minGapFactor": 1.0
            }
          }
        }
      ],
      "currentActivityIndex": 0,
      "ownedVehicles": {
        "car": {
          "id": "htcaid:car;alice_sedan_001",
          "classType": "hybrid.actor.Car"
        }
      },
      "currentTripVehicleId": null,
      "currentTripStartTick": null,
      "totalDistanceTraveled": 0.0,
      "completedTrips": 0
    }
  },
  "dependencies": {
    "car": {
      "id": "htcaid:car;alice_sedan_001",
      "classType": "hybrid.actor.Car"
    }
  }
}
```

**Key Points:**
- Each person has a `dailySchedule` with sequential activities
- First activity must have `arrivalLogistics: null` (starting location)
- Activity types: `Home`, `Work`, `School`, `Shopping`, `Leisure`, `Coffee`, `Gym`, `Restaurant`, `Park`, `Entertainment`
- `endTime` in seconds from midnight (e.g., 28800 = 8:00 AM)
- Driver attributes range: 0.0-1.0 for aggressiveness, 0.5-2.0 for other factors
- Person can own multiple vehicles (car, bicycle, motorcycle)

---

### **2. Private Vehicle Actors (Car/Bicycle/Motorcycle)**

**Car Example:**
```json
{
  "id": "htcaid:car;sedan_001",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "startTick": 0,
      "origin": "htcaid:node;parking_lot_01",
      "destination": "htcaid:node;parking_lot_01",
      "bestRoute": null,
      "currentNode": "htcaid:node;parking_lot_01",
      "distance": 0.0,
      "eventCount": 0,
      "status": "PARKED",
      "currentSimulationMode": "MESO",
      "microState": null
    }
  },
  "dependencies": {}
}
```

**Bicycle Example:**
```json
{
  "id": "htcaid:bicycle;mountain_bike_001",
  "typeActor": "hybrid.actor.Bicycle",
  "data": {
    "dataType": "model.hybrid.entity.state.BicycleState",
    "content": {
      "startTick": 0,
      "origin": "htcaid:node;bike_rack_01",
      "destination": "htcaid:node;bike_rack_01",
      "bestRoute": null,
      "currentNode": "htcaid:node;bike_rack_01",
      "distance": 0.0,
      "eventCount": 0,
      "status": "PARKED",
      "currentSimulationMode": "MESO",
      "microState": null
    }
  },
  "dependencies": {}
}
```

**Motorcycle Example:**
```json
{
  "id": "htcaid:motorcycle;sportbike_001",
  "typeActor": "hybrid.actor.Motorcycle",
  "data": {
    "dataType": "model.hybrid.entity.state.MotorcycleState",
    "content": {
      "startTick": 0,
      "origin": "htcaid:node;parking_spot_01",
      "destination": "htcaid:node;parking_spot_01",
      "bestRoute": null,
      "currentNode": "htcaid:node;parking_spot_01",
      "distance": 0.0,
      "eventCount": 0,
      "status": "PARKED",
      "currentSimulationMode": "MESO",
      "microState": null
    }
  },
  "dependencies": {}
}
```

**Key Points:**
- All private vehicles start in `PARKED` status
- They are ASSETS owned by Person actors
- Must reference valid node IDs for parking locations
- Support both `MESO` (aggregate) and `MICRO` (detailed) simulation modes

---

### **3. Public Transport Actors (Bus/Subway)**

**Bus Example:**
```json
{
  "id": "htcaid:bus;route_101",
  "typeActor": "mobility.actor.Bus",
  "data": {
    "dataType": "model.mobility.entity.state.BusState",
    "content": {
      "startTick": 21600,
      "label": "Route 101",
      "capacity": 50,
      "origin": "htcaid:node;bus_terminal_central",
      "destination": "htcaid:node;bus_terminal_north",
      "busStops": {
        "htcaid:busstation;stop_01": "htcaid:busstation;stop_01",
        "htcaid:busstation;stop_02": "htcaid:busstation;stop_02",
        "htcaid:busstation;stop_03": "htcaid:busstation;stop_03"
      },
      "people": {},
      "status": "START"
    }
  },
  "dependencies": {
    "bus_stop_1": {
      "id": "htcaid:busstation;stop_01",
      "classType": "mobility.actor.BusStation"
    }
  }
}
```

**Subway Example:**
```json
{
  "id": "htcaid:subway;line_blue_train_01",
  "typeActor": "mobility.actor.Subway",
  "data": {
    "dataType": "model.mobility.entity.state.SubwayState",
    "content": {
      "startTick": 18000,
      "label": "Blue Line",
      "capacity": 200,
      "origin": "htcaid:node;station_downtown",
      "destination": "htcaid:node;station_airport",
      "subwayStations": {
        "htcaid:subwaystation;downtown": "htcaid:subwaystation;downtown",
        "htcaid:subwaystation;midtown": "htcaid:subwaystation;midtown",
        "htcaid:subwaystation;airport": "htcaid:subwaystation;airport"
      },
      "people": {},
      "status": "START"
    }
  },
  "dependencies": {}
}
```

**Key Points:**
- Public transport follows fixed routes with stops
- Have passenger capacity limits
- Start at specific times throughout the day
- Can transport Person actors

---

### **4. Infrastructure Actors (Node/Link/Signal)**

**Node Example:**
```json
{
  "id": "htcaid:node;intersection_01",
  "typeActor": "hybrid.actor.Node",
  "data": {
    "dataType": "model.hybrid.entity.state.NodeState",
    "content": {
      "latitude": -23.5505,
      "longitude": -46.6333,
      "links": [
        "htcaid:link;north_01",
        "htcaid:link;south_01",
        "htcaid:link;east_01",
        "htcaid:link;west_01"
      ],
      "connections": {
        "htcaid:node;intersection_02": {"id": "htcaid:node;intersection_02"}
      },
      "signals": {
        "htcaid:signal;traffic_light_01": "RED"
      },
      "busStops": {},
      "subwayStations": {}
    }
  },
  "dependencies": {}
}
```

**Link Example (MESO):**
```json
{
  "id": "htcaid:link;main_street_01",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "from": "htcaid:node;intersection_01",
      "to": "htcaid:node;intersection_02",
      "length": 500.0,
      "lanes": 2,
      "speedLimit": 50.0,
      "freeSpeed": 50.0,
      "capacity": 1500,
      "congestionFactor": 1.0,
      "currentSpeed": 50.0,
      "simulationMode": "MESO",
      "registered": []
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;intersection_01",
      "classType": "hybrid.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;intersection_02",
      "classType": "hybrid.actor.Node"
    }
  }
}
```

**Link Example (MICRO):**
```json
{
  "id": "htcaid:link;downtown_corridor_micro",
  "typeActor": "hybrid.actor.Link",
  "data": {
    "dataType": "model.hybrid.entity.state.LinkState",
    "content": {
      "from": "htcaid:node;downtown_01",
      "to": "htcaid:node;downtown_02",
      "length": 1000.0,
      "lanes": 3,
      "speedLimit": 60.0,
      "freeSpeed": 60.0,
      "capacity": 2000,
      "congestionFactor": 1.0,
      "currentSpeed": 60.0,
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      "vehiclesByLane": {},
      "laneConfigurations": [
        {"laneId": 0, "type": "normal"},
        {"laneId": 1, "type": "normal"},
        {"laneId": 2, "type": "bus_lane"}
      ],
      "registered": []
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;downtown_01",
      "classType": "hybrid.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;downtown_02",
      "classType": "hybrid.actor.Node"
    }
  }
}
```

**Traffic Signal Example:**
```json
{
  "id": "htcaid:signal;traffic_light_intersection_01",
  "typeActor": "mobility.actor.TrafficSignal",
  "data": {
    "dataType": "model.mobility.entity.state.TrafficSignalState",
    "content": {
      "nodeId": "htcaid:node;intersection_01",
      "phases": [
        {
          "phaseId": 0,
          "duration": 30,
          "greenLinks": ["htcaid:link;north_01", "htcaid:link;south_01"]
        },
        {
          "phaseId": 1,
          "duration": 30,
          "greenLinks": ["htcaid:link;east_01", "htcaid:link;west_01"]
        }
      ],
      "currentPhase": 0,
      "currentPhaseElapsed": 0
    }
  },
  "dependencies": {}
}
```

**Key Points:**
- Nodes have GPS coordinates (latitude, longitude)
- Links connect two nodes with properties: length, lanes, speedLimit, capacity
- Links can be MESO (aggregate) or MICRO (detailed) simulation mode
- Signals control traffic light phases at intersections

---

## 🗺️ Map Generation Algorithms

### **1. Grid Map (Manhattan Style)**

**Algorithm:**
- Create regular grid of intersections (nodes)
- Connect adjacent nodes with links
- Grid size: sqrt(nodes) × sqrt(nodes)
- Link length: map_size / sqrt(nodes)
- Add traffic signals at major intersections

**Parameters:**
- `--map-size`: Total map area in meters
- `--nodes`: Number of intersections
- `--link-density`: Links per node (default: 3-4)

### **2. Radial Map (City Center)**

**Algorithm:**
- Create central node (city center)
- Create concentric rings of nodes
- Create radial spokes connecting center to outer rings
- Add circumferential links connecting nodes in same ring

**Parameters:**
- `--rings`: Number of concentric rings (default: 5)
- `--spokes`: Number of radial spokes (default: 8)
- `--ring-spacing`: Distance between rings in meters

### **3. Random Network**

**Algorithm:**
- Generate random node positions within map bounds
- Connect nodes using minimum spanning tree (ensure connectivity)
- Add additional random links to increase connectivity
- Ensure no isolated subgraphs

**Parameters:**
- `--connectivity`: Average links per node (default: 3.5)
- `--min-distance`: Minimum distance between nodes

### **4. Real Map (OpenStreetMap)**

**Algorithm:**
- Use OSMnx library to download street network
- Convert OSM nodes to simulator nodes
- Convert OSM ways to simulator links
- Extract traffic signal locations

**Parameters:**
- `--osm-bounds`: Bounding box "lat_min,lon_min,lat_max,lon_max"
- `--osm-place`: Place name (e.g., "Manhattan, New York")
- `--network-type`: "drive", "walk", "bike", "all"

---

## 👥 Person Generation Strategy

### **Activity Types Distribution:**

| Activity Type | Percentage | Avg Duration | Typical Time |
|--------------|------------|--------------|--------------|
| Home         | Baseline   | 12-16 hours  | 22:00-08:00  |
| Work         | 70%        | 8-9 hours    | 08:00-17:00  |
| School       | 15%        | 6-7 hours    | 08:00-15:00  |
| Shopping     | 40%        | 1-2 hours    | 10:00-20:00  |
| Leisure      | 30%        | 2-4 hours    | 18:00-22:00  |
| Restaurant   | 25%        | 1-2 hours    | 12:00-14:00, 19:00-21:00 |
| Gym          | 20%        | 1-2 hours    | 06:00-08:00, 18:00-20:00 |
| Park         | 15%        | 1-3 hours    | 10:00-18:00  |

### **Daily Schedule Generation:**

1. **Assign Home Location**: Random node designated as residential
2. **Generate Main Activity**: Work/School for most persons
3. **Add Optional Activities**: Shopping, Leisure, etc.
4. **Temporal Consistency**: Activities must not overlap
5. **Travel Time**: Calculate realistic travel time between locations
6. **Return Home**: Last activity should return to home location

### **Driver Profile Types:**

| Profile   | Aggressiveness | MaxSpeed | ReactionTime | MinGap |
|-----------|----------------|----------|--------------|--------|
| Cautious  | 0.2-0.4        | 0.8-0.95 | 1.1-1.3      | 1.1-1.3|
| Normal    | 0.4-0.6        | 0.95-1.1 | 0.9-1.1      | 0.9-1.1|
| Aggressive| 0.7-0.9        | 1.1-1.3  | 0.7-0.9      | 0.6-0.8|

Distribution: 25% Cautious, 50% Normal, 25% Aggressive

---

## 🚗 Vehicle-Person Assignment

### **Modal Split (Default):**
- Car: 40%
- Bicycle: 20%
- Motorcycle: 10%
- Bus: 20%
- Subway: 10%

### **Vehicle Ownership:**
- 60% of persons own 1 vehicle (car, bicycle, or motorcycle)
- 25% own 2 vehicles (typically car + bicycle)
- 10% own 3 vehicles (car + bicycle + motorcycle)
- 5% own no private vehicle (public transport only)

### **Vehicle Distribution:**
- Cars: 50% sedan, 30% SUV, 20% compact
- Bicycles: 60% city bike, 30% mountain bike, 10% electric
- Motorcycles: 40% standard, 40% sport, 20% scooter

---

## ⏰ Temporal Distribution

### **Peak Hours:**
- Morning Rush: 07:00-09:00 (30% of daily trips)
- Midday: 10:00-16:00 (30% of daily trips)
- Evening Rush: 17:00-19:00 (30% of daily trips)
- Night: 20:00-06:00 (10% of daily trips)

### **Trip Generation:**
- Use Poisson distribution for trip start times
- Higher λ during peak hours
- Account for activity duration constraints

---

## ✅ Validation Rules

### **1. Graph Connectivity:**
- [ ] All nodes are reachable from any other node
- [ ] No isolated subgraphs
- [ ] All links have valid from/to nodes

### **2. Person Schedule Validation:**
- [ ] Activities are temporally ordered (sequence 0, 1, 2, ...)
- [ ] Activity times don't overlap
- [ ] First activity has no arrivalLogistics
- [ ] Travel times are realistic (< 2 hours between activities)
- [ ] All referenced vehicles exist

### **3. Vehicle-Person Relationship:**
- [ ] Every ownedVehicle reference exists
- [ ] Vehicle IDs are unique
- [ ] Vehicles are parked at valid nodes

### **4. Infrastructure Integrity:**
- [ ] GPS coordinates within valid range (-90 to 90 lat, -180 to 180 lon)
- [ ] Link lengths are positive and reasonable (< 10 km)
- [ ] Lane counts are valid (1-6)
- [ ] Speed limits are reasonable (10-130 km/h)
- [ ] All node references in links exist

### **5. Public Transport:**
- [ ] Bus/Subway routes connect existing nodes
- [ ] Stops/Stations exist at valid nodes
- [ ] Capacity is reasonable (buses: 30-80, subways: 150-300)
- [ ] Start times are within simulation duration

---

## 📊 Metadata Output

### **metadata.json Structure:**

```json
{
  "scenario_name": "city_simulation_1000",
  "generation_timestamp": "2026-01-26T22:30:00Z",
  "generator_version": "1.0.0",
  "random_seed": 42,
  "statistics": {
    "actors": {
      "persons": 1000,
      "cars": 600,
      "bicycles": 250,
      "motorcycles": 150,
      "buses": 20,
      "subways": 5,
      "nodes": 200,
      "links": 400,
      "signals": 50
    },
    "modal_split": {
      "car": 40.5,
      "bicycle": 19.8,
      "motorcycle": 10.2,
      "bus": 19.0,
      "subway": 10.5
    },
    "trip_distribution": {
      "peak_morning": 298,
      "midday": 315,
      "peak_evening": 302,
      "night": 85
    },
    "network": {
      "map_type": "grid",
      "area_km2": 25.0,
      "avg_link_length": 500.0,
      "network_density": 16.0,
      "micro_links": 80,
      "meso_links": 320
    },
    "activity_distribution": {
      "Home": 1000,
      "Work": 700,
      "School": 150,
      "Shopping": 400,
      "Leisure": 300,
      "Restaurant": 250,
      "Gym": 200,
      "Park": 150
    }
  },
  "validation": {
    "passed": true,
    "warnings": [],
    "errors": []
  }
}
```

---

## 🛠️ Implementation Requirements

### **Python Libraries:**
```
numpy
pandas
networkx
geopy
osmnx (optional, for real maps)
argparse
json
matplotlib (for visualization)
tqdm (for progress bars)
```

### **Code Structure:**

```python
generate_scenario.py
├── main()                      # Entry point
├── parse_arguments()           # CLI parsing
├── generators/
│   ├── map_generator.py
│   │   ├── generate_grid_map()
│   │   ├── generate_radial_map()
│   │   ├── generate_random_map()
│   │   └── generate_real_map()
│   ├── person_generator.py
│   │   ├── generate_persons()
│   │   ├── generate_schedule()
│   │   ├── assign_activities()
│   │   └── create_driver_profile()
│   ├── vehicle_generator.py
│   │   ├── generate_cars()
│   │   ├── generate_bicycles()
│   │   ├── generate_motorcycles()
│   │   └── assign_vehicles_to_persons()
│   ├── transport_generator.py
│   │   ├── generate_buses()
│   │   ├── generate_subways()
│   │   └── create_routes()
│   └── infrastructure_generator.py
│       ├── generate_nodes()
│       ├── generate_links()
│       └── generate_signals()
├── validators/
│   ├── validate_graph.py
│   ├── validate_schedules.py
│   ├── validate_references.py
│   └── validate_constraints.py
├── utils/
│   ├── id_generator.py        # Generate unique IDs
│   ├── time_utils.py          # Time conversions
│   ├── distance_utils.py      # Distance calculations
│   └── json_utils.py          # JSON serialization
└── writers/
    ├── write_scenario.py       # Write JSON files
    ├── write_metadata.py       # Write metadata
    └── write_visualization.py  # Generate HTML viz
```

### **Key Functions:**

```python
def generate_unique_id(actor_type: str, index: int) -> str:
    """Generate unique actor ID following pattern: htcaid:type;name"""
    return f"htcaid:{actor_type};{actor_type}_{index:06d}"

def calculate_travel_time(from_node, to_node, mode: str) -> int:
    """Calculate realistic travel time in seconds"""
    # Distance-based calculation with mode-specific speeds
    pass

def assign_parking_location(vehicle_type: str, nodes: list) -> str:
    """Assign appropriate parking location for vehicle"""
    # Residential zones for personal vehicles
    pass

def create_activity_schedule(person_profile: dict, nodes: list) -> list:
    """Generate realistic daily activity schedule"""
    # Sequential activities with temporal constraints
    pass

def validate_scenario_integrity(scenario: dict) -> tuple[bool, list]:
    """Validate all scenario constraints"""
    # Return (is_valid, list_of_errors)
    pass
```

---

## 📝 Example Usage

### **Small Test Scenario:**
```bash
python generate_scenario.py \
  --name "test_scenario_10" \
  --output-dir "./scenarios/test_10" \
  --persons 10 \
  --cars 6 \
  --bicycles 3 \
  --motorcycles 1 \
  --nodes 20 \
  --links 40 \
  --map-type grid \
  --map-size 1000 \
  --verbose \
  --validate
```

### **Medium Urban Scenario:**
```bash
python generate_scenario.py \
  --name "city_500" \
  --output-dir "./scenarios/city_500" \
  --persons 500 \
  --modal-split "car:45,bicycle:25,motorcycle:10,bus:15,subway:5" \
  --map-type grid \
  --map-size 3000 \
  --micro-links-percentage 25 \
  --peak-hours "7-9,12-13,17-19" \
  --random-seed 123 \
  --verbose \
  --validate
```

### **Large City Scenario:**
```bash
python generate_scenario.py \
  --name "metropolis_5000" \
  --output-dir "./scenarios/metropolis_5000" \
  --persons 5000 \
  --cars 3000 \
  --bicycles 1000 \
  --motorcycles 500 \
  --buses 100 \
  --subways 20 \
  --nodes 1000 \
  --links 2500 \
  --signals 300 \
  --map-type radial \
  --map-size 10000 \
  --micro-links-percentage 30 \
  --simulation-duration 86400 \
  --verbose \
  --validate \
  --format split
```

### **Real-World Map:**
```bash
python generate_scenario.py \
  --name "manhattan_downtown" \
  --output-dir "./scenarios/manhattan" \
  --persons 2000 \
  --map-type real \
  --osm-place "Manhattan, New York, USA" \
  --network-type "drive" \
  --modal-split "car:30,bicycle:15,motorcycle:5,bus:30,subway:20" \
  --verbose \
  --validate
```

---

## 🎨 Additional Features

### **1. Visualization:**
- Generate HTML map with Folium/Leaflet showing network
- Color-code links by simulation mode (MESO/MICRO)
- Show person locations and activity distribution
- Timeline chart showing trip distribution over 24 hours

### **2. Statistics:**
- Average trip length by mode
- Network density (links per node)
- Activity type distribution
- Vehicle ownership statistics
- Peak hour load analysis

### **3. Export Formats:**
- JSON (primary)
- CSV (for analysis)
- GraphML (for network analysis tools)
- GeoJSON (for GIS applications)

### **4. Configuration Templates:**
- Commuter city (high car usage)
- Bike-friendly city (high bicycle usage)
- Transit-oriented (high bus/subway)
- Mixed-use urban (balanced modal split)

---

## 🔧 Error Handling

### **Validation Errors:**
- Clear error messages with actor ID and issue
- Suggestions for fixing common problems
- Optional auto-fix for minor issues

### **Generation Failures:**
- Rollback on validation failure
- Partial output saved for debugging
- Detailed log file with stack traces

### **Edge Cases:**
- Handle scenarios with very few actors
- Ensure minimum network connectivity
- Handle conflicting constraints gracefully

---

## 📚 Documentation

### **Docstrings:**
- All functions must have comprehensive docstrings
- Include parameter types and return types
- Provide usage examples

### **README.md:**
- Installation instructions
- Usage examples
- Configuration options
- Troubleshooting guide

### **Examples:**
- Include 3-5 example scenarios
- Show different map types and scales
- Demonstrate various modal splits

---

## 🎯 Success Criteria

A successfully generated scenario must:
1. ✅ Pass all validation checks
2. ✅ Have connected network (no isolated nodes)
3. ✅ Have temporally consistent person schedules
4. ✅ Match specified modal split (±5%)
5. ✅ Have realistic activity distributions
6. ✅ Be loadable by Hyperbolic Time Chamber simulator
7. ✅ Generate expected metadata and statistics

---

## 📖 Reference Files

Use these files from the Hyperbolic Time Chamber repository as reference:

1. **Person-Centric Model Documentation:**
   - `/docs/PERSON_CENTRIC_MODEL.md`
   - `/docs/PERSON_CENTRIC_QUICK_REFERENCE.md`

2. **Example Scenario:**
   - `/docs/examples/person_centric_scenario.json`
   - `/docs/examples/hybrid_simple_scenario.json`

3. **Configuration Guide:**
   - `/docs/CONFIGURATION.md`
   - `/docs/SCENARIO_CREATION.md`

4. **Architecture:**
   - `/docs/ARCHITECTURE.md`
   - `/docs/API_REFERENCE.md`

---

## 🚀 Implementation Priority

### **Phase 1: Core Generation (MVP)**
1. Map generation (grid and random)
2. Person generation with basic schedules
3. Private vehicle generation (car, bicycle, motorcycle)
4. Basic validation
5. JSON output

### **Phase 2: Enhanced Features**
1. Public transport (bus, subway)
2. Traffic signals
3. Radial and real map generation
4. Advanced validation
5. Metadata and statistics

### **Phase 3: Advanced Features**
1. Visualization (HTML maps)
2. Multiple output formats
3. Configuration templates
4. Auto-fix validation issues
5. Performance optimization

---

## 💡 Tips for Implementation

1. **Start Small:** Test with 10-person scenarios first
2. **Modular Design:** Each generator should be independent
3. **Validation First:** Validate as you generate, not at the end
4. **Realistic Defaults:** Provide sensible defaults for all parameters
5. **Progress Reporting:** Use tqdm for long-running operations
6. **Reproducibility:** Always use random seed for consistency
7. **Documentation:** Document assumptions and constraints
8. **Testing:** Include unit tests for key functions

---

## 📞 Support

For questions about the Hyperbolic Time Chamber simulator:
- Check `/docs/` directory for comprehensive documentation
- Review example scenarios in `/docs/examples/`
- See `/docs/TROUBLESHOOTING.md` for common issues

---

**This specification provides everything needed to implement a comprehensive, production-ready scenario generator for the Hyperbolic Time Chamber traffic simulation framework.** 🎉
