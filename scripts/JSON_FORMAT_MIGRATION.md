# 🔄 JSON Configuration Format Migration

**Date:** February 7, 2025  
**Status:** ✅ Complete

---

## 📋 Summary

The scenario generator has been updated to create `simulation.json` (JSON format) instead of `application.conf` (HOCON format) to match the Hyperbolic Time Chamber's expected input format.

---

## 🔧 Changes Made

### 1. Generator Script (`generate_hybrid_scenario.py`)

**Before:**
```python
def _write_application_conf(self, path: Path):
    """Write application.conf for this scenario"""
    # HOCON format with ${variables}
```

**After:**
```python
def _write_simulation_json(self, path: Path):
    """Write simulation.json configuration file"""
    # Clean JSON format
```

**New JSON Structure:**
```json
{
  "simulation": {
    "name": "Scenario Name",
    "description": "Scenario description",
    "startTick": 0,
    "endTick": 3600,
    "tickDuration": 1.0,
    "randomSeed": 42,
    "actorsDataSources": [
      {
        "id": "nodes",
        "classType": "hybrid.actor.Node",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/nodes.json"
          }
        }
      },
      {
        "id": "links",
        "classType": "hybrid.actor.Link",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/links.json"
          }
        }
      },
      {
        "id": "vehicles",
        "classType": "hybrid.actor.Car",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/vehicles.json"
          }
        }
      },
      {
        "id": "traffic_signals",
        "classType": "hybrid.actor.TrafficSignal",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/traffic_signals.json"
          }
        }
      }
    ],
    "cityMapFile": "data/city_map.json"
  }
}
```

### 2. Validator Script (`validate_scenario.py`)

**Updated Required Files:**
```python
required_files = [
    "data/city_map.json",
    "data/nodes.json",
    "data/links.json",
    "data/vehicles.json",
    "simulation.json",      # ← Changed from application.conf
    "scenario_metadata.json"
]
```

### 3. Documentation Updates

All documentation files updated:
- ✅ `README.md`
- ✅ `QUICKSTART.md`
- ✅ `INDEX.md`
- ✅ `SUMMARY.md`
- ✅ `DEPLOYMENT.md`

---

## 📂 Output Directory Structure

**Updated structure:**
```
scenario_name/
├── simulation.json              # ← New JSON configuration
├── scenario_metadata.json       # Metadata (unchanged)
├── SCENARIO_REPORT.md           # Report (unchanged)
└── data/
    ├── city_map.json           # Graph structure
    ├── nodes.json              # Node actors
    ├── links.json              # Link actors
    ├── vehicles.json           # Vehicle actors
    └── traffic_signals.json    # Traffic signals
```

---

## 🔍 Key Differences: HOCON vs JSON

| Feature | HOCON (Old) | JSON (New) |
|---------|-------------|------------|
| **Format** | Typesafe Config | Standard JSON |
| **Variables** | `${htc.simulation.dataPath}` | Direct paths |
| **Comments** | `#` and `//` | Not allowed |
| **Includes** | `include "file.conf"` | Not supported |
| **Paths** | Variable interpolation | Relative paths |
| **Parsing** | Requires HOCON library | Native JSON |

---

## 🎯 Benefits

1. **Compatibility**: Matches HTC's expected input format
2. **Simplicity**: No variable interpolation, direct paths
3. **Portability**: Standard JSON format, language-agnostic
4. **Validation**: Easier to validate with JSON schemas
5. **Tooling**: Better IDE/editor support for JSON

---

## 🧪 Testing

### Validation Test

```bash
# Generate test scenario
python3 scripts/generate_hybrid_scenario.py --quick-test

# Validate structure
python3 scripts/validate_scenario.py test_scenario

# Expected output:
# ✅ No issues found!
```

### Format Verification

```bash
# Check JSON format
cat test_scenario/simulation.json | jq .

# Should display properly formatted JSON
```

---

## 🔄 Migration Path

### For Existing Scenarios

If you have scenarios with `application.conf`, migrate them:

```bash
# 1. Extract values from application.conf
# 2. Create simulation.json with structure above
# 3. Remove variable interpolations (${...})
# 4. Use relative paths in actorsDataSources
# 5. Validate with validate_scenario.py
```

### For New Scenarios

Simply use the updated generator:

```bash
python3 scripts/generate_hybrid_scenario.py --config your_config.yaml
```

---

## 📚 References

- Docker Compose: Uses `HTC_SIMULATION_CONFIG_FILE=.../simulation.json`
- Documentation: [CONFIGURATION.md](../docs/CONFIGURATION.md)
- Examples: [docs/examples/](../docs/examples/)

---

## ✅ Verification Checklist

- [x] Generator creates `simulation.json` instead of `application.conf`
- [x] JSON structure matches HTC expectations
- [x] Validator checks for `simulation.json`
- [x] All documentation updated
- [x] Test scenarios generated successfully
- [x] JSON format validated with `jq`

---

**Status:** Migration complete ✅  
**Backward Compatibility:** Old scenarios with `application.conf` need manual migration  
**New Scenarios:** Use `simulation.json` by default
