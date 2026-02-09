# Vehicle Organization Guide

## Overview

This guide explains how to organize vehicle data in your simulation scenarios by splitting vehicles into separate files by type (cars, buses, bicycles, motorcycles, etc.) similar to how nodes and links are organized.

## Problem

Previously, all vehicles were stored in a single `vehicles.json` file, making it:
- Difficult to manage different vehicle types
- Hard to enable/disable specific vehicle types
- Less organized compared to other actor types (nodes, links)

## Solution

Split vehicles into separate JSON files by type:
```
data/
├── nodes.json           # Infrastructure nodes
├── links.json           # Road links
├── cars.json            # Cars only
├── buses.json           # Buses only
├── bicycles.json        # Bicycles only
├── motorcycles.json     # Motorcycles only
├── subways.json         # Subway trains only
└── traffic_signals.json # Traffic signals
```

## Simulation Configuration

Update your `simulation.json` to reference each vehicle type separately:

```json
{
  "simulation": {
    "actorsDataSources": [
      {
        "id": "nodes",
        "classType": "hybrid.actor.Node",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": { "path": "data/nodes.json" }
        }
      },
      {
        "id": "links",
        "classType": "hybrid.actor.Link",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": { "path": "data/links.json" }
        }
      },
      {
        "id": "cars",
        "classType": "hybrid.actor.Car",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": { "path": "data/cars.json" }
        }
      },
      {
        "id": "buses",
        "classType": "hybrid.actor.Bus",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": { "path": "data/buses.json" }
        }
      },
      {
        "id": "bicycles",
        "classType": "hybrid.actor.Bicycle",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": { "path": "data/bicycles.json" }
        }
      },
      {
        "id": "motorcycles",
        "classType": "hybrid.actor.Motorcycle",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": { "path": "data/motorcycles.json" }
        }
      }
    ]
  }
}
```

## Splitting Existing Files

### Automatic Method (Recommended)

Use the provided `split_vehicles.py` script:

```bash
# Split vehicles.json into separate type files
python scripts/split_vehicles.py \
    simulations/input/my_scenario/data/vehicles.json \
    simulations/input/my_scenario/data/

# The script will:
# 1. Analyze each vehicle's type
# 2. Create separate files (cars.json, buses.json, etc.)
# 3. Optionally update simulation.json
```

### Manual Method

1. **Identify Vehicle Types**
   
   Each vehicle has type information in multiple fields:
   - `typeActor`: e.g., `"hybrid.actor.Car"`
   - `data.dataType`: e.g., `"model.hybrid.entity.state.CarState"`
   - `data.content.actorType`: e.g., `"CAR"`

2. **Create Type-Specific Files**
   
   Filter vehicles by type and save to separate files:
   
   **cars.json**:
   ```json
   [
     {
       "id": "htcaid:car;car_0",
       "typeActor": "hybrid.actor.Car",
       "data": {
         "dataType": "model.hybrid.entity.state.CarState",
         "content": {
           "actorType": "CAR",
           ...
         }
       }
     }
   ]
   ```
   
   **buses.json**:
   ```json
   [
     {
       "id": "htcaid:bus;bus_0",
       "typeActor": "hybrid.actor.Bus",
       "data": {
         "dataType": "model.hybrid.entity.state.BusState",
         "content": {
           "actorType": "BUS",
           ...
         }
       }
     }
   ]
   ```

3. **Update simulation.json**
   
   Replace the single "vehicles" data source with multiple type-specific sources (see example above).

## Benefits

### 1. Easier Management
- Edit one vehicle type without affecting others
- Clear separation of concerns
- Better version control (smaller, focused diffs)

### 2. Flexible Scenarios
- Enable/disable specific vehicle types by commenting out data sources
- Run car-only scenarios quickly
- Test specific vehicle interactions

### 3. Better Performance
- Load only needed vehicle types
- Faster data parsing for smaller files
- Easier parallel loading

### 4. Clearer Organization
```
# Before (unclear)
data/vehicles.json  (5000 mixed vehicles)

# After (clear)
data/cars.json         (3500 cars)
data/buses.json        (200 buses)
data/bicycles.json     (1000 bicycles)
data/motorcycles.json  (300 motorcycles)
```

## Examples

See complete examples in:
- `docs/examples/organized_vehicles_scenario.json` - Simulation configuration
- `docs/examples/data/cars.json` - Car examples
- `docs/examples/data/buses.json` - Bus examples
- `docs/examples/data/bicycles.json` - Bicycle examples
- `docs/examples/data/motorcycles.json` - Motorcycle examples

## Vehicle Type Reference

### Car
- **Class**: `hybrid.actor.Car`
- **State**: `model.hybrid.entity.state.CarState`
- **Type**: `CAR`
- **Size**: 4.5m
- **ID Pattern**: `htcaid:car;car_*`

### Bus
- **Class**: `hybrid.actor.Bus`
- **State**: `model.hybrid.entity.state.BusState`
- **Type**: `BUS`
- **Size**: 12.0m
- **ID Pattern**: `htcaid:bus;bus_*`
- **Special**: Requires `busStops`, `capacity`, `label`

### Bicycle
- **Class**: `hybrid.actor.Bicycle`
- **State**: `model.hybrid.entity.state.BicycleState`
- **Type**: `BICYCLE`
- **Size**: 2.0m
- **ID Pattern**: `htcaid:bicycle;bicycle_*`
- **Special**: `prefersBikeLane`, `canUseSidewalk`

### Motorcycle
- **Class**: `hybrid.actor.Motorcycle`
- **State**: `model.hybrid.entity.state.MotorcycleState`
- **Type**: `MOTORCYCLE`
- **Size**: 2.5m
- **ID Pattern**: `htcaid:motorcycle;motorcycle_*`
- **Special**: `canFilterLanes`, `aggressiveness`

### Subway
- **Class**: `hybrid.actor.Subway`
- **State**: `model.hybrid.entity.state.SubwayState`
- **Type**: `SUBWAY`
- **ID Pattern**: `htcaid:subway;subway_*`
- **Special**: Uses rail network, station stops

## Migration Checklist

- [ ] Backup your existing `vehicles.json` file
- [ ] Run `split_vehicles.py` script or manually split files
- [ ] Verify all vehicles are correctly categorized
- [ ] Update `simulation.json` configuration
- [ ] Test the scenario runs successfully
- [ ] Commit changes to version control

## Troubleshooting

### "Unknown" Vehicle Type

If the script creates an `unknowns.json` file:
1. Check the vehicle's `typeActor`, `dataType`, and `actorType` fields
2. Ensure they match one of the standard types
3. Manually move to the correct file

### Missing Vehicles After Split

Verify the count:
```bash
# Count before
jq '. | length' data/vehicles.json

# Count after (sum should match)
jq '. | length' data/cars.json
jq '. | length' data/buses.json
# ... etc
```

### Simulation Won't Start

Check:
1. All referenced JSON files exist in the `data/` directory
2. The `classType` in `simulation.json` matches the vehicle types
3. No syntax errors in JSON files (`jq . file.json` to validate)

## See Also

- [SCENARIO_CREATION.md](SCENARIO_CREATION.md) - Creating new scenarios
- [HYBRID_INPUT_MODEL.md](HYBRID_INPUT_MODEL.md) - Input data format
- [examples/README.md](examples/README.md) - Example scenarios
