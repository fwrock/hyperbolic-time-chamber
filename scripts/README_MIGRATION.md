[![Migration](https://img.shields.io/badge/Migration-Mobility→Hybrid-blue)](./MIGRATION_GUIDE.md)
[![Python](https://img.shields.io/badge/Python-3.8+-green)](https://python.org)
[![Status](https://img.shields.io/badge/Status-Ready-success)](./migrate_to_hybrid.py)

# 🔄 Mobility → Hybrid Model Migration

Transform existing mobility model scenarios (MESO-only) into hybrid model scenarios (MICRO/MESO).

## Quick Links

- 📘 [**Complete Migration Guide**](./MIGRATION_GUIDE.md) - Full documentation
- 🇧🇷 [**Resumo em Português**](./MIGRATION_SUMMARY_PT.md) - Portuguese summary
- ✅ [**Implementation Complete**](./MIGRATION_COMPLETE.md) - What was built
- 📋 [**Index**](./INDEX.md) - All scripts

## ⚡ Quick Start

```bash
# 1. Test migration
./test_migration.sh

# 2. Basic migration
python3 migrate_to_hybrid.py \
  --input input/cenario_1000_viagens \
  --output output/hybrid_scenario

# 3. With vehicle conversion
python3 migrate_to_hybrid.py \
  --input input/cenario_1000_viagens \
  --output output/hybrid_mixed \
  --convert-vehicles \
  --micro-ratio 0.3

# 4. From config file
python3 migrate_to_hybrid.py \
  --config example_configs/migration_config.yaml
```

## 📋 What It Does

✅ **Reads** old mobility model (MESO-only)  
✅ **Transforms** to hybrid model (MICRO/MESO)  
✅ **Migrates** nodes, links, vehicles  
✅ **Converts** vehicle types (Car → Bus/Bicycle/Motorcycle)  
✅ **Generates** lane configurations for MICRO links  
✅ **Generates** traffic signals (optional)  
✅ **Splits** large files into chunks  
✅ **Validates** graph connectivity  
✅ **Creates** detailed reports  

## 📊 Example

**Input:** `cenario_1000_viagens` (mobility model)
- 4,544 nodes
- 7,072 links (all MESO)
- 1,000 cars

**Output:** `hybrid_scenario` (hybrid model)
- 4,544 nodes (hybrid)
- 7,072 links (30% MICRO, 70% MESO)
- 1,000 vehicles (65% cars, 15% buses, 10% bicycles, 10% motorcycles)
- 909 traffic signals

## 🎯 Configuration Options

### Hybrid Links
- `--micro-ratio` - Ratio of MICRO links (0.0-1.0)
- `--micro-strategy` - Selection strategy (random, arterial, highway)

### Vehicle Conversion
- `--convert-vehicles` - Enable vehicle type conversion
- `--car-ratio`, `--bus-ratio`, `--bicycle-ratio`, `--motorcycle-ratio`

### File Splitting
- `--items-per-file` - Maximum items per JSON file

### Traffic Signals
- `--signal-coverage` - Ratio of nodes with signals
- `--no-signals` - Disable signal generation

## 📁 Files

### Scripts
- [`migrate_to_hybrid.py`](./migrate_to_hybrid.py) - Main migration script (1400+ lines)
- [`test_migration.sh`](./test_migration.sh) - Quick test script

### Configurations
- [`example_configs/migration_config.yaml`](./example_configs/migration_config.yaml) - Full config
- [`example_configs/migration_simple.yaml`](./example_configs/migration_simple.yaml) - Simple
- [`example_configs/migration_micro_intensive.yaml`](./example_configs/migration_micro_intensive.yaml) - MICRO-intensive

### Documentation
- [`MIGRATION_GUIDE.md`](./MIGRATION_GUIDE.md) - Complete guide (11,000+ words)
- [`MIGRATION_SUMMARY_PT.md`](./MIGRATION_SUMMARY_PT.md) - Portuguese summary
- [`MIGRATION_COMPLETE.md`](./MIGRATION_COMPLETE.md) - Implementation details

## 🚀 Usage Examples

### Example 1: Simple Migration
```bash
python3 migrate_to_hybrid.py \
  --input input/cenario_1000_viagens \
  --output output/simple \
  --micro-ratio 0.2 \
  --no-signals
```

### Example 2: Full Migration with Diversity
```bash
python3 migrate_to_hybrid.py \
  --input input/cenario_1000_viagens \
  --output output/complete \
  --micro-ratio 0.3 \
  --micro-strategy arterial \
  --convert-vehicles \
  --car-ratio 0.6 \
  --bus-ratio 0.2 \
  --bicycle-ratio 0.1 \
  --motorcycle-ratio 0.1 \
  --signal-coverage 0.3
```

### Example 3: Using YAML Config
```bash
python3 migrate_to_hybrid.py \
  --config example_configs/migration_config.yaml
```

## 📈 Output Structure

```
output/hybrid_scenario/
├── data/
│   ├── city_map.json              # Migrated graph
│   ├── nodes_1.json               # Hybrid nodes
│   ├── links_1.json, links_2.json # Hybrid links (MICRO/MESO)
│   ├── cars_1.json                # Cars
│   ├── buses_1.json               # Buses (if converted)
│   ├── bicycles_1.json            # Bicycles (if converted)
│   ├── motorcycles_1.json         # Motorcycles (if converted)
│   └── traffic_signals_1.json     # Traffic signals (if generated)
├── simulation.json                # Updated configuration
├── scenario_metadata.json         # Migration metadata
└── MIGRATION_REPORT.md            # Detailed report
```

## 🎨 Transformations

### Nodes
```
mobility.actor.Node → hybrid.actor.Node
+ Adds: links[], hasHybridConnections, conflictZones
```

### Links
```
mobility.actor.Link → hybrid.actor.Link
+ Adds: simulationMode (MICRO/MESO)
+ Adds: laneConfigurations[] for MICRO links
+ Converts: speeds m/s → km/h
```

### Vehicles
```
mobility.actor.Car → hybrid.actor.{Car,Bus,Bicycle,Motorcycle}
+ Adds: currentSimulationMode, microState
+ Adds: driverAttributes
+ Adds: type-specific fields
```

## 📊 Statistics

Migration of `cenario_1000_viagens` with 30% MICRO:

| Entity | Source | Target | Notes |
|--------|--------|--------|-------|
| Nodes | 4,544 | 4,544 | All migrated |
| Links | 7,072 | 7,072 | 30% MICRO, 70% MESO |
| Vehicles | 1,000 | 1,000 | 4 types if converted |
| Signals | 0 | ~900 | If generated |
| Files | 15 | ~10 | Split by items_per_file |

## 🔍 Validation

The script automatically validates:
- ✅ Graph connectivity
- ✅ Dependency integrity
- ✅ Data conversion
- ✅ File references

## 🐛 Troubleshooting

### Input directory not found
```bash
# Use absolute path
python3 migrate_to_hybrid.py \
  --input /full/path/to/cenario_1000_viagens \
  --output ./output
```

### Memory issues with large scenarios
```bash
# Reduce items per file
python3 migrate_to_hybrid.py \
  --input input/large_scenario \
  --output output/hybrid \
  --items-per-file 1000
```

## 📚 Documentation

- [**MIGRATION_GUIDE.md**](./MIGRATION_GUIDE.md) - Complete guide with all features
- [**MIGRATION_SUMMARY_PT.md**](./MIGRATION_SUMMARY_PT.md) - Portuguese executive summary
- [**MIGRATION_COMPLETE.md**](./MIGRATION_COMPLETE.md) - Implementation details

## 🤝 Support

For questions or issues:
1. Check [MIGRATION_GUIDE.md](./MIGRATION_GUIDE.md)
2. Review [MIGRATION_COMPLETE.md](./MIGRATION_COMPLETE.md)
3. Run with `--help` flag

## ✨ Features

- ✅ Preserves valid maps and IDs
- ✅ Configurable via YAML or CLI
- ✅ Multiple MICRO selection strategies
- ✅ Optional vehicle type conversion
- ✅ Automatic file splitting
- ✅ Detailed reports (MD + JSON)
- ✅ Graph validation
- ✅ Reproducible (seed control)

## 🎓 Differences: Mobility vs Hybrid

| Feature | Mobility | Hybrid |
|---------|----------|--------|
| Simulation | MESO only | MICRO + MESO |
| Nodes | mobility.actor.Node | hybrid.actor.Node |
| Links | mobility.actor.Link | hybrid.actor.Link |
| Lanes | ❌ | ✅ (MICRO links) |
| Vehicles | Car only | Car, Bus, Bicycle, Motorcycle |
| Driver Attrs | ❌ | ✅ |
| Car-Following | ❌ | ✅ (Krauss, IDM) |
| Lane Change | ❌ | ✅ (MICRO mode) |

## 📄 License

Part of the Hyperbolic Time Chamber project.

---

**Ready to migrate!** 🚀

See [MIGRATION_GUIDE.md](./MIGRATION_GUIDE.md) for complete documentation.
