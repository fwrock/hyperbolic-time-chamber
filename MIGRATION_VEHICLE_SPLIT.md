# Vehicle File Organization Migration ✅

## Summary

Successfully updated the Hyperbolic Time Chamber to generate and use **separate vehicle files by type** instead of a single unified `vehicles.json` file.

## Changes Made

### 1. Fixed Cluster Router Error ✓

**File:** [src/main/scala/core/actor/manager/LoadDataManager.scala](src/main/scala/core/actor/manager/LoadDataManager.scala)

**Problem:** Crash when `totalInstances = 0` for cluster router
**Solution:** 
- Added validation for empty data sources
- Ensured minimum 1 instance for routers
- Early return when no actors to load

### 2. Updated Scenario Generator ✓

**File:** [scripts/generate_hybrid_scenario.py](scripts/generate_hybrid_scenario.py)

**Changes:**
- Replaced `_write_vehicles()` with `_write_vehicles_by_type()`
- Now generates separate files:
  - `cars.json` (hybrid.actor.Car)
  - `buses.json` (hybrid.actor.Bus)
  - `bicycles.json` (hybrid.actor.Bicycle)
  - `motorcycles.json` (hybrid.actor.Motorcycle)
- Updated `simulation.json` generation to reference all vehicle types
- Added vehicle-type-specific fields (bicycle lanes, motorcycle filtering, etc.)

### 3. Created Tools & Documentation ✓

**New Files:**
- [scripts/split_vehicles.py](scripts/split_vehicles.py) - Tool to split existing unified files
- [docs/VEHICLE_ORGANIZATION.md](docs/VEHICLE_ORGANIZATION.md) - Complete guide
- [docs/FIX_VEHICLE_ORGANIZATION.md](docs/FIX_VEHICLE_ORGANIZATION.md) - Quick fix reference
- [docs/examples/organized_vehicles_scenario.json](docs/examples/organized_vehicles_scenario.json) - Example scenario
- [docs/examples/data/](docs/examples/data/) - Example vehicle type files

**Updated Files:**
- [docs/examples/README.md](docs/examples/README.md) - Added reference to organized scenario
- [scripts/README.md](scripts/README.md) - Documented split_vehicles.py

## File Structure

### Before (Old)
```
scenario/
├── data/
│   ├── nodes.json
│   ├── links.json
│   ├── vehicles.json          # ❌ All vehicles mixed together
│   └── traffic_signals.json
└── simulation.json
```

### After (New)
```
scenario/
├── data/
│   ├── nodes.json
│   ├── links.json
│   ├── cars.json               # ✓ Cars only
│   ├── buses.json              # ✓ Buses only
│   ├── bicycles.json           # ✓ Bicycles only
│   ├── motorcycles.json        # ✓ Motorcycles only
│   └── traffic_signals.json
└── simulation.json              # ✓ References all types
```

## Configuration Example

### simulation.json
```json
{
  "simulation": {
    "actorsDataSources": [
      { "id": "nodes", "classType": "hybrid.actor.Node", ... },
      { "id": "links", "classType": "hybrid.actor.Link", ... },
      { "id": "cars", "classType": "hybrid.actor.Car",
        "dataSource": { "path": "data/cars.json" } },
      { "id": "buses", "classType": "hybrid.actor.Bus",
        "dataSource": { "path": "data/buses.json" } },
      { "id": "bicycles", "classType": "hybrid.actor.Bicycle",
        "dataSource": { "path": "data/bicycles.json" } },
      { "id": "motorcycles", "classType": "hybrid.actor.Motorcycle",
        "dataSource": { "path": "data/motorcycles.json" } }
    ]
  }
}
```

## Testing

### Test Results ✓
```bash
$ python3 scripts/generate_hybrid_scenario.py --quick-test

✓ Cars: cars.json (14 vehicles)
✓ Buses: buses.json (2 vehicles)
✓ Bicycles: bicycles.json (2 vehicles)
✓ Motorcycles: motorcycles.json (2 vehicles)

$ ls test_scenario/data/
bicycles.json  buses.json  cars.json  city_map.json  
links.json  motorcycles.json  nodes.json  traffic_signals.json

$ jq '.[0].typeActor' test_scenario/data/buses.json
"hybrid.actor.Bus"
```

## Migration Guide

### For New Scenarios
✅ **Automatic** - Just use the generator:
```bash
python3 scripts/generate_hybrid_scenario.py --quick-test
# Automatically creates separate files
```

### For Existing Scenarios
Use the split tool:
```bash
# Backup first
cp simulations/input/my_scenario/data/vehicles.json \
   simulations/input/my_scenario/data/vehicles.json.backup

# Split into separate files
python3 scripts/split_vehicles.py \
    simulations/input/my_scenario/data/vehicles.json \
    simulations/input/my_scenario/data/

# Update simulation.json when prompted: y
```

## Benefits

### 1. Better Organization
- Clear separation by vehicle type
- Matches organization of nodes and links
- Easier to understand scenario composition

### 2. Flexible Configuration
- Enable/disable vehicle types independently
- Comment out data sources you don't need
- Test specific vehicle interactions

### 3. Improved Performance
- Load only needed vehicle types
- Faster JSON parsing (smaller files)
- Better parallel loading

### 4. Easier Development
- Smaller, focused files
- Better version control diffs
- Clear vehicle type boundaries

### 5. Type-Specific Features
Each vehicle type can have specific fields:
- **Buses:** capacity, route, stops, passengers
- **Bicycles:** prefersBikeLane, canUseSidewalk
- **Motorcycles:** canFilterLanes, aggressiveness
- **Cars:** standard fields

## Vehicle Type Reference

| Type | Class | State | Size | ID Pattern | Special Fields |
|------|-------|-------|------|------------|----------------|
| Car | hybrid.actor.Car | CarState | 4.5m | `htcaid:car;*` | driverAttributes |
| Bus | hybrid.actor.Bus | BusState | 12.0m | `htcaid:bus;*` | capacity, busStops, people |
| Bicycle | hybrid.actor.Bicycle | BicycleState | 2.0m | `htcaid:bicycle;*` | prefersBikeLane, canUseSidewalk |
| Motorcycle | hybrid.actor.Motorcycle | MotorcycleState | 2.5m | `htcaid:motorcycle;*` | canFilterLanes, aggressiveness |

## Backward Compatibility

⚠️ **Breaking Change:** Existing scenarios with `vehicles.json` need migration.

**Options:**
1. **Recommended:** Use `split_vehicles.py` to migrate
2. **Manual:** Split file and update `simulation.json`
3. **Regenerate:** Create new scenario from scratch

## Next Steps

- [x] Fix cluster router error
- [x] Update scenario generator
- [x] Create split tool
- [x] Write documentation
- [x] Test with example scenarios
- [ ] Migrate existing scenarios in `simulations/input/`
- [ ] Update CI/CD if needed
- [ ] Announce to users

## See Also

- [VEHICLE_ORGANIZATION.md](docs/VEHICLE_ORGANIZATION.md) - Detailed guide
- [FIX_VEHICLE_ORGANIZATION.md](docs/FIX_VEHICLE_ORGANIZATION.md) - Quick reference
- [scripts/split_vehicles.py](scripts/split_vehicles.py) - Migration tool
- [examples/organized_vehicles_scenario.json](docs/examples/organized_vehicles_scenario.json) - Example

---

**Status:** ✅ Complete and Tested  
**Date:** February 9, 2026  
**Impact:** All new scenarios automatically use organized structure
