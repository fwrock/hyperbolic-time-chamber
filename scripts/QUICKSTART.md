# 🚀 Quick Start: Hybrid Scenario Generation

Get started with scenario generation in 5 minutes!

## Prerequisites

```bash
# Install Python dependencies
pip install pyyaml
```

## Generate Your First Scenario

### Option 1: Quick Test (Fastest)

```bash
cd scripts/
python3 generate_hybrid_scenario.py --quick-test
```

**Result:** Small test scenario in `./test_scenario/`

### Option 2: Use Example Config

```bash
cd scripts/
python3 generate_hybrid_scenario.py --config example_configs/small_grid.yaml
```

**Result:** Scenario in `../simulations/input/small_grid_scenario/`

### Option 3: Interactive Mode

```bash
cd scripts/
python3 generate_hybrid_scenario.py --interactive
```

Follow the prompts!

## Validate the Scenario

```bash
python3 validate_scenario.py test_scenario
```

Should output: ✅ No issues found!

## Run the Simulation

```bash
# Set the data path
export HTC_SIMULATION_DATA_PATH=$(pwd)/test_scenario

# Go to project root
cd ..

# Build and run
./build-and-run.sh
```

## What's Generated?

```
test_scenario/
├── data/
│   ├── city_map.json          # Graph for routing
│   ├── nodes.json              # Intersections
│   ├── links.json              # Roads (MICRO + MESO)
│   ├── vehicles.json           # Cars, buses, bikes, motorcycles
│   └── traffic_signals.json    # Traffic lights
├── simulation.json            # Config file
├── scenario_metadata.json      # Statistics
└── SCENARIO_REPORT.md          # Human-readable report
```

## Next Steps

1. **Read the report:**
   ```bash
   cat test_scenario/SCENARIO_REPORT.md
   ```

2. **Try different configs:**
   ```bash
   # Large scenario
   python3 generate_hybrid_scenario.py --config example_configs/large_grid.yaml
   
   # MICRO intensive
   python3 generate_hybrid_scenario.py --config example_configs/micro_intensive.yaml
   
   # Random network
   python3 generate_hybrid_scenario.py --config example_configs/random_network.yaml
   ```

3. **Create custom scenario:**
   ```bash
   python3 generate_hybrid_scenario.py \
     --name "My Test" \
     --nodes 16 \
     --vehicles 200 \
     --micro-ratio 0.4 \
     --output ../simulations/input/my_test
   ```

4. **Use the helper script:**
   ```bash
   ./run_examples.sh help      # See all examples
   ./run_examples.sh quick     # Generate quick test
   ./run_examples.sh small     # Generate small grid
   ./run_examples.sh all       # Generate all examples
   ```

## Troubleshooting

**Problem:** `ModuleNotFoundError: No module named 'yaml'`
```bash
pip install pyyaml
```

**Problem:** Permission denied
```bash
chmod +x generate_hybrid_scenario.py
chmod +x validate_scenario.py
chmod +x run_examples.sh
```

**Problem:** Validation errors
- Check `SCENARIO_REPORT.md` for statistics
- Re-run validation with: `python3 validate_scenario.py <path>`
- Review error messages carefully

## Key Parameters

| Parameter | Description | Example |
|-----------|-------------|---------|
| `--nodes` | Number of intersections | 9, 16, 25 |
| `--vehicles` | Total vehicles | 50, 200, 1000 |
| `--micro-ratio` | % of MICRO links | 0.3 (30%) |
| `--network-type` | Topology | grid, random |
| `--seed` | Random seed | 42 |

## Example Scenarios

| Name | Nodes | Vehicles | Duration | MICRO % | Use Case |
|------|-------|----------|----------|---------|----------|
| Quick Test | 9 | 20 | 10 min | 50% | Development |
| Small Grid | 9 | 50 | 30 min | 40% | Testing |
| Large Grid | 25 | 500 | 2 hours | 30% | Production |
| MICRO Intensive | 9 | 30 | 10 min | 90% | Vehicle dynamics |
| MESO Baseline | 25 | 1000 | 2 hours | 0% | Performance baseline |

## Full Documentation

- [README.md](README.md) - Complete guide
- [example_configs/](example_configs/) - Configuration examples
- Run `./run_examples.sh help` for more examples

---

**Ready to go! 🎯**
