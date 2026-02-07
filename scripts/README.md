# 🛠️ Scenario Generation Scripts

This directory contains Python scripts for generating complete hybrid simulation scenarios for the Hyperbolic Time Chamber.

---

## 📋 Table of Contents

1. [Quick Start](#quick-start)
2. [Scripts Overview](#scripts-overview)
3. [Configuration Files](#configuration-files)
4. [Generated Scenario Structure](#generated-scenario-structure)
5. [Advanced Usage](#advanced-usage)
6. [Examples](#examples)
7. [Troubleshooting](#troubleshooting)

---

## 🚀 Quick Start

### Prerequisites

```bash
# Install Python dependencies
pip install pyyaml
```

### Generate Your First Scenario

```bash
# Option 1: Interactive mode (easiest)
python generate_hybrid_scenario.py --interactive

# Option 2: Quick test scenario
python generate_hybrid_scenario.py --quick-test

# Option 3: From configuration file
python generate_hybrid_scenario.py --config example_configs/small_grid.yaml
```

### Run the Generated Scenario

```bash
# Set the data path
export HTC_SIMULATION_DATA_PATH=/path/to/generated/scenario

# Run simulation
cd ..
./build-and-run.sh
```

---

## 📂 Scripts Overview

### `generate_hybrid_scenario.py`

**Main scenario generator script** - Creates complete hybrid simulation scenarios with:

- ✅ Network topology (nodes, links)
- ✅ City map graph structure
- ✅ Mixed MICRO/MESO link configuration
- ✅ Multi-modal vehicles (Car, Bus, Bicycle, Motorcycle)
- ✅ Traffic signals
- ✅ Application configuration
- ✅ Detailed reports and metadata

**Features:**
- Configurable via YAML or command-line
- Interactive mode for quick setup
- Validates generated scenarios
- Generates human-readable reports
- Verbose output for debugging

---

## ⚙️ Configuration Files

Configuration files are located in `example_configs/` and use YAML format.

### Available Example Configs

| Config File | Description | Nodes | Vehicles | MICRO % |
|-------------|-------------|-------|----------|---------|
| `small_grid.yaml` | Small 3×3 test scenario | 9 | 50 | 40% |
| `large_grid.yaml` | Large 5×5 production scenario | 25 | 500 | 30% |
| `random_network.yaml` | Irregular network topology | 20 | 150 | 50% |
| `micro_intensive.yaml` | High-detail MICRO testing | 9 | 30 | 90% |
| `meso_baseline.yaml` | Pure MESO performance baseline | 25 | 1000 | 0% |

### Configuration Schema

```yaml
# Scenario metadata
name: "My Scenario"
description: "Detailed description"
output_dir: "../simulations/input/my_scenario"

# Simulation parameters
start_tick: 0
end_tick: 3600
tick_duration: 1.0

# Network configuration
network_type: "grid"  # "grid" or "random"
num_nodes: 9
grid_size: 500.0  # meters
base_latitude: -23.5505
base_longitude: -46.6333

# Link parameters
default_lanes: 2
default_speed_limit: 50.0  # km/h
default_capacity: 1800.0  # vehicles/hour/lane
micro_link_ratio: 0.3  # 0.0-1.0

# Vehicle parameters
num_vehicles: 100
vehicle_distribution:
  car: 0.7
  bus: 0.1
  bicycle: 0.1
  motorcycle: 0.1

# Random seed (for reproducibility)
random_seed: 42
verbose: true
```

---

## 📁 Generated Scenario Structure

After running the generator, you'll get:

```
output_directory/
├── data/
│   ├── city_map.json          # Graph structure for routing
│   ├── nodes.json              # Node actors (intersections)
│   ├── links.json              # Link actors (road segments)
│   ├── vehicles.json           # Vehicle actors (all types)
│   └── traffic_signals.json    # Traffic signal actors
│
├── simulation.json            # Simulation configuration
├── scenario_metadata.json      # Machine-readable metadata
└── SCENARIO_REPORT.md          # Human-readable report
```

### File Descriptions

#### `data/city_map.json`
Graph structure with vertices (nodes) and edges (links) for routing algorithms.

```json
{
  "vertices": {
    "node_0_0": {"latitude": -23.5505, "longitude": -46.6333},
    ...
  },
  "edges": [
    {"sourceId": "node_0_0", "targetId": "node_0_1", "weight": 500.0, "label": "link_0"},
    ...
  ]
}
```

#### `data/nodes.json`
Array of node actors with positions and connections.

```json
[
  {
    "id": "htcaid:node;node_0_0",
    "typeActor": "hybrid.actor.Node",
    "data": {
      "dataType": "model.hybrid.entity.state.NodeState",
      "content": {
        "latitude": -23.5505,
        "longitude": -46.6333,
        "links": ["htcaid:link;link_0", ...],
        ...
      }
    },
    "dependencies": {}
  },
  ...
]
```

#### `data/links.json`
Array of link actors with MICRO or MESO configuration.

```json
[
  {
    "id": "htcaid:link;link_0",
    "typeActor": "hybrid.actor.Link",
    "data": {
      "dataType": "model.hybrid.entity.state.LinkState",
      "content": {
        "from": "htcaid:node;node_0_0",
        "to": "htcaid:node;node_0_1",
        "length": 500.0,
        "lanes": 2,
        "speedLimit": 50.0,
        "simulationMode": "MICRO",  # or "MESO"
        "laneConfigurations": [...],  # Only for MICRO
        ...
      }
    },
    "dependencies": {...}
  },
  ...
]
```

#### `data/vehicles.json`
Array of vehicle actors (Car, Bus, Bicycle, Motorcycle) with origin/destination.

```json
[
  {
    "id": "htcaid:car;car_0",
    "typeActor": "hybrid.actor.Car",
    "data": {
      "dataType": "model.hybrid.entity.state.CarState",
      "content": {
        "startTick": 154,
        "origin": "htcaid:node;node_0_0",
        "destination": "htcaid:node;node_2_2",
        "driverAttributes": {
          "aggressiveness": 0.65,
          "reactionTimeFactor": 1.1,
          ...
        },
        ...
      }
    },
    "dependencies": {...}
  },
  ...
]
```

#### `simulation.json`
HOCON configuration file for the simulation.

```hocon
htc {
  simulation {
    name = "My Scenario"
    startTick = 0
    endTick = 3600
    
    actorsDataSources = [
      {
        id = "nodes"
        classType = "hybrid.actor.Node"
        creationType = "LoadBalancedDistributed"
        dataSource {
          type = "json"
          info { path = "${htc.simulation.dataPath}/data/nodes.json" }
        }
      },
      ...
    ]
    
    cityMapFile = "${htc.simulation.dataPath}/data/city_map.json"
  }
}
```

---

## 🎯 Advanced Usage

### Command-Line Options

```bash
# Generate with custom parameters
python generate_hybrid_scenario.py \
  --name "My Custom Scenario" \
  --output ../simulations/input/my_scenario \
  --nodes 16 \
  --vehicles 200 \
  --micro-ratio 0.4 \
  --network-type grid \
  --seed 12345

# Available options:
  --config PATH           # YAML configuration file
  --interactive           # Interactive mode
  --quick-test            # Quick test scenario
  --name TEXT             # Scenario name
  --output PATH           # Output directory
  --nodes INT             # Number of nodes
  --vehicles INT          # Number of vehicles
  --micro-ratio FLOAT     # MICRO link ratio (0.0-1.0)
  --network-type TYPE     # "grid" or "random"
  --seed INT              # Random seed
```

### Creating Custom Configurations

1. **Copy an example config:**
   ```bash
   cp example_configs/small_grid.yaml my_config.yaml
   ```

2. **Edit parameters:**
   ```yaml
   name: "My Custom Scenario"
   num_nodes: 16  # 4×4 grid
   num_vehicles: 200
   micro_link_ratio: 0.5
   ```

3. **Generate:**
   ```bash
   python generate_hybrid_scenario.py --config my_config.yaml
   ```

### Reproducibility

All scenarios use a **random seed** for reproducibility:

```yaml
random_seed: 42
```

Running with the same seed produces **identical** scenarios.

---

## 📖 Examples

### Example 1: Small Test Scenario

```bash
python generate_hybrid_scenario.py --quick-test
```

**Output:** `./test_scenario/` with 9 nodes, 20 vehicles, 10 minutes

### Example 2: Large Production Scenario

```bash
python generate_hybrid_scenario.py --config example_configs/large_grid.yaml
```

**Output:** `../simulations/input/large_grid_scenario/` with 25 nodes, 500 vehicles, 2 hours

### Example 3: MICRO-Intensive Testing

```bash
python generate_hybrid_scenario.py --config example_configs/micro_intensive.yaml
```

**Output:** Scenario with 90% MICRO links for detailed vehicle dynamics

### Example 4: Custom Scenario via CLI

```bash
python generate_hybrid_scenario.py \
  --name "Peak Hour Simulation" \
  --output ../simulations/input/peak_hour \
  --nodes 25 \
  --vehicles 800 \
  --micro-ratio 0.2
```

### Example 5: Interactive Mode

```bash
python generate_hybrid_scenario.py --interactive
```

Follow the prompts to configure your scenario interactively.

---

## 🧪 Testing Your Scenario

After generating a scenario:

### 1. Validate Structure

```bash
# Check generated files
ls -lh output_directory/data/

# Read the report
cat output_directory/SCENARIO_REPORT.md
```

### 2. Run Simulation

```bash
# Set environment variable
export HTC_SIMULATION_DATA_PATH=$(pwd)/output_directory

# Run
cd ..
./build-and-run.sh
```

### 3. Monitor Logs

```bash
tail -f logs/simulation.log
```

---

## 🐛 Troubleshooting

### Problem: Import error for `yaml`

**Solution:**
```bash
pip install pyyaml
```

### Problem: Permission denied

**Solution:**
```bash
chmod +x generate_hybrid_scenario.py
```

### Problem: Invalid configuration

**Solution:** Check YAML syntax with:
```bash
python -c "import yaml; yaml.safe_load(open('my_config.yaml'))"
```

### Problem: Generated scenario won't run

**Checklist:**
- [ ] Check `HTC_SIMULATION_DATA_PATH` is set correctly
- [ ] Verify all JSON files are valid: `cat data/*.json | jq .`
- [ ] Review `SCENARIO_REPORT.md` for statistics
- [ ] Check logs for specific error messages

### Problem: Too many vehicles for network

**Solution:** Adjust vehicle density:
```yaml
num_vehicles: 50  # Reduce from 500
```

Or increase network size:
```yaml
num_nodes: 25  # Increase from 9
```

---

## 📊 Scenario Statistics

After generation, check `SCENARIO_REPORT.md` for:

- ✅ Network topology summary
- ✅ Link distribution (MICRO vs MESO)
- ✅ Vehicle type breakdown
- ✅ Traffic signal count
- ✅ Average link characteristics
- ✅ Temporal distribution

---

## 🎨 Best Practices

1. **Start small:** Use `--quick-test` first
2. **Validate:** Always check `SCENARIO_REPORT.md`
3. **Use seeds:** Set `random_seed` for reproducibility
4. **Name clearly:** Use descriptive scenario names
5. **Document:** Add description in config
6. **Version control:** Keep config files in git
7. **Performance:** Start with MESO baseline, add MICRO gradually

---

## 📚 Related Documentation

- [HYBRID_INPUT_MODEL.md](../docs/HYBRID_INPUT_MODEL.md) - Data format specification
- [SCENARIO_CREATION.md](../docs/SCENARIO_CREATION.md) - Manual scenario creation guide
- [CONFIGURATION.md](../docs/CONFIGURATION.md) - Configuration reference
- [HYBRID_QUICK_REFERENCE.md](../docs/HYBRID_QUICK_REFERENCE.md) - Hybrid implementation guide

---

## 🤝 Contributing

To add new scenario types or features:

1. Edit `generate_hybrid_scenario.py`
2. Add example config in `example_configs/`
3. Update this README
4. Test with multiple configurations
5. Submit pull request

---

## 📝 License

Same as Hyperbolic Time Chamber project.

---

**Happy Scenario Generation! 🚀**
