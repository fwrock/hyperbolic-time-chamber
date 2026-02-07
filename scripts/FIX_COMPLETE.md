# ✅ Configuration Format Fix - Complete

**Issue:** Generator was creating `application.conf` (HOCON format) instead of `simulation.json` (JSON format)  
**Status:** ✅ **FIXED**  
**Date:** February 7, 2025

---

## 🔍 Problem

The scenario generator was creating configuration files in HOCON format (`application.conf`) with variable interpolation:

```hocon
htc {
  simulation {
    dataSource {
      path = "${htc.simulation.dataPath}/data/nodes.json"
    }
  }
}
```

But HTC expects JSON format (`simulation.json`) as shown in [docker-compose.yml](../docker-compose.yml):

```yaml
environment:
  - HTC_SIMULATION_CONFIG_FILE=/app/.../simulation.json  # ← JSON expected
```

---

## ✅ Solution

### 1. Updated Generator

**File:** [generate_hybrid_scenario.py](generate_hybrid_scenario.py)

**Changes:**
- Renamed method: `_write_application_conf()` → `_write_simulation_json()`
- Changed format: HOCON → JSON
- Removed variable interpolation
- Used relative paths directly

**New JSON structure:**
```json
{
  "simulation": {
    "name": "Scenario Name",
    "description": "Description",
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
          "info": {"path": "data/nodes.json"}
        }
      },
      ...
    ],
    "cityMapFile": "data/city_map.json"
  }
}
```

### 2. Updated Validator

**File:** [validate_scenario.py](validate_scenario.py)

**Changes:**
```python
required_files = [
    ...
    "simulation.json",      # ← Changed from "application.conf"
    ...
]
```

### 3. Updated Documentation

All docs updated to reference `simulation.json`:
- ✅ [README.md](README.md)
- ✅ [QUICKSTART.md](QUICKSTART.md)
- ✅ [INDEX.md](INDEX.md)
- ✅ [SUMMARY.md](SUMMARY.md)
- ✅ [DEPLOYMENT.md](DEPLOYMENT.md)

---

## 🧪 Verification

### Test 1: Generate Scenario
```bash
$ python3 scripts/generate_hybrid_scenario.py --quick-test
✓ Configuration: simulation.json  # ← Correct format
```

### Test 2: Validate Structure
```bash
$ python3 scripts/validate_scenario.py test_scenario
✅ No issues found!  # ← simulation.json validated
```

### Test 3: Inspect JSON
```bash
$ cat test_scenario/simulation.json | jq .
{
  "simulation": {
    "name": "Quick Test",
    "startTick": 0,
    ...
  }
}  # ← Valid JSON structure
```

---

## 📊 Impact

### Before Fix
```
scenario_name/
├── application.conf        # ❌ HOCON format (wrong)
├── scenario_metadata.json
└── data/
    └── ...
```

### After Fix
```
scenario_name/
├── simulation.json         # ✅ JSON format (correct)
├── scenario_metadata.json
└── data/
    └── ...
```

---

## 📚 Related Files

| File | Status | Notes |
|------|--------|-------|
| [generate_hybrid_scenario.py](generate_hybrid_scenario.py) | ✅ Updated | Uses `_write_simulation_json()` |
| [validate_scenario.py](validate_scenario.py) | ✅ Updated | Checks for `simulation.json` |
| [JSON_FORMAT_MIGRATION.md](JSON_FORMAT_MIGRATION.md) | ✅ Created | Complete migration guide |
| All `*.md` docs | ✅ Updated | Reference `simulation.json` |

---

## 🎯 Key Takeaways

1. **Format:** JSON, not HOCON
2. **Filename:** `simulation.json`, not `application.conf`
3. **Paths:** Relative paths (`data/nodes.json`), not variables
4. **Structure:** Nested under `"simulation": { ... }`
5. **Compatibility:** Matches HTC's expected input format

---

## ✅ Checklist

- [x] Generator creates `simulation.json`
- [x] JSON format is valid and parseable
- [x] Structure matches HTC expectations
- [x] Validator checks for correct filename
- [x] Documentation updated
- [x] Test scenarios generated successfully
- [x] Migration guide created

---

**Result:** ✅ All scenarios now generate with correct JSON format compatible with HTC's input expectations.
