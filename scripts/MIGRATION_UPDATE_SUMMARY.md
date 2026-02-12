# Migration Script Update Summary

## 🎯 Overview

Major architectural update to the `migrate_to_hybrid.py` script based on critical user feedback. The script now properly supports **dynamic runtime mode switching** and generates a **complete public transport ecosystem** with **Person actors** for proper model testing.

## 🔄 Key Changes

### 1. **ALL Links Get MICRO Fields** ✅
**Previous:** Only links selected as MICRO mode had microscopic fields  
**Now:** ALL links have complete MICRO fields (laneConfigurations, vehiclesByLane, etc.)

**Why:** Enables dynamic switching between MICRO and MESO modes at runtime via API without reloading scenarios.

The `simulationMode` field now indicates:
- **DEFAULT behavior** at scenario start
- Can be changed dynamically during simulation
- ALL links are prepared for both modes

### 2. **Vehicle Conversion = Private Vehicles Only** ✅
**Previous:** Cars could be converted to buses  
**Now:** Cars can only convert to bicycles or motorcycles

**Removed:** `VehicleTypeEnum.BUS` from vehicle conversion  
**Added:** `PublicTransportTypeEnum` (BUS, SUBWAY) for generated infrastructure

**New Ratios:**
```yaml
vehicle_conversion_ratios:
  car: 0.80       # 80% remain cars
  bicycle: 0.10   # 10% become bicycles
  motorcycle: 0.10  # 10% become motorcycles
```

### 3. **Public Transport Generation** ✅ NEW FEATURE
Generates complete public transport infrastructure and actors:

#### Bus System
- **Bus Stops:** At ~15% of nodes (configurable)
- **Bus Routes:** Multiple routes connecting bus stops
- **Bus Actors:** 3-8 buses per route with schedules
- **Parameters:** Capacity, start times, route assignments

#### Subway System
- **Subway Stations:** At ~5% of nodes (configurable)
- **Subway Lines:** Multiple lines connecting stations
- **Train Actors:** 2-5 trains per line with schedules
- **Parameters:** Capacity, start times, line assignments

**Configuration Example:**
```yaml
public_transport:
  generate: true
  
  bus:
    stop_coverage: 0.15
    num_routes: 5
    buses_per_route: [3, 8]
    bus_stops_per_route: [5, 15]
    capacity: 80
    
  subway:
    station_coverage: 0.05
    num_lines: 3
    trains_per_line: [2, 5]
    stations_per_line: [4, 10]
    capacity: 200
```

### 4. **Person Actor Generation** ✅ NEW FEATURE
Generates Person actors essential for person-centric model testing:

- **Number:** Configurable multiplier per vehicle trip (default: 2.0)
- **Origins/Destinations:** Based on vehicle trip patterns
- **Modality Preferences:** Configurable (car, bus, subway, bicycle)
- **Start Times:** Random distribution

**Configuration Example:**
```yaml
person_generation:
  generate: true
  persons_per_vehicle: 2.0
  
  modality_preferences:
    car: 0.4
    bus: 0.3
    subway: 0.2
    bicycle: 0.1
```

## 📊 New Data Structures

### Generated Entity Types
```
Nodes (migrated)
Links (ALL with MICRO fields)
Private Vehicles (cars, bicycles, motorcycles)
Bus Stops (infrastructure)
Subway Stations (infrastructure)
Bus Routes (with schedules)
Subway Routes (with schedules)
Buses (actor instances)
Subways/Trains (actor instances)
Persons (actor instances)
Traffic Signals (optional)
```

### File Output Structure
```
output/hybrid_scenario/
├── simulation.json          # Main config with all data sources
├── scenario_metadata.json   # Statistics and info
├── data/
│   ├── nodes_0.json
│   ├── nodes_1.json
│   ├── links_0.json
│   ├── links_1.json
│   ├── vehicles_0.json
│   ├── vehicles_1.json
│   ├── bus_stops_0.json     # NEW
│   ├── subway_stations_0.json  # NEW
│   ├── bus_routes_0.json    # NEW
│   ├── subway_routes_0.json # NEW
│   ├── buses_0.json         # NEW
│   ├── subways_0.json       # NEW
│   └── persons_0.json       # NEW
```

## 🔧 Implementation Details

### New Functions Added

```python
def _generate_public_transport(self) -> None:
    """Master function orchestrating public transport generation"""
    
def _generate_bus_stops(self) -> List[Dict]:
    """Generate bus stop actors at selected nodes"""
    
def _generate_subway_stations(self) -> List[Dict]:
    """Generate subway station actors at selected nodes"""
    
def _generate_bus_routes(self) -> Tuple[List[Dict], List[Dict]]:
    """Generate bus routes and bus actors"""
    
def _generate_subway_routes(self) -> Tuple[List[Dict], List[Dict]]:
    """Generate subway lines and train actors"""
    
def _generate_persons(self) -> List[Dict]:
    """Generate Person actors based on vehicle trips"""
```

### Modified Functions

```python
def _migrate_links(self) -> None:
    """ALL links now get laneConfigurations regardless of simulationMode"""
    
def _migrate_vehicles(self) -> None:
    """Now only handles private vehicles (cars, bicycles, motorcycles)"""
    
def _migrate_car_to_hybrid(self, car: Dict, target_type: VehicleTypeEnum) -> Dict:
    """Removed BUS from type_map, only private vehicle types"""
    
def _write_output(self) -> None:
    """Now writes 7+ new entity types"""
    
def _write_simulation_config(self) -> None:
    """Updated to include all new data sources"""
```

## 📝 Updated Configuration Files

All three YAML configs updated:

### migration_config.yaml (Complete)
- Full public transport configuration
- Person generation settings
- All new parameters documented

### migration_simple.yaml (Minimal)
- Basic public transport (2 bus routes, 1 subway line)
- Minimal person generation (1.5x vehicles)
- Most links MESO by default

### migration_micro_intensive.yaml (Detailed)
- Extensive public transport (8 bus routes, 4 subway lines)
- High person generation (2.5x vehicles)
- 60% links start in MICRO mode

## 🚀 Command-Line Updates

### New Arguments

```bash
# Public Transport Control
--no-public-transport         # Disable public transport generation
--bus-stop-coverage 0.15      # Ratio of nodes with bus stops
--subway-station-coverage 0.05  # Ratio of nodes with subway stations
--num-bus-routes 5            # Number of bus routes
--num-subway-routes 2         # Number of subway lines

# Person Generation Control
--no-persons                  # Disable Person actor generation
--persons-per-vehicle 2.0     # Average persons per vehicle trip

# Updated Vehicle Conversion (no --bus-ratio)
--car-ratio 0.8
--bicycle-ratio 0.1
--motorcycle-ratio 0.1
```

## 📈 Expected Results

### For cenario_1000_viagens (1,000 cars)

**Before Update:**
- 4,544 nodes
- 7,072 links (30% with MICRO fields)
- 1,000 vehicles (700 cars, 100 buses, 100 bicycles, 100 motorcycles)
- No public transport infrastructure
- No persons

**After Update:**
- 4,544 nodes
- 7,072 links (ALL with MICRO fields, 30% start in MICRO mode)
- 1,000 vehicles (800 cars, 100 bicycles, 100 motorcycles)
- ~680 bus stops
- ~227 subway stations
- 5 bus routes with ~25 buses total
- 2 subway lines with ~6 trains total
- ~2,000 Person actors
- Optional: ~1,136 traffic signals

## 🎯 Benefits

1. **Runtime Flexibility:** Any link can switch MICRO↔MESO at runtime via API
2. **Realistic Public Transport:** Complete infrastructure with routes, schedules, vehicles
3. **Person-Centric Testing:** Person actors enable proper multi-modal testing
4. **Separation of Concerns:** Private vehicles vs. public transport clearly separated
5. **Scalable:** Public transport scales independently of vehicle conversion

## 🧪 Testing

### Basic Test
```bash
cd /home/dean/PhD/hyperbolic-time-chamber/scripts

# Test help
python3 migrate_to_hybrid.py --help

# Run migration with simple config
python3 migrate_to_hybrid.py \
  --config example_configs/migration_simple.yaml
```

### Full Test with cenario_1000_viagens
```bash
python3 migrate_to_hybrid.py \
  --input ../simulations/input/cenario_1000_viagens \
  --output ../simulations/output/hybrid_1000 \
  --micro-ratio 0.3 \
  --convert-vehicles \
  --car-ratio 0.8 \
  --bicycle-ratio 0.1 \
  --motorcycle-ratio 0.1 \
  --bus-stop-coverage 0.15 \
  --subway-station-coverage 0.05 \
  --num-bus-routes 5 \
  --num-subway-routes 2 \
  --persons-per-vehicle 2.0 \
  --items-per-file 500
```

### Validation
Check output directory for all new entity types:
```bash
ls -la simulations/output/hybrid_1000/data/
# Should see: nodes, links, vehicles, bus_stops, subway_stations,
#             bus_routes, subway_routes, buses, subways, persons
```

## 📚 Documentation Status

**Updated:**
- ✅ `migrate_to_hybrid.py` (1,699 lines, fully refactored)
- ✅ All 3 YAML configuration files
- ✅ This summary document

**Needs Update:**
- ⏳ `MIGRATION_GUIDE.md` - Reflect architectural changes
- ⏳ `MIGRATION_SUMMARY_PT.md` - Portuguese version
- ⏳ `MIGRATION_COMPLETE.md` - Final status

## 🎓 Key Concepts

### Dynamic Mode Switching
```
ALL links have:
  simulationMode: "MESO" | "MICRO"  # Current behavior
  laneConfigurations: [...]         # Always present
  vehiclesByLane: {}                # Always present
  microTimeStep: 0.1                # Always present
  microTicksPerGlobalTick: 10       # Always present
  
At runtime, API can change simulationMode instantly because
all fields are already prepared.
```

### Public Transport vs. Private Vehicles
```
PRIVATE VEHICLES (converted from cars):
  - Cars (remain)
  - Bicycles (converted)
  - Motorcycles (converted)
  
PUBLIC TRANSPORT (generated from infrastructure):
  - Bus Stops → Bus Routes → Bus Actors
  - Subway Stations → Subway Lines → Train Actors
  
Separate pipelines ensure realistic public transport
with proper routes, schedules, and capacities.
```

### Person-Centric Model
```
Person actors enable:
  - Multi-modal trips (car → walk → subway → walk)
  - Mode choice decisions
  - Public transport boarding/alighting
  - Walking segments
  - Activity chains
  
Generated based on vehicle trip patterns to ensure
realistic origin-destination demand.
```

## 🏁 Next Steps

1. **Test Migration:** Run with cenario_1000_viagens
2. **Validate Output:** Check all entity types generated correctly
3. **Update Docs:** Refresh MIGRATION_GUIDE.md
4. **Integration Test:** Load in simulation system
5. **API Development:** Build runtime mode switching API

---

**Date:** 2025
**Script Version:** 2.0 (Major Refactor)
**Lines of Code:** 1,699 (previously 1,400)
**New Features:** 7+ (public transport infrastructure, person generation, dynamic switching)
