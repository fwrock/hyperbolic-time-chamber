# Quick Fix Summary

## Issues Fixed

### 1. Cluster Router Error ✓
**Problem:** `requirement failed: totalInstances of cluster router must be > 0`

**Root Cause:** When no actor data sources were configured, the router initialization failed with `totalInstances = 0`

**Fix:** Modified [LoadDataManager.scala](../src/main/scala/core/actor/manager/LoadDataManager.scala):
- Added validation to check if `dataSourceAmount == 0`
- Early return if no data sources (skips router creation)
- Added `Math.max(1, ...)` guards to ensure minimum 1 instance

**Changes:**
```scala
// Lines 51-67: Added validation
if (dataSourceAmount == 0) {
  logWarning("No data sources to load. Skipping actor creation.")
  simulationManager ! FinishCreationEvent()
  return
}

// Lines 106-108 & 130-132: Ensured minimum instances
val totalInstances = Math.max(1, amountDataSources)
val maxInstancesPerNode = Math.max(1, Math.max(10, amountDataSources / 8))
```

### 2. Vehicle Organization ✓
**Problem:** Vehicles not organized by type like nodes and links

**Solution:** Created tools and documentation for splitting vehicles into type-specific files

**New Files:**
1. `scripts/split_vehicles.py` - Automated splitting tool
2. `docs/VEHICLE_ORGANIZATION.md` - Comprehensive guide
3. `docs/examples/organized_vehicles_scenario.json` - Example configuration
4. `docs/examples/data/{cars,buses,bicycles,motorcycles}.json` - Example data files

## Usage

### Fix the Cluster Router Error
The fix is automatic - rebuild and the error will be prevented:
```bash
sbt clean compile
```

### Organize Vehicles by Type

#### Option 1: Automated (Recommended)
```bash
# Split existing vehicles.json
python scripts/split_vehicles.py \
    simulations/input/YOUR_SCENARIO/data/vehicles.json \
    simulations/input/YOUR_SCENARIO/data/

# Update simulation.json when prompted
```

#### Option 2: Manual
1. Copy example: `cp docs/examples/organized_vehicles_scenario.json your_scenario/`
2. Split vehicles manually by type
3. Update simulation.json to reference individual files

#### Option 3: Generate New Scenario
```bash
# New scenarios from generate_hybrid_scenario.py already create organized files
python scripts/generate_hybrid_scenario.py --quick-test
```

## Configuration Example

### Before (Single File)
```json
{
  "actorsDataSources": [
    {
      "id": "vehicles",
      "classType": "hybrid.actor.Car",
      "dataSource": { "path": "data/vehicles.json" }
    }
  ]
}
```

### After (Organized by Type)
```json
{
  "actorsDataSources": [
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
```

## Benefits

1. **Error Prevention**: Cluster router error can't occur with 0 data sources
2. **Better Organization**: Vehicles organized like other actor types
3. **Easier Management**: Enable/disable vehicle types independently
4. **Clearer Structure**: Separate files for different vehicle types
5. **Flexible Testing**: Run scenarios with specific vehicle combinations

## Testing

### Verify the Fix
```bash
# Test with empty scenario
cd simulations/input/test_empty
# Ensure actorsDataSources is empty or missing
./build-and-run.sh  # Should not crash

# Test with organized vehicles
cd simulations/input/small_grid_scenario
python ../../scripts/split_vehicles.py data/vehicles.json data/
# Update simulation.json
./build-and-run.sh  # Should work with split files
```

## See Also

- [VEHICLE_ORGANIZATION.md](VEHICLE_ORGANIZATION.md) - Complete vehicle organization guide
- [LoadDataManager.scala](../src/main/scala/core/actor/manager/LoadDataManager.scala) - Source code changes
- [organized_vehicles_scenario.json](examples/organized_vehicles_scenario.json) - Example configuration
