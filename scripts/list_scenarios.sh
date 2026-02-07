#!/bin/bash
# List all available scenarios

echo "=============================================="
echo "📂 Available Scenarios"
echo "=============================================="
echo ""

SIMULATIONS_DIR="../simulations/input"
SCRIPT_DIR="."

# Count scenarios
if [ -d "$SIMULATIONS_DIR" ]; then
    scenario_count=$(find "$SIMULATIONS_DIR" -maxdepth 1 -type d ! -path "$SIMULATIONS_DIR" | wc -l)
    echo "Found $scenario_count scenario(s) in $SIMULATIONS_DIR/"
    echo ""
    
    for scenario_dir in "$SIMULATIONS_DIR"/*/; do
        if [ -d "$scenario_dir" ]; then
            scenario_name=$(basename "$scenario_dir")
            
            # Check if metadata exists
            if [ -f "$scenario_dir/scenario_metadata.json" ]; then
                echo "📁 $scenario_name"
                
                # Extract info using python
                python3 -c "
import json
import sys
try:
    with open('$scenario_dir/scenario_metadata.json', 'r') as f:
        data = json.load(f)
    stats = data.get('statistics', {})
    config = data.get('configuration', {})
    
    print(f\"   Name: {data.get('name', 'Unknown')}\")
    print(f\"   Nodes: {stats.get('nodes', 0)}, Links: {stats.get('links', 0)}, Vehicles: {stats.get('vehicles', 0)}\")
    print(f\"   MICRO: {stats.get('microLinks', 0)}, MESO: {stats.get('mesoLinks', 0)}\")
    print(f\"   Duration: {config.get('endTick', 0)} ticks\")
    print(f\"   Generated: {data.get('generated', 'Unknown')[:10]}\")
except Exception as e:
    print(f\"   Error reading metadata: {e}\")
" 2>/dev/null
                
                echo "   Path: $scenario_dir"
                echo ""
            else
                echo "📁 $scenario_name (no metadata)"
                echo "   Path: $scenario_dir"
                echo ""
            fi
        fi
    done
else
    echo "⚠️  Simulations directory not found: $SIMULATIONS_DIR"
    echo ""
fi

# Check test scenario
if [ -d "$SCRIPT_DIR/test_scenario" ]; then
    echo "🧪 Test Scenario (local)"
    if [ -f "$SCRIPT_DIR/test_scenario/scenario_metadata.json" ]; then
        python3 -c "
import json
try:
    with open('$SCRIPT_DIR/test_scenario/scenario_metadata.json', 'r') as f:
        data = json.load(f)
    stats = data.get('statistics', {})
    print(f\"   Nodes: {stats.get('nodes', 0)}, Links: {stats.get('links', 0)}, Vehicles: {stats.get('vehicles', 0)}\")
    print(f\"   MICRO: {stats.get('microLinks', 0)}, MESO: {stats.get('mesoLinks', 0)}\")
except:
    pass
" 2>/dev/null
    fi
    echo "   Path: $SCRIPT_DIR/test_scenario/"
    echo ""
fi

echo "=============================================="
echo "💡 Tips:"
echo "  • Validate: python3 validate_scenario.py <path>"
echo "  • Analyze:  python3 scenario_stats.py <path>"
echo "  • Generate: python3 generate_hybrid_scenario.py --help"
echo "=============================================="
