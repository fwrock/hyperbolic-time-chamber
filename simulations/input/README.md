# 📁 Simulation Scenarios

This directory contains generated simulation scenarios for the Hyperbolic Time Chamber.

## Structure

```
simulations/input/
├── small_grid_scenario/        # Example scenario (generated)
│   ├── data/
│   │   ├── city_map.json
│   │   ├── nodes.json
│   │   ├── links.json
│   │   ├── vehicles.json
│   │   └── traffic_signals.json
│   ├── application.conf
│   ├── scenario_metadata.json
│   └── SCENARIO_REPORT.md
│
└── your_scenario/              # Your scenarios here
    └── ...
```

## Quick Start

### Generate a New Scenario

```bash
cd ../../scripts/
python3 generate_hybrid_scenario.py --config example_configs/small_grid.yaml
```

### List Available Scenarios

```bash
cd ../../scripts/
./list_scenarios.sh
```

### Run a Scenario

```bash
# Set the scenario path
export HTC_SIMULATION_DATA_PATH=/home/dean/PhD/hyperbolic-time-chamber/simulations/input/small_grid_scenario

# Run simulation
cd ../..
./build-and-run.sh
```

## Scenario Guidelines

- **Location:** All scenarios should be in `simulations/input/`
- **Structure:** Each scenario must have a `data/` directory with required JSON files
- **Naming:** Use descriptive names (e.g., `downtown_rush_hour`, `highway_corridor`)
- **Documentation:** Include `SCENARIO_REPORT.md` for each scenario

## See Also

- [Scenario Generation Scripts](../../scripts/README.md)
- [Quick Start Guide](../../scripts/QUICKSTART.md)
- [Configuration Reference](../../docs/CONFIGURATION.md)
