# 📚 Scenario Generation Scripts - Complete Index

Complete toolkit for generating, validating, and analyzing hybrid simulation scenarios.

---

## 🎯 What's This?

This directory contains **Python scripts** to generate complete, configurable hybrid (MICRO/MESO) simulation scenarios for the Hyperbolic Time Chamber. No manual JSON editing required!

---

## 🚀 Quick Start

```bash
# 1. Generate a test scenario
python3 generate_hybrid_scenario.py --quick-test

# 2. Validate it
python3 validate_scenario.py test_scenario

# 3. View statistics
python3 scenario_stats.py test_scenario

# 4. Run simulation
export HTC_SIMULATION_DATA_PATH=$(pwd)/test_scenario
cd .. && ./build-and-run.sh
```

**Done!** 🎉

---

## 📂 Files & Scripts

### Core Scripts

| Script | Purpose | Usage |
|--------|---------|-------|
| **generate_hybrid_scenario.py** | Main generator | Create complete scenarios from scratch |
| **migrate_to_hybrid.py** | 🔄 **NEW** Migration tool | Convert mobility → hybrid model |
| **validate_scenario.py** | Validator | Check scenario correctness |
| **scenario_stats.py** | Statistics | Analyze scenario properties |
| **run_examples.sh** | Helper | Quick examples runner |
| **test_migration.sh** | Migration tester | Test migration script |

### Configuration Files

| Directory | Contents |
|-----------|----------|
| **example_configs/** | Pre-made YAML configs |
| └─ small_grid.yaml | Small 3×3 grid test |
| └─ large_grid.yaml | Large 5×5 production |
| └─ random_network.yaml | Irregular topology |
| └─ micro_intensive.yaml | 90% MICRO links |
| └─ meso_baseline.yaml | Pure MESO baseline |
| └─ migration_config.yaml | 🔄 Migration configuration |
| └─ migration_simple.yaml | 🔄 Simple migration |
| └─ migration_micro_intensive.yaml | 🔄 MICRO-intensive migration |

### Documentation

| File | Description |
|------|-------------|
| **README.md** | Complete guide for scenario generation |
| **MIGRATION_GUIDE.md** | 🔄 **NEW** Complete migration guide |
| **QUICKSTART.md** | 5-minute getting started guide |
| **INDEX.md** | This file |

---

## 🎮 Common Commands

### Generate Scenarios (New)

```bash
# Quick test
python3 generate_hybrid_scenario.py --quick-test

# From config file
python3 generate_hybrid_scenario.py --config example_configs/small_grid.yaml

# Interactive mode
python3 generate_hybrid_scenario.py --interactive

# Custom parameters
python3 generate_hybrid_scenario.py \
  --name "My Scenario" \
  --nodes 25 \
  --vehicles 500 \
  --micro-ratio 0.3 \
  --output ../simulations/input/my_scenario
```

### 🔄 Migrate Existing Scenarios (Mobility → Hybrid)

```bash
# Quick migration test
./test_migration.sh

# Basic migration
python3 migrate_to_hybrid.py \
  --input input/cenario_1000_viagens \
  --output output/hybrid_scenario

# With vehicle type conversion
python3 migrate_to_hybrid.py \
  --input input/cenario_1000_viagens \
  --output output/hybrid_mixed \
  --convert-vehicles \
  --micro-ratio 0.3

# From config file
python3 migrate_to_hybrid.py --config example_configs/migration_config.yaml

# See full guide
cat MIGRATION_GUIDE.md
```

### Validate & Analyze

```bash
# Validate scenario
python3 validate_scenario.py <scenario_path>

# View statistics
python3 scenario_stats.py <scenario_path>

# View report
cat <scenario_path>/SCENARIO_REPORT.md
```

### Run Examples

```bash
# Show help
./run_examples.sh help

# Quick test
./run_examples.sh quick

# All examples
./run_examples.sh all

# Clean up
./run_examples.sh clean
```

---

## 🎨 Scenario Types

### 1. Quick Test
- **Nodes:** 9 (3×3 grid)
- **Vehicles:** 20
- **Duration:** 10 minutes
- **MICRO:** 50%
- **Use:** Quick testing

```bash
python3 generate_hybrid_scenario.py --quick-test
```

### 2. Small Grid
- **Nodes:** 9 (3×3 grid)
- **Vehicles:** 50
- **Duration:** 30 minutes
- **MICRO:** 40%
- **Use:** Development

```bash
python3 generate_hybrid_scenario.py --config example_configs/small_grid.yaml
```

### 3. Large Grid
- **Nodes:** 25 (5×5 grid)
- **Vehicles:** 500
- **Duration:** 2 hours
- **MICRO:** 30%
- **Use:** Production

```bash
python3 generate_hybrid_scenario.py --config example_configs/large_grid.yaml
```

### 4. Random Network
- **Nodes:** 20 (irregular)
- **Vehicles:** 150
- **Duration:** 1 hour
- **MICRO:** 50%
- **Use:** Routing tests

```bash
python3 generate_hybrid_scenario.py --config example_configs/random_network.yaml
```

### 5. MICRO Intensive
- **Nodes:** 9 (3×3 grid)
- **Vehicles:** 30
- **Duration:** 10 minutes
- **MICRO:** 90%
- **Use:** Vehicle dynamics

```bash
python3 generate_hybrid_scenario.py --config example_configs/micro_intensive.yaml
```

### 6. MESO Baseline
- **Nodes:** 25 (5×5 grid)
- **Vehicles:** 1000
- **Duration:** 2 hours
- **MICRO:** 0%
- **Use:** Performance baseline

```bash
python3 generate_hybrid_scenario.py --config example_configs/meso_baseline.yaml
```

---

## 📋 Generated Files

Every scenario generates:

```
scenario_name/
├── data/
│   ├── city_map.json          # Graph structure
│   ├── nodes.json              # Node actors
│   ├── links.json              # Link actors (MICRO + MESO)
│   ├── vehicles.json           # Vehicle actors
│   └── traffic_signals.json    # Traffic signals
├── simulation.json            # Simulation config
├── scenario_metadata.json      # Machine-readable stats
└── SCENARIO_REPORT.md          # Human-readable report
```

---

## 🔧 Configuration Parameters

### Network

| Parameter | Description | Values |
|-----------|-------------|--------|
| `network_type` | Topology | `grid`, `random` |
| `num_nodes` | Number of nodes | 9, 16, 25, ... |
| `grid_size` | Distance (meters) | 300, 500, ... |
| `base_latitude` | Starting latitude | -23.5505 |
| `base_longitude` | Starting longitude | -46.6333 |

### Links

| Parameter | Description | Values |
|-----------|-------------|--------|
| `default_lanes` | Lanes per link | 1-4 |
| `default_speed_limit` | Speed (km/h) | 30-80 |
| `default_capacity` | Vehicles/hour/lane | 1200-2000 |
| `micro_link_ratio` | MICRO ratio | 0.0-1.0 |

### Vehicles

| Parameter | Description | Values |
|-----------|-------------|--------|
| `num_vehicles` | Total vehicles | 20-1000+ |
| `vehicle_distribution` | Type ratios | car: 0.7, bus: 0.1, ... |

### Simulation

| Parameter | Description | Values |
|-----------|-------------|--------|
| `start_tick` | Start tick | 0 |
| `end_tick` | End tick | 600-7200 |
| `tick_duration` | Tick (seconds) | 1.0 |
| `random_seed` | Random seed | 42, ... |

---

## 🧪 Workflow

### Development Cycle

1. **Generate** → 2. **Validate** → 3. **Analyze** → 4. **Run** → 5. **Iterate**

```bash
# 1. Generate
python3 generate_hybrid_scenario.py --quick-test

# 2. Validate
python3 validate_scenario.py test_scenario

# 3. Analyze
python3 scenario_stats.py test_scenario
cat test_scenario/SCENARIO_REPORT.md

# 4. Run
export HTC_SIMULATION_DATA_PATH=$(pwd)/test_scenario
cd .. && ./build-and-run.sh

# 5. Iterate (modify config, regenerate)
```

---

## 📊 Example Output

### Validation

```
✅ Directory structure OK
✅ Loaded: data/city_map.json
✅ Loaded: data/nodes.json
🗺️  Validating city map...
🔵 Validating nodes...
🔗 Validating links...
  • MICRO links: 12 (50.0%)
  • MESO links: 12 (50.0%)
🚗 Validating vehicles...
  • CAR: 14
  • BUS: 2
✅ No issues found!
```

### Statistics

```
📊 Scenario Statistics
🗺️  Network Topology
  • Nodes: 9
  • Links: 24
🔗 Link Analysis
  • MICRO links: 12 (50.0%)
  • Average length: 500.0 m
🚗 Vehicle Analysis
  • Total vehicles: 20
  • CAR: 14 (70.0%)
  • BUS: 2 (10.0%)
```

---

## 🎓 Learning Path

### Beginner

1. Read [QUICKSTART.md](QUICKSTART.md)
2. Run `./run_examples.sh quick`
3. Explore generated files
4. Try `--interactive` mode

### Intermediate

1. Read [README.md](README.md)
2. Customize YAML configs
3. Generate different topologies
4. Run validation & stats

### Advanced

1. Edit `generate_hybrid_scenario.py`
2. Add custom network types
3. Implement new vehicle behaviors
4. Integrate with external data sources

---

## 🔗 Related Documentation

| Document | Topic |
|----------|-------|
| [MIGRATION_UPDATE_SUMMARY.md](MIGRATION_UPDATE_SUMMARY.md) | 🆕 **Migration v2.0 update** |
| [MIGRATION_GUIDE.md](MIGRATION_GUIDE.md) | Migration tool complete guide |
| [MIGRATION_SUMMARY_PT.md](MIGRATION_SUMMARY_PT.md) | Resumo em português |
| [HYBRID_INPUT_MODEL.md](../docs/HYBRID_INPUT_MODEL.md) | Data format specification |
| [SCENARIO_CREATION.md](../docs/SCENARIO_CREATION.md) | Manual creation guide |
| [CONFIGURATION.md](../docs/CONFIGURATION.md) | Configuration reference |
| [HYBRID_QUICK_REFERENCE.md](../docs/HYBRID_QUICK_REFERENCE.md) | Hybrid implementation |
| [ARCHITECTURE.md](../docs/ARCHITECTURE.md) | System architecture |

---

## 🐛 Troubleshooting

### Common Issues

| Problem | Solution |
|---------|----------|
| `ModuleNotFoundError: yaml` | `pip install pyyaml` |
| Permission denied | `chmod +x *.py *.sh` |
| Validation errors | Check SCENARIO_REPORT.md |
| Simulation won't run | Verify HTC_SIMULATION_DATA_PATH |

### Debug Checklist

- [ ] Python dependencies installed?
- [ ] Scripts executable?
- [ ] Valid YAML syntax?
- [ ] Scenario validated?
- [ ] Environment variables set?

---

## 💡 Tips & Best Practices

1. **Start small** → Use quick-test first
2. **Validate always** → Run validator before simulation
3. **Use seeds** → Set random_seed for reproducibility
4. **Read reports** → Check SCENARIO_REPORT.md
5. **Iterate** → Generate → Validate → Analyze → Refine
6. **Version control** → Keep configs in git
7. **Document** → Add descriptions to scenarios

---

## 🤝 Contributing

Want to add features?

1. Fork repository
2. Add to `generate_hybrid_scenario.py`
3. Create example config
4. Update documentation
5. Test thoroughly
6. Submit PR

---

## 📜 License

Same as Hyperbolic Time Chamber project.

---

## 🎯 Next Steps

Choose your path:

- 📖 **New?** → Read [QUICKSTART.md](QUICKSTART.md)
- 🚀 **Ready?** → Run `./run_examples.sh quick`
- 🎨 **Customize?** → Edit `example_configs/*.yaml`
- 🔧 **Advanced?** → Read [README.md](README.md)
- 💬 **Help?** → Check troubleshooting section

---

**Happy scenario generation! 🎉**
